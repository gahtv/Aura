package com.hans.aura.data

data class LyricVoice(val id: String, val kind: Kind) {
    enum class Kind { PRIMARY, SECONDARY, GROUP }
}

internal object LrcVoices {
    private const val token = "(?:v[1-9][0-9]*|[fmd]|bg)"
    private val prefix = Regex("^($token(?:\\s*[,+&/]\\s*$token)*)[:：]\\s*", RegexOption.IGNORE_CASE)
    private val separator = Regex("\\s*[,+&/]\\s*")

    fun split(text: String): Pair<String, LyricVoice?> {
        val match = prefix.find(text) ?: return text to null
        val ids = match.groupValues[1].lowercase().split(separator)
        val id = ids.singleOrNull()
        val kind = when {
            id == null || id in listOf("d", "bg", "v1000") -> LyricVoice.Kind.GROUP
            id == "m" || (id.startsWith('v') && id.drop(1).last().digitToInt() % 2 == 0) -> LyricVoice.Kind.SECONDARY
            else -> LyricVoice.Kind.PRIMARY
        }
        return text.substring(match.range.last + 1) to LyricVoice(ids.joinToString(","), kind)
    }
}
