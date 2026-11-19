package com.hans.aura.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import kotlin.math.roundToLong

internal object TtmlLyrics {
    private const val metadataNamespace = "http://www.w3.org/ns/ttml#metadata"
    private val whitespace = Regex("""\s+""")

    private fun normalize(text: String): String = text.replace(whitespace, " ").trim()

    private fun timeMs(value: String?): Long? {
        val text = value?.trim() ?: return null
        if (text.endsWith("ms")) return text.dropLast(2).toDoubleOrNull()?.roundToLong()
        if (text.endsWith("s")) return text.dropLast(1).toDoubleOrNull()?.let { (it * 1000).roundToLong() }
        val parts = text.split(':')
        if (parts.size !in 1..3) return null
        var seconds = 0.0
        for (part in parts) seconds = seconds * 60 + (part.toDoubleOrNull() ?: return null)
        return (seconds * 1000).roundToLong()
    }

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
        var lineStart: Long? = null
        var wordStart: Long? = null
        var lineAgent: String? = null
        val text = StringBuilder()
        val wordText = StringBuilder()
        var words = mutableListOf<TimedWord>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "agent" && parser.namespace == metadataNamespace) {
                        parser.getAttributeValue("http://www.w3.org/XML/1998/namespace", "id")?.let {
                            agentTypes[it] = parser.getAttributeValue(null, "type").orEmpty()
                        }
                    }
                    if (parser.name == "p") {
                        lineDepth = parser.depth
                        lineStart = timeMs(parser.getAttributeValue(null, "begin"))
                        lineAgent = parser.getAttributeValue(metadataNamespace, "agent")?.takeIf { it.isNotBlank() }
                        text.clear()
                        words = mutableListOf()
                    }
                    if (lineDepth >= 0 && skipDepth < 0) {
                        val role = (0 until parser.attributeCount).firstOrNull { parser.getAttributeName(it) == "role" }
                            ?.let { parser.getAttributeValue(it) }
                        if (role == "x-bg" || role == "x-translation") skipDepth = parser.depth
                        else if (parser.name == "span") {
                            if (lineAgent == null) {
                                lineAgent = parser.getAttributeValue(metadataNamespace, "agent")?.takeIf { it.isNotBlank() }
                            }
                            if (wordDepth < 0) {
                                timeMs(parser.getAttributeValue(null, "begin"))?.let {
                                    wordDepth = parser.depth
                                    wordStart = it
                                    wordText.clear()
                                }
                            }
                        } else if (parser.name == "br") text.append(' ')
                    }
                }
                XmlPullParser.TEXT -> if (lineDepth >= 0 && skipDepth < 0) {
                    text.append(parser.text)
                    if (wordDepth >= 0) wordText.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    if (parser.depth == wordDepth) {
                        normalize(wordText.toString()).takeIf { it.isNotEmpty() }?.let {
                            words += TimedWord(wordStart!!, it)
                        }
                        wordDepth = -1
                    }
                    if (parser.depth == skipDepth) skipDepth = -1
                    if (parser.depth == lineDepth) {
                        val line = normalize(text.toString())
                        val start = lineStart ?: words.firstOrNull()?.startMs
                        val paragraphStart = lineStart
                        if (paragraphStart != null && words.isNotEmpty() && words.all { it.startMs < paragraphStart }) {
                            words = words.map { it.copy(startMs = it.startMs + paragraphStart) }.toMutableList()
                        }
                        if (line.isNotEmpty()) lines += LyricLine(start ?: 0L, line, start != null, words, voice(lineAgent))
                        lineDepth = -1
                    }
                }
            }
            parser.next()
        }
        lines.sortedBy { it.startMs }
    }.getOrDefault(emptyList())
}
