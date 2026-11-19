package com.hans.aura.data

sealed interface LyricsResult {
    val lines: List<LyricLine> get() = emptyList()

    data class Lyrics(override val lines: List<LyricLine>) : LyricsResult
    data object Instrumental : LyricsResult
    data object NotFound : LyricsResult
}
