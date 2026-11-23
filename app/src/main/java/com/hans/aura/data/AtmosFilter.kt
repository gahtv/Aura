package com.hans.aura.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hans.aura.model.Album
import com.hans.aura.model.Artist
import com.hans.aura.model.Genre
import com.hans.aura.model.MediaItem
import com.hans.aura.model.Playlist
import com.hans.aura.model.Track

/**
 * "Only Atmos" as an app-wide filter.
 *
 * Two different switches, on purpose (user decision 2026-11-15):
 *  - the Settings preference `Prefs.atmosOnly` — persisted, applied to every
 *    browse list. This is what a user wants when ONE library contains both the
 *    standard and the Atmos copy of a release and they never want to see the
 *    standard one;
 *  - the per-view icon pill (session-scoped, ui/library + ui/collection), for
 *    the case where the two libraries are separate and the user just wants the
 *    Atmos ones for a moment.
 *
 * [on] mirrors the preference as Compose state so shelves re-filter the moment
 * the switch is flipped, without a relaunch. [catalog] is filled by
 * [AtmosCatalog.catalog] (same single scan the ATMOS badges use) — never
 * triggers its own walk.
 *
 * Filtering NEVER touches the queue, Now Playing or playlist contents: dropping
 * tracks out of a queue mid-play is data loss from the user's point of view.
 */
object AtmosFilter {

    /** Preference mirror; written through [setEnabled] so Compose sees it. */
    var on: Boolean by mutableStateOf(false)
        private set

    private var catalog: AtmosCatalog.Catalog = AtmosCatalog.Catalog()

    /** Call once from AuraApp.onCreate, after Prefs.init. */
    fun load() {
        on = Prefs.atmosOnly
    }

    fun setEnabled(value: Boolean) {
        on = value
        Prefs.atmosOnly = value
        android.util.Log.d("AURA_ATMOS", "pref=${if (value) "on" else "off"}")
    }

    /** Called by AtmosCatalog on every resolved scan. */
    fun adopt(c: AtmosCatalog.Catalog) {
        catalog = c
    }

    fun albumIds(): Set<String> = catalog.albumIds
    fun trackIds(): Set<String> = catalog.trackIds

    /** True when [item] should be shown under the current preference. */
    fun keep(item: MediaItem): Boolean {
        if (!on) return true
        return when (item) {
            is Album -> item.id in catalog.albumIds
            is Track -> item.id in catalog.trackIds
            // Artists/playlists/genres are not filtered: an artist with both copies
            // must stay reachable, and filtering a playlist would hide its contents.
            is Artist, is Playlist, is Genre -> true
        }
    }

    fun albums(list: List<JellyfinItem>): List<JellyfinItem> =
        if (!on) list else list.filter { it.id in catalog.albumIds }

    fun tracks(list: List<JellyfinItem>): List<JellyfinItem> =
        if (!on) list else list.filter { it.id in catalog.trackIds }
}
