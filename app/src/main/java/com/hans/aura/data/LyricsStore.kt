package com.hans.aura.data

import kotlinx.coroutines.CancellationException

data class LyricLine(
    val startMs: Long,
    val text: String,
    val isSynced: Boolean,
    val words: List<TimedWord> = emptyList(),
    val voice: LyricVoice? = null,
    val adlib: LyricAdlib? = null,
    val credits: LyricCredits = LyricCredits(),
    /** Song part (verse, chorus, …) the line belongs to; neighbours that differ start a new part. */
    val part: Int = 0,
    val translation: LyricAdlib? = null
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

    /** Slices timed words into sub-line segments while keeping the line's exact spacing. */
    private fun sublineSegments(line: LyricLine): List<AdlibSegment> {
        var cursor = 0
        return line.words.mapIndexed { index, word ->
            val pattern = word.text.split(whitespace).joinToString("\\s+", transform = Regex::escape)
            val match = Regex(pattern).find(line.text, cursor)
            val end = if (index == line.words.lastIndex) line.text.length else match?.range?.last?.plus(1) ?: cursor
            AdlibSegment(line.text.substring(cursor, end), word.startMs).also { cursor = end }
        }.ifEmpty { listOf(AdlibSegment(line.text)) }
    }

    private data class LrcEntry(val line: LyricLine, val hasLineStamp: Boolean)

    private fun pairSameStampTranslations(entries: List<LrcEntry>): List<LyricLine> {
        val paired = mutableListOf<LyricLine>()
        var previous: LrcEntry? = null
        var runLength = 0
        for (entry in entries) {
            val line = entry.line
            val lead = previous?.line
            runLength = if (entry.hasLineStamp && previous?.hasLineStamp == true && line.startMs == lead?.startMs) runLength + 1 else 1
            if (runLength == 2 && lead != null && lead.text.isNotBlank() && line.text.isNotBlank() &&
                (line.voice == null || line.voice == lead.voice)
            ) {
                paired[paired.lastIndex] = lead.copy(translation = LyricAdlib(sublineSegments(line)))
            } else {
                paired += line
            }
            previous = entry
        }
        return paired
    }

    private fun applyLrcOffsetAndSort(lines: List<LyricLine>, offsetMs: Long): List<LyricLine> =
        lines.map { line ->
            line.copy(
                startMs = if (line.isSynced) shifted(line.startMs, offsetMs) else line.startMs,
                words = line.words.map { it.copy(startMs = shifted(it.startMs, offsetMs)) },
                translation = line.translation?.let { subline ->
                    subline.copy(segments = subline.segments.map { segment ->
                        segment.copy(startMs = segment.startMs?.let { shifted(it, offsetMs) })
                    })
                }
            )
        }.filterNot { isHeader(it.text) }.sortedBy { it.startMs }

    private fun finishLrc(entries: List<LrcEntry>, offsetMs: Long): List<LyricLine> =
        applyLrcOffsetAndSort(pairSameStampTranslations(entries), offsetMs)

    suspend fun fetch(api: JellyfinApi, itemId: String): List<LyricLine> {
        val response = try {
            api.getLyrics(itemId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
        return parseResponse(response)
    }

    internal fun parseResponse(resp: LyricsResponse): List<LyricLine> {
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
            val parsed = dtoLines.map {
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
                    startMs = lineStartMs ?: 0L,
                    text = if (hasInlineTags) normalize(content.replace(wordTag, "")) else content,
                    isSynced = !isHeader(content) && (lineStartMs != null || !it.cues.isNullOrEmpty() || hasInlineTags),
                    words = words,
                    voice = voice,
                    credits = credits
                ).let { line -> LrcEntry(line, lineStartMs != null && !isHeader(content)) }
            }
            return finishLrc(parsed, offsetMs)
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
        val parsed = mutableListOf<LyricLine>()
        val headers = mutableListOf<String>()
        val offsetMs = offsetTag.findAll(raw).mapNotNull { it.groupValues[1].toLongOrNull() }.lastOrNull() ?: 0L

        for (rawLine in raw.lines()) {
            val stamps = stampRegex.findAll(rawLine).toList()
            val (content, voice) = LrcVoices.split(rawLine.replace(stampRegex, "").trim())
            if (isHeader(content)) {
                headers += content
                parsed += LyricLine(0L, content, isSynced = false)
                continue
            }
            val words = inlineWords(content)
            val text = if (wordTag.containsMatchIn(content)) normalize(content.replace(wordTag, "")) else content
            if (stamps.isEmpty()) {
                parsed.add(LyricLine(0L, text, isSynced = false, voice = voice))
                continue
            }
            val firstMs = stampMs(stamps.first().groupValues[1], stamps.first().groupValues[2])
            for (m in stamps) {
                val ms = stampMs(m.groupValues[1], m.groupValues[2])
                parsed.add(LyricLine(startMs = ms, text = text, isSynced = true,
                    words = words.map { it.copy(startMs = it.startMs + ms - firstMs) }, voice = voice))
            }
        }

        val credits = headerCredits(headers.asSequence())
        val hasSynced = parsed.any { it.isSynced }
        return finishLrc(parsed.map { LrcEntry(it, it.isSynced) }, offsetMs).filter {
            if (hasSynced) it.isSynced else it.text.isNotEmpty()
        }.map { it.copy(credits = credits) }
    }
}
