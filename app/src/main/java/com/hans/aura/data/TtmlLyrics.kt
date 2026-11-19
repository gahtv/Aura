package com.hans.aura.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.util.Locale
import kotlin.math.roundToLong

internal object TtmlLyrics {
    private const val metadataNamespace = "http://www.w3.org/ns/ttml#metadata"
    private const val parameterNamespace = "http://www.w3.org/ns/ttml#parameter"
    private val appleLyricNamespaces = setOf(
        "http://music.apple.com/lyric-ttml-internal",
        "http://music.apple.com/lyrics",
        "http://itunes.apple.com/lyric-ttml-extensions"
    )
    private val whitespace = Regex("""\s+""")
    private val clockTime = Regex("""(?:(\d+):)?(\d+):(\d{2})(?:(\.\d+)|:(\d{2,})(?:\.(\d+))?)?""")
    private val offsetTime = Regex("""(\d+(?:\.\d+)?)(ms|h|m|s|f|t)?""")

    private fun normalize(text: String): String = text.replace(whitespace, " ").trim()

    private class Background {
        private data class Timing(val depth: Int, val start: Double?, val end: Double, val segmentEnd: Double?)

        private val timings = ArrayDeque<Timing>()
        private val segments = mutableListOf<AdlibSegment>()
        private var pendingSpace = false
        private var endMs: Long? = null
        val inside: Boolean get() = timings.isNotEmpty()

        fun enter(depth: Int, begin: Double?, end: Double?, duration: Double?, containerEnd: Double = Double.POSITIVE_INFINITY) {
            val parent = timings.lastOrNull()
            val start = begin ?: parent?.start
            val explicitEnd = listOfNotNull(end, duration?.let { start?.plus(it) }).minOrNull()
            val limit = minOf(parent?.end ?: Double.POSITIVE_INFINITY,
                explicitEnd ?: Double.POSITIVE_INFINITY, containerEnd)
            val segmentEnd = explicitEnd ?: parent?.segmentEnd?.takeIf { begin == null }
            timings.addLast(Timing(depth, start, limit, segmentEnd?.let { minOf(it, limit) }))
        }

        fun leave(depth: Int) {
            if (timings.lastOrNull()?.depth == depth) timings.removeLast()
        }

        fun space() {
            pendingSpace = true
        }

        fun append(text: String) {
            if (text.isEmpty()) return
            val normalized = text.replace(whitespace, " ")
            val content = normalized.trim()
            if (content.isNotEmpty()) {
                val separator = if (segments.isNotEmpty() && (pendingSpace || normalized.startsWith(' '))) " " else ""
                val timing = timings.last()
                val start = timing.start?.takeIf { it < timing.end }
                segments += AdlibSegment(separator + content, start?.roundToLong(), timing.segmentEnd?.roundToLong())
                if (start != null && timing.end.isFinite()) {
                    val end = timing.end.roundToLong()
                    endMs = maxOf(end, endMs ?: end)
                }
            }
            pendingSpace = normalized.endsWith(' ')
        }

        fun toAdlib(): LyricAdlib? =
            segments.takeIf { it.isNotEmpty() }?.let { LyricAdlib(it.toList(), endMs) }
    }

    private data class Rates(
        val frame: Int = 30,
        val effectiveFrame: Double = 30.0,
        val subFrame: Int = 1,
        val tick: Double = 1.0
    ) {
        fun timeMs(value: String?): Double? {
            val text = value?.trim() ?: return null
            val time = offsetTime.matchEntire(text)?.let { match ->
                val (number, unit) = match.destructured
                val count = number.toDouble()
                when (unit) {
                    "h" -> count * 3_600_000
                    "m" -> count * 60_000
                    "ms" -> count
                    "f" -> count * 1000 / effectiveFrame
                    "t" -> count * 1000 / tick
                    else -> count * 1000
                }
            } ?: clockTime.matchEntire(text)?.let { match ->
                val (hours, minutes, seconds, fraction, frames, subFrames) = match.destructured
                val frameCount = frames.toDoubleOrNull() ?: 0.0
                val subFrameCount = subFrames.toDoubleOrNull() ?: 0.0
                if ((frames.isNotEmpty() && hours.isEmpty()) || frameCount >= frame || subFrameCount >= subFrame) return null
                (3600 * (hours.toDoubleOrNull() ?: 0.0) + 60 * minutes.toDouble() +
                    (seconds + fraction).toDouble() + (frameCount + subFrameCount / subFrame) / effectiveFrame) * 1000
            }
            return time?.takeIf { it.isFinite() }
        }
    }

