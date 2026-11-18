package com.hans.aura.data

data class LyricLine(
    val startMs: Long,
    val text: String,
    val isSynced: Boolean
)

/**
 * Fetches and normalizes lyrics from Jellyfin's /Audio/{id}/lyrics endpoint.
 * Handles both shapes seen in the wild:
 *  - synced JSON lines with Start (ticks, 100ns) from the LrcLib plugin
 *  - a plain LyricText blob (optionally LRC-formatted with [mm:ss.xx] stamps)
 */
object LyricsStore {

    suspend fun fetch(api: JellyfinApi, itemId: String): List<LyricLine> {
        val resp = runCatching { api.getLyrics(itemId) }.getOrNull()
            ?: return emptyList()

        // 1) Structured synced/plain lines
        val dtoLines = resp.lyrics
        if (!dtoLines.isNullOrEmpty()) {
            // If any line has a non-zero start (or multiple lines), treat ticks as sync.
            val hasTicks = dtoLines.size > 1 && dtoLines.any { it.start > 0 }
            return dtoLines.map {
                LyricLine(
                    startMs = if (hasTicks) it.start / 10_000L else 0L,
                    text = it.text.trim(),
                    isSynced = hasTicks
                )
            }
        }

        // 2) Raw text, possibly LRC-formatted
        val text = resp.lyricText?.takeIf { it.isNotBlank() } ?: return emptyList()
        return parseLrcText(text)
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
            val parsed = parseLrcText(fromSource)
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
        val plain = mutableListOf<String>()

        for (rawLine in raw.lines()) {
            val stamps = stampRegex.findAll(rawLine).toList()
            val text = rawLine.replace(stampRegex, "").trim()
            if (stamps.isEmpty()) {
                if (text.isNotEmpty()) plain.add(text)
                continue
            }
            for (m in stamps) {
                val minutes = m.groupValues[1].toLong()
                val seconds = m.groupValues[2].replace(':', '.').toDoubleOrNull() ?: 0.0
                val ms = minutes * 60_000 + (seconds * 1000).toLong()
                synced.add(LyricLine(startMs = ms, text = text, isSynced = true))
            }
        }

        return when {
            synced.isNotEmpty() -> synced.sortedBy { it.startMs }
            plain.isNotEmpty() -> plain.map { LyricLine(0L, it, isSynced = false) }
            else -> emptyList()
        }
    }
}
