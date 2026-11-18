package com.hans.aura.ui.common

/**
 * "1 song" / "2 songs": a count with its noun inflected for English.
 *
 * Screens used to build these strings by hand (`"$n songs"`), so a one-track
 * album read "1 songs" and a single-album genre read "1 albums". Exactly one
 * takes the singular; zero and everything above take the plural, which is what
 * English does ("0 songs", "2 songs").
 */
fun countLabel(count: Int, singular: String, plural: String = singular + "s"): String =
    "$count " + if (count == 1) singular else plural
