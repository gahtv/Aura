package com.hans.aura.data

data class LyricLine(
    val startMs: Long,
    val text: String,
    val isSynced: Boolean,
    val words: List<TimedWord> = emptyList(),
    val voice: LyricVoice? = null,
    /** When the line stops being sung, if the source says so. */
    val endMs: Long? = null
)

/**
 * Fetches and normalizes lyrics from Jellyfin's /Audio/{id}/lyrics endpoint.
 * Handles both shapes seen in the wild:
 *  - synced JSON lines with Start (ticks, 100ns) from the LrcLib plugin
 *  - a plain LyricText blob (optionally LRC-formatted with [mm:ss.xx] stamps)
 */
object LyricsStore {
    fun parseLyricsText(raw: String): List<LyricLine> =
        if (Regex("""<(?:\w+:)?tt(?:\s|>)""").containsMatchIn(raw)) TtmlLyrics.parse(raw) else parseLrcText(raw)

    private val wordTag = Regex("""<(\d+):(\d+(?:\.\d+)?)>""")
    private val whitespace = Regex("""\s+""")

    private fun normalize(text: String): String = text.replace(whitespace, " ").trim()

    private fun stampMs(minutes: String, seconds: String): Long =
        minutes.toLong() * 60_000 + (seconds.replace(':', '.').toDouble() * 1000).toLong()

    private fun inlineWords(text: String): List<TimedWord> {
        val tags = wordTag.findAll(text).toList()
        return tags.mapIndexedNotNull { index, tag ->
            val word = normalize(text.substring(tag.range.last + 1, tags.getOrNull(index + 1)?.range?.first ?: text.length))
            word.takeIf { it.isNotEmpty() }?.let {
                TimedWord(stampMs(tag.groupValues[1], tag.groupValues[2]), it)
            }
        }
    }

    /** Enhanced LRC's closing `<mm:ss.xx>` after the last word marks when the line ends. */
    private fun inlineEnd(text: String): Long? {
        val tags = wordTag.findAll(text).toList()
        val last = tags.lastOrNull()?.takeIf { tags.size > 1 && text.substring(it.range.last + 1).isBlank() }
            ?: return null
        return stampMs(last.groupValues[1], last.groupValues[2])
    }

    /**
     * A blank synced line is how LRC (and Jellyfin's copy of it) marks the end of
     * the line before it: fold it into that line's [LyricLine.endMs] instead of
     * rendering an empty row.
     */
    internal fun foldBlankLines(lines: List<LyricLine>): List<LyricLine> {
        val folded = ArrayList<LyricLine>(lines.size)
        for (line in lines) {
            val previous = folded.lastOrNull()
            if (line.isSynced && line.text.isBlank()) {
                if (previous != null && previous.endMs == null && previous.startMs < line.startMs) {
                    folded[folded.lastIndex] = previous.copy(endMs = line.startMs)
                }
            } else {
                folded += line
            }
        }
        return folded
    }

    suspend fun fetch(api: JellyfinApi, itemId: String): List<LyricLine> {
        val resp = runCatching { api.getLyrics(itemId) }.getOrNull()
            ?: return emptyList()

        // 1) Structured synced/plain lines
        val dtoLines = resp.lyrics
        if (!dtoLines.isNullOrEmpty()) {
            val firstInlineStart = mutableMapOf<String, Long>()
            return foldBlankLines(dtoLines.map {
                val original = it.text.trim()
                val (content, voice) = LrcVoices.split(original)
                val prefixLength = it.text.indexOf(original) + original.length - content.length
                val lineStartMs = it.start?.div(10_000L)
                val hasInlineTags = wordTag.containsMatchIn(content)
                val words = it.cues?.mapNotNull { cue ->
                    val start = maxOf(cue.position, prefixLength)
                    val end = cue.endPosition
                    if (cue.position < 0 || end <= start || end > it.text.length) null else {
                        normalize(it.text.substring(start, end)).takeIf { word -> word.isNotEmpty() }
                            ?.let { word -> TimedWord(cue.start / 10_000L, word) }
                    }
                }?.takeIf { words -> words.isNotEmpty() }
                // Jellyfin expands repeated line stamps but leaves their inline word stamps unchanged.
                val inlineOffset = if (hasInlineTags && lineStartMs != null) {
                    lineStartMs - firstInlineStart.getOrPut(original) { lineStartMs }
                } else 0L
                LyricLine(
                    startMs = lineStartMs ?: 0L,
                    text = if (hasInlineTags) normalize(content.replace(wordTag, "")) else content,
                    isSynced = lineStartMs != null || !it.cues.isNullOrEmpty() || hasInlineTags,
                    words = words ?: inlineWords(content).map { word -> word.copy(startMs = word.startMs + inlineOffset) },
                    voice = voice,
                    endMs = it.cues?.mapNotNull { cue -> cue.end }?.maxOrNull()?.div(10_000L)
                        ?: inlineEnd(content)?.plus(inlineOffset)
                )
            })
        }

        // 2) Raw text, possibly LRC-formatted
        val text = resp.lyricText?.takeIf { it.isNotBlank() } ?: return emptyList()
        return parseLyricsText(text)
    }

    /**
     * Lyrics for [itemId], preferring the backend's own copy ([MusicSource.lyrics])
     * and falling back to the server's /Audio/{id}/lyrics endpoint when the source
     * has none. Parsing is the same LRC path either way.
     */
    suspend fun fetchPreferred(api: JellyfinApi, itemId: String): List<LyricLine> {
        (MusicSources.current as? JellyfinSource)?.let { return it.jellyfinLyrics(itemId) }
        val fromSource = runCatching { MusicSources.current?.lyrics(itemId) }.getOrNull()
        if (!fromSource.isNullOrBlank()) {
            val parsed = parseLyricsText(fromSource)
            android.util.Log.i("AURA_LYRICS", "source item=$itemId chars=${fromSource.length} lines=${parsed.size}")
            if (parsed.isNotEmpty()) return parsed
        } else {
            android.util.Log.i("AURA_LYRICS", "source item=$itemId empty")
        }
        return fetch(api, itemId)
    }

    /** Parses "[mm:ss.xx] line" LRC; falls back to one unsynced entry per line. */
    fun parseLrcText(raw: String): List<LyricLine> {
        val stampRegex = Regex("""\[(\d+):(\d+(?:[.:]\d+)?)\]""")
        val synced = mutableListOf<LyricLine>()
        val plain = mutableListOf<LyricLine>()

        for (rawLine in raw.lines()) {
            val stamps = stampRegex.findAll(rawLine).toList()
            val (content, voice) = LrcVoices.split(rawLine.replace(stampRegex, "").trim())
            val words = inlineWords(content)
            val text = if (wordTag.containsMatchIn(content)) normalize(content.replace(wordTag, "")) else content
            if (stamps.isEmpty()) {
                if (text.isNotEmpty()) plain.add(LyricLine(0L, text, isSynced = false, voice = voice))
                continue
            }
            val firstMs = stampMs(stamps.first().groupValues[1], stamps.first().groupValues[2])
            val endMs = inlineEnd(content)
            for (m in stamps) {
                val ms = stampMs(m.groupValues[1], m.groupValues[2])
                synced.add(LyricLine(startMs = ms, text = text, isSynced = true,
                    words = words.map { it.copy(startMs = it.startMs + ms - firstMs) }, voice = voice,
                    endMs = endMs?.plus(ms - firstMs)))
            }
        }

        return when {
            synced.isNotEmpty() -> foldBlankLines(synced.sortedBy { it.startMs })
            plain.isNotEmpty() -> plain
            else -> emptyList()
        }
    }
}
