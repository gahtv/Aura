package com.hans.aura.ui.collection

import com.hans.aura.data.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

class TracklistRowsTest {

    private fun track(id: String, disc: Int?, number: Int?) =
        Fixtures.domainTrack(id).copy(discNumber = disc, trackNumber = number)

    /** Rows as compact labels: "Disc 2" for separators, "<label>:<id>@<position>" for tracks. */
    private fun labels(rows: List<TracklistRow>) = rows.map { row ->
        when (row) {
            is TracklistRow.Disc -> "Disc ${row.number}"
            is TracklistRow.Track -> "${row.number}:${row.track.id}@${row.position}"
        }
    }

    private val twoDiscs = listOf(
        track("a", 1, 1), track("b", 1, 2),
        track("c", 2, 1), track("d", 2, 2)
    )

    @Test
    fun `a two-disc album gets a separator per disc and restarts numbering`() {
        assertEquals(
            listOf("Disc 1", "1:a@0", "2:b@1", "Disc 2", "1:c@2", "2:d@3"),
            labels(tracklistRows(twoDiscs, twoDiscs.spansSeveralDiscs()))
        )
    }

    @Test
    fun `a playlist keeps positional numbers even across discs`() {
        assertEquals(
            listOf("1:a@0", "2:b@1", "3:c@2", "4:d@3"),
            labels(tracklistRows(twoDiscs, multiDisc = false))
        )
    }

    @Test
    fun `a single-disc album has no separator`() {
        val tracks = listOf(track("a", 1, 1), track("b", 1, 2), track("c", 1, 3))
        assertEquals(listOf("1:a@0", "2:b@1", "3:c@2"), labels(tracklistRows(tracks, tracks.spansSeveralDiscs())))
    }

    @Test
    fun `an album without disc tags keeps positional numbers`() {
        val tracks = listOf(track("a", null, 4), track("b", null, null), track("c", null, 9))
        assertEquals(listOf("1:a@0", "2:b@1", "3:c@2"), labels(tracklistRows(tracks, tracks.spansSeveralDiscs())))
    }

    @Test
    fun `untagged tracks sort ahead of disc 1 without a separator`() {
        val tracks = listOf(track("x", null, null), track("a", 1, 1), track("c", 2, 1))
        assertEquals(
            listOf("1:x@0", "Disc 1", "1:a@1", "Disc 2", "1:c@2"),
            labels(tracklistRows(tracks, tracks.spansSeveralDiscs()))
        )
    }

    @Test
    fun `a track without a number falls back to its place on the disc`() {
        val tracks = listOf(track("a", 1, 1), track("c", 2, null), track("d", 2, null))
        assertEquals(
            listOf("Disc 1", "1:a@0", "Disc 2", "1:c@1", "2:d@2"),
            labels(tracklistRows(tracks, tracks.spansSeveralDiscs()))
        )
    }

    @Test
    fun `filtered discs keep their own track numbers`() {
        // "Only Atmos" can drop tracks; the survivors keep the numbers on the sleeve.
        val tracks = listOf(track("b", 1, 2), track("d", 2, 4))
        assertEquals(
            listOf("Disc 1", "2:b@0", "Disc 2", "4:d@1"),
            labels(tracklistRows(tracks, tracks.spansSeveralDiscs()))
        )
    }

    @Test
    fun `a filter that leaves one disc keeps its separator and tagged numbers`() {
        val atmosOnly = twoDiscs.drop(2)
        assertEquals(
            listOf("Disc 2", "1:c@0", "2:d@1"),
            labels(tracklistRows(atmosOnly, twoDiscs.spansSeveralDiscs()))
        )
    }
}
