package com.hans.aura.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val translation: LyricAdlib? = null,
    val romanization: LyricAdlib? = null,
    /** When the line stops being sung, if the source says so. */
    val endMs: Long? = null
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
                val trailing = tags.getOrNull(index + 1)?.takeIf { next ->
                    text.substring(next.range.last + 1).isBlank()
                }
                TimedWord(stampMs(tag.groupValues[1], tag.groupValues[2]), it,
                    trailing?.let { next -> stampMs(next.groupValues[1], next.groupValues[2]) })
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

    /** Slices timed words into sub-line segments while keeping the line's exact spacing. */
    private fun sublineSegments(line: LyricLine): List<AdlibSegment> {
        var cursor = 0
        return line.words.mapIndexed { index, word ->
            val pattern = word.text.split(whitespace).joinToString("\\s+", transform = Regex::escape)
            val match = Regex(pattern).find(line.text, cursor)
            val end = if (index == line.words.lastIndex) line.text.length else match?.range?.last?.plus(1) ?: cursor
            AdlibSegment(line.text.substring(cursor, end), word.startMs, word.endMs).also { cursor = end }
        }.ifEmpty { listOf(AdlibSegment(line.text)) }
    }

    /** [row] groups the copies of one source line that a repeated-stamp line expands into. */
    private data class LrcEntry(val line: LyricLine, val hasLineStamp: Boolean, val row: Int)

    private fun pairSameStampTranslations(entries: List<LrcEntry>): List<LyricLine> {
        /** [index] is the entry's slot in the output, or null once it became a translation. */
        class Placed(val entry: LrcEntry, val runLength: Int, val index: Int?)
        val paired = mutableListOf<LyricLine>()
        var previousRow = mutableListOf<Placed>()
        var row = mutableListOf<Placed>()
        for (entry in entries) {
            if (row.isNotEmpty() && row.first().entry.row != entry.row) {
                previousRow = row
                row = mutableListOf()
            }
            val line = entry.line
            val lead = previousRow.firstOrNull { it.entry.line.startMs == line.startMs }
                ?.also(previousRow::remove)
                ?.takeIf { entry.hasLineStamp && it.entry.hasLineStamp }
            val runLength = (lead?.runLength ?: 0) + 1
            val target = lead?.index
            val leadLine = lead?.entry?.line
            if (runLength == 2 && target != null && leadLine != null && leadLine.text.isNotBlank() &&
                line.text.isNotBlank() && (line.voice == null || line.voice == leadLine.voice)
            ) {
                paired[target] = paired[target].copy(translation = LyricAdlib(sublineSegments(line)))
                row += Placed(entry, runLength, null)
            } else {
                paired += line
                row += Placed(entry, runLength, paired.lastIndex)
            }
        }
        return paired
    }

    private fun applyLrcOffsetAndSort(lines: List<LyricLine>, offsetMs: Long): List<LyricLine> =
        lines.map { line ->
            line.copy(
                startMs = if (line.isSynced) shifted(line.startMs, offsetMs) else line.startMs,
                endMs = line.endMs?.let { shifted(it, offsetMs) },
                words = line.words.map { it.copy(startMs = shifted(it.startMs, offsetMs),
                    endMs = it.endMs?.let { end -> shifted(end, offsetMs) }) },
                translation = line.translation?.let { subline ->
                    subline.copy(segments = subline.segments.map { segment ->
                        segment.copy(startMs = segment.startMs?.let { shifted(it, offsetMs) },
                            endMs = segment.endMs?.let { shifted(it, offsetMs) })
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
        return parseResponseOffMain(response)
    }

    internal suspend fun parseResponseOffMain(response: LyricsResponse): List<LyricLine> =
        withContext(Dispatchers.Default) { parseResponse(response) }

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
                            ?.let { word -> TimedWord(cue.start / 10_000L, word, cue.end?.div(10_000L)) }
                    }
                }?.takeIf { words -> words.isNotEmpty() }
                // Jellyfin expands repeated line stamps but leaves their inline word stamps unchanged.
                val inlineOffset = if (hasInlineTags && lineStartMs != null) {
                    lineStartMs - firstInlineStart.getOrPut(original) { lineStartMs }
                } else 0L
                LyricLine(
                    startMs = lineStartMs ?: 0L,
                    text = if (hasInlineTags) normalize(content.replace(wordTag, "")) else content,
                    isSynced = !isHeader(content) && (lineStartMs != null || !it.cues.isNullOrEmpty() || hasInlineTags),
                    words = words ?: inlineWords(content).map { word ->
                        word.copy(startMs = word.startMs + inlineOffset, endMs = word.endMs?.plus(inlineOffset))
                    },
                    voice = voice,
                    credits = credits,
                    endMs = words?.sungEnd() ?: inlineEnd(content)?.plus(inlineOffset)
                ) to (lineStartMs != null && !isHeader(content))
            }
            // Jellyfin expands a repeated-stamp line into consecutive copies with distinct starts.
            var row = -1
            val rowStarts = mutableSetOf<Long>()
            val entries = parsed.mapIndexed { index, (line, hasLineStamp) ->
                val previous = parsed.getOrNull(index - 1)
                val expandedCopy = previous != null && hasLineStamp && previous.second &&
                    dtoLines[index - 1].text == dtoLines[index].text && line.startMs !in rowStarts
                if (!expandedCopy) { row++; rowStarts.clear() }
                rowStarts += line.startMs
                LrcEntry(line, hasLineStamp, row)
            }
            return foldBlankLines(finishLrc(entries, offsetMs))
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
        val fromSource = try {
            MusicSources.current?.lyrics(itemId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (!fromSource.isNullOrBlank()) {
            val parsed = withContext(Dispatchers.Default) { parseLyricsText(fromSource) }
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
        val parsed = mutableListOf<LrcEntry>()
        val headers = mutableListOf<String>()
        val offsetMs = offsetTag.findAll(raw).mapNotNull { it.groupValues[1].toLongOrNull() }.lastOrNull() ?: 0L

        for ((row, rawLine) in raw.lines().withIndex()) {
            val stamps = stampRegex.findAll(rawLine).toList()
            val (content, voice) = LrcVoices.split(rawLine.replace(stampRegex, "").trim())
            if (isHeader(content)) {
                headers += content
                parsed += LrcEntry(LyricLine(0L, content, isSynced = false), false, row)
                continue
            }
            val words = inlineWords(content)
            val text = if (wordTag.containsMatchIn(content)) normalize(content.replace(wordTag, "")) else content
            if (stamps.isEmpty()) {
                parsed += LrcEntry(LyricLine(0L, text, isSynced = false, voice = voice), false, row)
                continue
            }
            val firstMs = stampMs(stamps.first().groupValues[1], stamps.first().groupValues[2])
            val endMs = inlineEnd(content)
            for (m in stamps) {
                val ms = stampMs(m.groupValues[1], m.groupValues[2])
                parsed += LrcEntry(LyricLine(startMs = ms, text = text, isSynced = true,
                    words = words.map { it.copy(startMs = it.startMs + ms - firstMs,
                        endMs = it.endMs?.let { end -> end + ms - firstMs }) }, voice = voice,
                    endMs = endMs?.let { it + ms - firstMs }), true, row)
            }
        }

        val credits = headerCredits(headers.asSequence())
        val hasSynced = parsed.any { it.line.isSynced }
        val lines = finishLrc(parsed, offsetMs)
        return (if (hasSynced) foldBlankLines(lines.filter { it.isSynced }) else lines.filter { it.text.isNotEmpty() })
            .map { it.copy(credits = credits) }
    }
}
