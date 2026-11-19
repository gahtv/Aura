package com.hans.aura.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import kotlin.math.roundToLong

internal object TtmlLyrics {
    private const val metadataNamespace = "http://www.w3.org/ns/ttml#metadata"
    private const val parameterNamespace = "http://www.w3.org/ns/ttml#parameter"
    private val whitespace = Regex("""\s+""")
    private val clockTime = Regex("""(?:(\d+):)?(\d+):(\d{2})(?:(\.\d+)|:(\d{2,})(?:\.(\d+))?)?""")
    private val offsetTime = Regex("""(\d+(?:\.\d+)?)(ms|h|m|s|f|t)?""")

    private fun normalize(text: String): String = text.replace(whitespace, " ").trim()

    private class Background {
        private data class Timing(val depth: Int, val start: Double?, val end: Double)

        private val timings = ArrayDeque<Timing>()
        private val segments = mutableListOf<AdlibSegment>()
        private var pendingSpace = false
        private var endMs: Long? = null
        val inside: Boolean get() = timings.isNotEmpty()

        fun enter(depth: Int, begin: Double?, end: Double?, duration: Double?) {
            val parent = timings.lastOrNull()
            val start = begin ?: parent?.start
            val limit = minOf(
                parent?.end ?: Double.POSITIVE_INFINITY,
                end ?: Double.POSITIVE_INFINITY,
                duration?.let { start?.plus(it) } ?: Double.POSITIVE_INFINITY
            )
            timings.addLast(Timing(depth, start, limit))
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
                segments += AdlibSegment(separator + content, start?.roundToLong())
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
        fun child(begin: Double?, end: Double?, duration: Double?): Interval {
            val childStart = start + (begin ?: 0.0)
            val childEnd = minOf(
                this.end,
                end?.let { start + it } ?: Double.POSITIVE_INFINITY,
                duration?.let { childStart + it } ?: Double.POSITIVE_INFINITY
            )
            return Interval(childStart, childEnd, timed || begin != null)
        }
    }

    private data class Word(val start: Double, val text: String, val end: Double?, val limit: Double)

    fun parse(raw: String): List<LyricLine> = runCatching {
        val parser = Xml.newPullParser()
        parser.setInput(raw.reader())
        val lines = mutableListOf<LyricLine>()
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
        var skipDepth = -1
        var wordDepth = -1
        var rates = Rates()
        val intervals = mutableListOf(Interval())
        var lineBegin: Double? = null
        var legacyWordTimes = false
        var wordEnd: Double? = null
        var lineAgent: String? = null
        val text = StringBuilder()
        var background = Background()
        val wordText = StringBuilder()
        var words = mutableListOf<Word>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.depth == 1 && parser.name == "tt") rates = parser.rates()
                    val parent = intervals.last()
                    val timedElement = when (parser.name) {
                        "body", "div", "p", "span" -> true
                        else -> false
                    }
                    val begin = if (timedElement) rates.timeMs(parser.getAttributeValue(null, "begin")) else null
                    val end = if (timedElement) rates.timeMs(parser.getAttributeValue(null, "end")) else null
                    val duration = if (timedElement) rates.timeMs(parser.getAttributeValue(null, "dur")) else null
                    val interval = if (timedElement) parent.child(begin, end, duration) else parent
                    intervals.add(interval)
                    if (parser.name == "agent" && parser.namespace == metadataNamespace) {
                        parser.getAttributeValue("http://www.w3.org/XML/1998/namespace", "id")?.let {
                            agentTypes[it] = parser.getAttributeValue(null, "type").orEmpty()
                        }
                    }
                    if (parser.name == "p") {
                        lineDepth = parser.depth
                        lineBegin = begin
                        legacyWordTimes = parser.getAttributeValue(null, "timeContainer") == null
                        lineAgent = parser.getAttributeValue(metadataNamespace, "agent")?.takeIf { it.isNotBlank() }
                        text.clear()
                        background = Background()
                        words = mutableListOf()
                    }
                    if (lineDepth >= 0 && skipDepth < 0) {
                        val role = (0 until parser.attributeCount).firstOrNull { parser.getAttributeName(it) == "role" }
                            ?.let { parser.getAttributeValue(it) }
                        if (role == "x-translation") {
                            skipDepth = parser.depth
                        } else if (background.inside || role == "x-bg") {
                            if (!background.inside || parser.name == "br") background.space()
                            if (legacyWordTimes) {
                                // Apple-style background cues use the track clock and may overlap the lead.
                                background.enter(parser.depth, begin, end, duration)
                            } else {
                                background.enter(parser.depth, interval.start.takeIf { begin != null }, interval.end, null)
                            }
                        } else if (parser.name == "span") {
                            if (lineAgent == null) {
                                lineAgent = parser.getAttributeValue(metadataNamespace, "agent")?.takeIf { it.isNotBlank() }
                            }
                            if (wordDepth < 0 && begin != null) {
                                wordDepth = parser.depth
                                wordEnd = listOfNotNull(end?.let { parent.start + it },
                                    duration?.let { interval.start + it }).minOrNull()
                                wordText.clear()
                            }
                        } else if (parser.name == "br") text.append(' ')
                    }
                }
                XmlPullParser.TEXT -> if (lineDepth >= 0 && skipDepth < 0) {
                    if (background.inside) {
                        background.append(parser.text)
                    } else {
                        text.append(parser.text)
                        if (wordDepth >= 0) wordText.append(parser.text)
                    }
                }
                XmlPullParser.END_TAG -> {
                    val interval = intervals.removeAt(intervals.lastIndex)
                    if (parser.depth == wordDepth) {
                        normalize(wordText.toString()).takeIf { it.isNotEmpty() }?.let {
                            words += Word(interval.start, it, wordEnd, interval.end)
                        }
                        wordDepth = -1
                    }
                    if (parser.depth == skipDepth) skipDepth = -1
                    background.leave(parser.depth)
                    if (parser.depth == lineDepth) {
                        val line = normalize(text.toString())
                        val begin = lineBegin
                        // Undeclared lyric paragraphs may use track-relative word timestamps.
                        val adjustment = if (legacyWordTimes && begin != null &&
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
                        if (line.isNotEmpty() && interval.start < interval.end) {
                            lines += LyricLine(start ?: 0L, line, start != null, timedWords, voice(lineAgent), background.toAdlib())
                        }
                        lineDepth = -1
                    }
                }
            }
            parser.next()
        }
        lines.sortedBy { it.startMs }
    }.getOrDefault(emptyList())
}