    private fun XmlPullParser.rates(): Rates {
        fun String?.positiveInt() = this?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        val frame = getAttributeValue(parameterNamespace, "frameRate").positiveInt()
        val subFrame = getAttributeValue(parameterNamespace, "subFrameRate").positiveInt() ?: 1
        val multiplier = getAttributeValue(parameterNamespace, "frameRateMultiplier")?.trim()?.split(whitespace)
            ?.takeIf { it.size == 2 }?.let { (numerator, denominator) ->
                val n = numerator.positiveInt() ?: return@let null
                val d = denominator.positiveInt() ?: return@let null
                n.toDouble() / d
            } ?: 1.0
        val effectiveFrame = (frame ?: 30) * multiplier
        val tick = getAttributeValue(parameterNamespace, "tickRate").positiveInt()?.toDouble()
            ?: if (frame != null) effectiveFrame * subFrame else 1.0
        return Rates(frame ?: 30, effectiveFrame, subFrame, tick)
    }

    private data class Interval(
        val start: Double = 0.0,
        val end: Double = Double.POSITIVE_INFINITY,
        val timed: Boolean = false
    ) {
        fun child(begin: Double?, end: Double?, duration: Double?, trackTimes: Boolean): Interval {
            val referenceStart = if (trackTimes) 0.0 else start
            val childStart = begin?.let { referenceStart + it } ?: start
            val childEnd = minOf(
                this.end,
                end?.let { referenceStart + it } ?: Double.POSITIVE_INFINITY,
                duration?.let { childStart + it } ?: Double.POSITIVE_INFINITY
            )
            return Interval(childStart, childEnd, timed || begin != null)
        }
    }

    private data class Word(val start: Double, val text: String, val end: Double?, val limit: Double)

