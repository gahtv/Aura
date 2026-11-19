package com.hans.aura.ui.collection

import com.hans.aura.data.JellyfinItem

/** One row of a collection's tracklist: a disc separator or a track. */
internal sealed interface TracklistRow {
    data class Disc(val number: Int) : TracklistRow

    /** [position] indexes the played list; [number] is the label the row shows. */
    data class Track(val position: Int, val number: Int, val track: JellyfinItem) : TracklistRow
}

/** True when [this] album's tracks span several disc numbers. */
internal fun List<JellyfinItem>.spansSeveralDiscs(): Boolean =
    mapNotNullTo(HashSet()) { it.discNumber }.size > 1

/**
 * With [multiDisc] (decided on the whole album, so a filtered list keeps it), each
 * disc gets a separator and its tagged track numbers (disc 2 restarts at 1); a track
 * without a number falls back to its place on the disc. Otherwise (single-disc
 * albums, albums with no disc tags, playlists) rows keep positional numbers.
 */
internal fun tracklistRows(tracks: List<JellyfinItem>, multiDisc: Boolean): List<TracklistRow> {
    if (!multiDisc) return tracks.mapIndexed { i, track -> TracklistRow.Track(i, i + 1, track) }
    val rows = ArrayList<TracklistRow>(tracks.size + 4)
    var disc: Int? = null
    var onDisc = 0
    tracks.forEachIndexed { i, track ->
        val number = track.discNumber
        if (number != null && number != disc) {
            rows += TracklistRow.Disc(number)
            disc = number
            onDisc = 0
        }
        onDisc++
        rows += TracklistRow.Track(i, track.trackNumber ?: onDisc, track)
    }
    return rows
}
