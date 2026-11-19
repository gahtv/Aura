package com.hans.aura.data

data class LyricLine(
    val startMs: Long,
    val text: String,
    val isSynced: Boolean,
    val words: List<TimedWord> = emptyList(),
    val voice: LyricVoice? = null,
    val adlib: LyricAdlib? = null,
    val credits: LyricCredits = LyricCredits()
)

data class LyricCredits(
    val writers: List<String> = emptyList(),
    val transcribers: List<String> = emptyList()
) {
    internal operator fun plus(other: LyricCredits) = LyricCredits(
        writers = (writers + other.writers).distinct(),
        transcribers = (transcribers + other.transcribers).distinct()
    )
}

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
    private val idTag = Regex("""\[(ar|al|ti|au|lr|by|re|ve|tool|length|offset|#):([^\]]*)\]""", RegexOption.IGNORE_CASE)
    private val offsetTag = Regex("""\[offset:([+-]?\d+)\]""", RegexOption.IGNORE_CASE)

    private fun isHeader(text: String): Boolean =
        idTag.containsMatchIn(text) && text.replace(idTag, "").isBlank()

    private fun headerCredits(headers: Sequence<String>): LyricCredits {
        val tags = headers.flatMap { idTag.findAll(it) }.groupBy(
            { it.groupValues[1].lowercase() }, { normalize(it.groupValues[2]) }
        )
        fun values(tag: String) = tags[tag].orEmpty().filter { it.isNotEmpty() }
        return LyricCredits(
            writers = (values("au") + values("lr")).distinct(),
            transcribers = values("by").distinct()
        )
    }

    private fun shifted(startMs: Long, offsetMs: Long): Long = (startMs - offsetMs).coerceAtLeast(0L)

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

    suspend fun fetch(api: JellyfinApi, itemId: String): List<LyricLine> {
        val resp = runCatching { api.getLyrics(itemId) }.getOrNull()
            ?: return emptyList()
        val metadata = resp.metadata
        val serverCredits = LyricCredits(
            writers = listOfNotNull(metadata?.author, metadata?.lyricist).map(::normalize).filter { it.isNotEmpty() }.distinct(),
            transcribers = listOfNotNull(metadata?.by?.let(::normalize)?.takeIf { it.isNotEmpty() })
        )

        // 1) Structured synced/plain lines
        val dtoLines = resp.lyrics
        if (!dtoLines.isNullOrEmpty()) {
            val offsetMs = (metadata?.offset ?: 0L) / 10_000L
            val credits = serverCredits + headerCredits(dtoLines.asSequence().map { it.text }.filter(::isHeader))
            val firstInlineStart = mutableMapOf<String, Long>()
            return dtoLines.filterNot { isHeader(it.text.trim()) }.map {
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
                }?.takeIf { words -> words.isNotEmpty() } ?: if (hasInlineTags) {
                    // Jellyfin expands repeated line stamps but leaves their inline word stamps unchanged.
                    val offset = lineStartMs?.let { start ->
                        start - firstInlineStart.getOrPut(original) { start }
                    } ?: 0L
                    inlineWords(content).map { word -> word.copy(startMs = word.startMs + offset) }
                } else emptyList()
                LyricLine(
                    startMs = lineStartMs?.let { shifted(it, offsetMs) } ?: 0L,
                    text = if (hasInlineTags) normalize(content.replace(wordTag, "")) else content,
                    isSynced = lineStartMs != null || !it.cues.isNullOrEmpty() || hasInlineTags,
                    words = words.map { word -> word.copy(startMs = shifted(word.startMs, offsetMs)) },
                    voice = voice,
                    credits = credits
                )
            }
        }

        // 2) Raw text, possibly LRC-formatted
        val text = resp.lyricText?.takeIf { it.isNotBlank() } ?: return emptyList()
        return parseLyricsText(text).map { it.copy(credits = serverCredits + it.credits) }
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
        val headers = mutableListOf<String>()
        val offsetMs = offsetTag.findAll(raw).mapNotNull { it.groupValues[1].toLongOrNull() }.lastOrNull() ?: 0L

        for (rawLine in raw.lines()) {
            val stamps = stampRegex.findAll(rawLine).toList()
            val (content, voice) = LrcVoices.split(rawLine.replace(stampRegex, "").trim())
            if (isHeader(content)) {
                headers += content
                continue
            }
            val words = inlineWords(content)
            val text = if (wordTag.containsMatchIn(content)) normalize(content.replace(wordTag, "")) else content
            if (stamps.isEmpty()) {
                if (text.isNotEmpty()) plain.add(LyricLine(0L, text, isSynced = false, voice = voice))
                continue
            }
            val firstMs = stampMs(stamps.first().groupValues[1], stamps.first().groupValues[2])
            for (m in stamps) {
                val ms = stampMs(m.groupValues[1], m.groupValues[2])
                synced.add(LyricLine(startMs = shifted(ms, offsetMs), text = text, isSynced = true,
                    words = words.map { it.copy(startMs = shifted(it.startMs + ms - firstMs, offsetMs)) }, voice = voice))
            }
        }

        val credits = headerCredits(headers.asSequence())
        return when {
            synced.isNotEmpty() -> synced.sortedBy { it.startMs }
            plain.isNotEmpty() -> plain
            else -> emptyList()
        }.map { it.copy(credits = credits) }
    }
}