    fun parse(raw: String): List<LyricLine> = runCatching {
        val parser = Xml.newPullParser()
        parser.setInput(raw.reader())
        val lines = mutableListOf<Pair<LyricLine, String?>>()
        val writers = mutableListOf<String>()
        val agentTypes = mutableMapOf<String, String>()
        val agentKinds = mutableMapOf<String, LyricVoice.Kind>()
        fun voice(value: String?): LyricVoice? {
            val ids = value?.trim()?.split(whitespace)?.filter { it.isNotEmpty() }.orEmpty()
            if (ids.isEmpty()) return null
            val kinds = ids.map { id ->
                agentKinds.getOrPut(id) {
                    if (agentTypes[id] == "group") LyricVoice.Kind.GROUP
                    else if (agentKinds.values.count { it != LyricVoice.Kind.GROUP } % 2 == 0) LyricVoice.Kind.PRIMARY
                    else LyricVoice.Kind.SECONDARY
                }
            }
            return LyricVoice(ids.joinToString(" "), if (ids.size > 1) LyricVoice.Kind.GROUP else kinds.single())
        }
        var lineDepth = -1
        var sublinesDepth = -1
        var metadataKind = ""
        var metadataTextDepth = -1
        var metadataLanguage = ""
        var metadataKey: String? = null
        var metadataText = Background()
        val metadataSublines = mutableMapOf<String, LinkedHashMap<String, MutableMap<String, LyricAdlib>>>()
        var lineKey: String? = null
        var wordDepth = -1
        var rates = Rates()
        var appleTrackTimes = false
        val intervals = mutableListOf(Interval())
        var lineBegin: Double? = null
        var legacyWordTimes = false
        var wordEnd: Double? = null
        var lineAgent: String? = null
        var part = 0
        var itunesMetadataDepth = -1
        var songwritersDepth = -1
        var songwriterDepth = -1
        val songwriterText = StringBuilder()
        val text = StringBuilder()
        var background = Background()
        var sublines = linkedMapOf("x-translation" to Background(), "x-roman" to Background())
        val wordText = StringBuilder()
        var words = mutableListOf<Word>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when {
                        parser.name == "iTunesMetadata" && lineDepth < 0 ->
                            itunesMetadataDepth = parser.depth
                        parser.name == "songwriters" && itunesMetadataDepth == parser.depth - 1 ->
                            songwritersDepth = parser.depth
                        parser.name == "songwriter" && songwritersDepth == parser.depth - 1 -> {
                            songwriterDepth = parser.depth
                            songwriterText.clear()
                        }
                    }
                    if (parser.depth == 1 && parser.name == "tt") {
                        rates = parser.rates()
                        appleTrackTimes = (0 until parser.getNamespaceCount(parser.depth)).any {
                            parser.getNamespaceUri(it) in appleLyricNamespaces
                        }
                    }
                    val parent = intervals.last()
                    val referenceStart = if (appleTrackTimes) 0.0 else parent.start
                    val timedElement = when (parser.name) {
                        "body", "div", "p", "span" -> true
                        else -> false
                    }
                    val begin = if (timedElement) rates.timeMs(parser.getAttributeValue(null, "begin")) else null
                    val end = if (timedElement) rates.timeMs(parser.getAttributeValue(null, "end")) else null
                    val duration = if (timedElement) rates.timeMs(parser.getAttributeValue(null, "dur")) else null
                    val interval = if (timedElement) parent.child(begin, end, duration, trackTimes = appleTrackTimes) else parent
                    intervals.add(interval)
                    if (parser.name in setOf("translations", "transliterations") && lineDepth < 0) {
                        sublinesDepth = parser.depth
                        metadataKind = parser.name.removeSuffix("s")
                        metadataSublines.getOrPut(metadataKind) { linkedMapOf() }
                    }
                    if (sublinesDepth >= 0 && parser.name == metadataKind) {
                        metadataLanguage = parser.getAttributeValue("http://www.w3.org/XML/1998/namespace", "lang").orEmpty()
                        metadataSublines.getValue(metadataKind).getOrPut(metadataLanguage) { mutableMapOf() }
                    }
                    if (sublinesDepth >= 0 && parser.name == "text") {
                        metadataTextDepth = parser.depth
                        metadataKey = parser.getAttributeValue(null, "for")
                        metadataText = Background()
                    }
                    if (metadataTextDepth >= 0) {
                        if (parser.name == "br") metadataText.space()
                        metadataText.enter(parser.depth, begin, end, duration)
                    }
                    if (parser.name == "agent" && parser.namespace == metadataNamespace) {
                        parser.getAttributeValue("http://www.w3.org/XML/1998/namespace", "id")?.let {
                            agentTypes[it] = parser.getAttributeValue(null, "type").orEmpty()
                        }
                    }
                    if (parser.name == "div") part++
                    if (parser.name == "p") {
                        lineDepth = parser.depth
                        lineBegin = begin
                        legacyWordTimes = parser.getAttributeValue(null, "timeContainer") == null
                        lineAgent = parser.getAttributeValue(metadataNamespace, "agent")?.takeIf { it.isNotBlank() }
                        text.clear()
                        background = Background()
                        sublines = linkedMapOf("x-translation" to Background(), "x-roman" to Background())
                        lineKey = (0 until parser.attributeCount).firstOrNull { parser.getAttributeName(it) == "key" }
                            ?.let { parser.getAttributeValue(it) }
                        words = mutableListOf()
                    }
                    if (lineDepth >= 0) {
                        val role = (0 until parser.attributeCount).firstOrNull { parser.getAttributeName(it) == "role" }
                            ?.let { parser.getAttributeValue(it) }
                        val subline = sublines.values.firstOrNull { it.inside } ?: sublines[role]
                        if (subline != null) {
                            if (!subline.inside || parser.name == "br") subline.space()
                            if (legacyWordTimes) {
                                subline.enter(parser.depth, begin, end, duration)
                            } else {
                                subline.enter(parser.depth, interval.start.takeIf { begin != null }, interval.end, null)
                            }
                        } else if (background.inside || role == "x-bg") {
                            if (!background.inside || parser.name == "br") background.space()
                            if (legacyWordTimes && !appleTrackTimes) {
                                // Apple-style background cues use the track clock and may overlap the lead.
                                background.enter(parser.depth, begin, end, duration)
                            } else {
                                background.enter(parser.depth, interval.start.takeIf { begin != null },
                                    end?.let { referenceStart + it }, duration, interval.end)
                            }
                        } else if (parser.name == "span") {
                            if (lineAgent == null) {
                                lineAgent = parser.getAttributeValue(metadataNamespace, "agent")?.takeIf { it.isNotBlank() }
                            }
                            if (wordDepth < 0 && begin != null) {
                                wordDepth = parser.depth
                                wordEnd = listOfNotNull(end?.let { referenceStart + it },
                                    duration?.let { interval.start + it }).minOrNull()
                                wordText.clear()
                            }
                        } else if (parser.name == "br") text.append(' ')
                    }
                }
                XmlPullParser.TEXT -> {
                    if (songwriterDepth >= 0) songwriterText.append(parser.text)
                    if (metadataTextDepth >= 0) {
                        metadataText.append(parser.text)
                    } else if (lineDepth >= 0) {
                        val subline = sublines.values.firstOrNull { it.inside }
                        if (subline != null) {
                            subline.append(parser.text)
                        } else if (background.inside) {
                            background.append(parser.text)
                        } else {
                            text.append(parser.text)
                            if (wordDepth >= 0) wordText.append(parser.text)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.depth == songwriterDepth) {
                        normalize(songwriterText.toString()).takeIf { it.isNotEmpty() }?.let(writers::add)
                        songwriterDepth = -1
                    }
                    if (parser.depth == songwritersDepth) songwritersDepth = -1
                    if (parser.depth == itunesMetadataDepth) itunesMetadataDepth = -1
                    val interval = intervals.removeAt(intervals.lastIndex)
                    if (parser.depth == wordDepth) {
                        normalize(wordText.toString()).takeIf { it.isNotEmpty() }?.let {
                            words += Word(interval.start, it, wordEnd, interval.end)
                        }
                        wordDepth = -1
                    }
                    metadataText.leave(parser.depth)
                    if (parser.depth == metadataTextDepth) {
                        val key = metadataKey
                        val value = metadataText.toAdlib()
                        if (key != null && value != null) metadataSublines.getValue(metadataKind).getValue(metadataLanguage)[key] = value
                        metadataTextDepth = -1
                    }
                    if (parser.depth == sublinesDepth) sublinesDepth = -1
                    sublines.values.forEach { it.leave(parser.depth) }
                    background.leave(parser.depth)
                    if (parser.depth == lineDepth) {
                        val line = normalize(text.toString())
                        val begin = lineBegin
                        // Undeclared lyric paragraphs may use track-relative word timestamps.
                        val adjustment = if (!appleTrackTimes && legacyWordTimes && begin != null &&
                            words.any { (it.start - interval.start).roundToLong() >= begin.roundToLong() }
                        ) begin else 0.0
                        val timedWords = words.mapNotNull {
                            val start = it.start - adjustment
                            val end = it.end?.let { end ->
                                minOf(end - adjustment, if (adjustment == 0.0) it.limit else interval.end).roundToLong()
                            }
                            if (start < interval.end) TimedWord(start.roundToLong(), it.text, end) else null
                        }
                        val start = if (begin != null) interval.start.roundToLong()
                            else timedWords.firstOrNull()?.startMs ?: interval.start.roundToLong().takeIf { interval.timed }
                        val end = interval.end.takeIf { it.isFinite() }?.roundToLong() ?: timedWords.sungEnd()
                        if (line.isNotEmpty() && interval.start < interval.end) {
                            val lyric = LyricLine(
                                start ?: 0L, line, start != null, timedWords, voice(lineAgent), background.toAdlib(),
                                part = part, translation = sublines.getValue("x-translation").toAdlib(),
                                romanization = sublines.getValue("x-roman").toAdlib(), endMs = end
                            )
                            lines += lyric to lineKey
                        }
                        lineDepth = -1
                    }
                }
            }
            parser.next()
        }
        val credits = LyricCredits(writers = writers.distinct())
        fun preferred(kind: String): Map<String, LyricAdlib>? {
            val languages = metadataSublines[kind] ?: return null
            return languages.entries.firstOrNull {
                val locale = Locale.forLanguageTag(it.key)
                if (kind == "transliteration") locale.script == "Latn"
                else locale.language == Locale.getDefault().language
            }?.value ?: languages.values.firstOrNull()
        }
        val translation = preferred("translation")
        val romanization = preferred("transliteration")
        lines.map { (line, key) ->
            line.copy(translation = line.translation ?: translation?.get(key),
                romanization = line.romanization ?: romanization?.get(key), credits = credits)
        }.sortedBy { it.startMs }
    }.getOrDefault(emptyList())
}
