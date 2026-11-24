package com.hans.aura.ui

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.hans.aura.ui.nav.CollectionKind
import com.hans.aura.ui.nav.NavState
import com.hans.aura.ui.nav.Screen
import com.hans.aura.ui.nav.StackEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Process-death restore into [NavState]: the restored stack must resume its
 * Library category and let the top entry claim content focus, the same way a
 * push does.
 */
@RunWith(RobolectricTestRunner::class)
class NavigationRestoreStateTest {

    /** A restored stack, the shape `rememberNavBackStack` hands to NavState. */
    private fun restored(vararg screens: Screen): NavState =
        NavState(
            NavBackStack<NavKey>(
                *screens.mapIndexed { index, screen ->
                    StackEntry(4L + index, screen)
                }.toTypedArray()
            )
        )

    private val collection = Screen.Collection(
        id = "album-a",
        name = "Album A",
        kind = CollectionKind.ALBUM
    )

    // ---------------------------------------------------------------------
    // Restored Library category
    // ---------------------------------------------------------------------

    @Test
    fun restoredTracksStackResumesTracksAndContinuesIdsAboveTheRestoredMaximum() {
        val nav = restored(Screen.Home, Screen.Tracks)
        assertEquals(Screen.Tracks, nav.lastLibraryTab)

        nav.push(Screen.Queue)
        assertEquals(6L, nav.topEntry.id)
    }

    @Test
    fun restoredStackResumesEveryLibraryCategory() {
        listOf(Screen.Albums, Screen.Artists, Screen.Tracks, Screen.Playlists).forEach { category ->
            val nav = restored(Screen.Home, category)
            assertEquals(category, nav.lastLibraryTab)
        }
    }

    @Test
    fun restoredStackResumesTheMostRecentLibraryCategory() {
        val nav = restored(Screen.Home, Screen.Tracks, collection)
        assertEquals(Screen.Tracks, nav.lastLibraryTab)
    }

    @Test
    fun restoredStackWithoutALibraryDestinationKeepsAlbums() {
        val nav = restored(Screen.Home, collection)
        assertEquals(Screen.Albums, nav.lastLibraryTab)
    }

    @Test
    fun switchingLibraryCategoryUpdatesTheResumedCategory() {
        val nav = restored(Screen.Home, Screen.Artists)
        nav.switchLibraryCategory(Screen.Tracks)
        assertEquals(Screen.Tracks, nav.lastLibraryTab)
        assertEquals(Screen.Tracks, nav.top)
    }

    // ---------------------------------------------------------------------
    // Restored focus
    // ---------------------------------------------------------------------

    @Test
    fun restoredMultiEntryStackRequestsFocusForItsTopEntry() {
        val nav = restored(Screen.Home, collection)
        assertTrue(nav.pendingFocus)
        assertEquals(0, nav.focusTick)
    }

    @Test
    fun restoredSingleEntryHomeStackKeepsColdStartFocusBehavior() {
        val nav = restored(Screen.Home)
        assertFalse(nav.pendingFocus)
        assertEquals(0, nav.focusTick)
    }

    @Test
    fun pushStillRequestsFocus() {
        val nav = restored(Screen.Home)
        assertFalse(nav.pendingFocus)

        nav.push(Screen.Queue)
        assertTrue(nav.pendingFocus)
    }
}
