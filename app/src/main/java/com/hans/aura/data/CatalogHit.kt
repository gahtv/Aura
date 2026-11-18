package com.hans.aura.data

/**
 * One album as it exists in a *catalog* — searchable and importable, but not yet
 * part of the library this build browses.
 *
 * Deliberately not a [JellyfinItem]: nothing in the catalog has a library id, a
 * playable track or a user-data record, so wrapping it in the item shape would
 * invite callers to treat it as browsable. Only the search screen's "From the
 * catalog" section touches this type, and only through the optional
 * [MusicSource.catalogSearch] seam.
 */
data class CatalogHit(
    /** Backend id used to import the album (the catalog's adam id). */
    val id: String,
    val title: String,
    val artist: String,
    val year: Int? = null,
    val hasAtmos: Boolean = false,
    /** Absolute cover URL when the backend hands one out; null otherwise. */
    val artworkUrl: String? = null,
    val trackCount: Int? = null
) {
    /** Line under the title: "Artist" or "Artist · 2019". */
    val subtitle: String
        get() = listOfNotNull(artist.takeIf { it.isNotBlank() }, year?.toString()).joinToString(" \u00B7 ")
}
