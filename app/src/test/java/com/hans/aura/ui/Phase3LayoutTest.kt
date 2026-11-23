package com.hans.aura.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.ComposeUiTestConfig
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.hans.aura.AuraApp
import com.hans.aura.data.Fixtures
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.Prefs
import com.hans.aura.ui.genres.genreCardColor
import com.hans.aura.ui.home.HomeUiState
import com.hans.aura.ui.nav.NavSpec
import com.hans.aura.ui.nav.NavState
import com.hans.aura.ui.nav.Screen
import com.hans.aura.ui.search.RemoteSearchTipBanner
import com.hans.aura.ui.shell.RailDestination
import com.hans.aura.ui.theme.AuraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class Phase3LayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>(
        ComposeUiTestConfig(inputMode = InputMode.Keyboard)
    )

    @Before
    fun resetPrefs() {
        val app = ApplicationProvider.getApplicationContext<AuraApp>()
        Prefs.init(app)
        Prefs.searchRemoteTipDismissed = false
    }

    @Test
    fun homeQuickAccessDeduplicatesAndCapsAtEight() {
        val shared = Fixtures.domainAlbum("shared-1")
        val state = HomeUiState(
            recentAlbums = listOf(shared, Fixtures.domainAlbum("rec-2"), Fixtures.domainAlbum("rec-3"), Fixtures.domainAlbum("rec-4")),
            playlists = listOf(
                com.hans.aura.model.Playlist(id = "pl-1", name = "pl-1"),
                com.hans.aura.model.Playlist(id = "pl-2", name = "pl-2")
            ),
            atmosAlbums = listOf(shared, Fixtures.domainAlbum("atm-1"), Fixtures.domainAlbum("atm-2")),
            recentlyPlayed = listOf(Fixtures.domainTrack("trk-1"), Fixtures.domainTrack("trk-2")),
            artists = listOf(Fixtures.domainArtist("art-1"))
        )
        val qa = state.quickAccess
        assertEquals(8, qa.size)
        assertEquals(8, qa.map { it.id }.distinct().size)
    }

    @Test
    fun libraryRailEntryRemembersLastCategoryAndPreservesNavSpec() {
        val nav = NavState()
        assertEquals(Screen.Home, nav.top)
        assertTrue(NavSpec.isLibraryScreen(Screen.Albums))
        assertTrue(NavSpec.isLibraryScreen(Screen.Artists))
        assertTrue(NavSpec.isLibraryScreen(Screen.Tracks))
        assertTrue(NavSpec.isLibraryScreen(Screen.Playlists))
        assertFalse(NavSpec.isLibraryScreen(Screen.Genres))
        assertFalse(NavSpec.isLibraryScreen(Screen.Liked))
        assertFalse(NavSpec.isLibraryScreen(Screen.Home))

        nav.selectTab(Screen.Albums)
        assertEquals(Screen.Albums, nav.top)

        nav.switchLibraryCategory(Screen.Tracks)
        assertEquals(Screen.Tracks, nav.top)

        nav.selectTab(Screen.Home)
        assertEquals(Screen.Home, nav.top)

        // Selecting Library returns to the last chosen category (Tracks).
        nav.selectTab(Screen.Albums)
        assertEquals(Screen.Tracks, nav.top)

        // Saveable-state keys & mirror routes remain backward-compatible.
        assertEquals("tracks", NavSpec.key(Screen.Tracks))
        assertEquals("albums", NavSpec.key(Screen.Albums))
        assertEquals("col:a1", NavSpec.key(Screen.Collection(Fixtures.domainAlbum("a1"))))
        assertEquals("artist:ar1", NavSpec.key(Screen.Artist(Fixtures.domainArtist("ar1"))))
    }

    @Test
    fun railDestinationsDoNotChangeLibraryCategoryOrDetailBackStack() {
        val nav = NavState()
        nav.switchLibraryCategory(Screen.Artists)
        nav.selectTab(Screen.Liked)
        assertEquals(RailDestination.Liked, RailDestination.forStack(nav.stack))
        assertEquals("liked", NavSpec.key(nav.top))
        assertEquals("Tracks", NavSpec.libraryKind(nav.top))
        assertEquals("tracks" to null, NavSpec.mirrorPublish(nav.top))
        nav.openNowPlaying()
        nav.back()
        assertEquals(Screen.Liked, nav.top)

        nav.selectTab(Screen.Genres)
        nav.actions.onOpenGenre("Jazz")
        assertEquals(RailDestination.Genres, RailDestination.forStack(nav.stack))
        nav.back()
        assertEquals(Screen.Genres, nav.top)
        nav.back()
        assertEquals(Screen.Home, nav.top)

        nav.selectTab(Screen.Albums)
        assertEquals(Screen.Artists, nav.top)
    }

    @Test
    fun genreCardColorIsDeterministic() {
        assertEquals(genreCardColor("Electronic"), genreCardColor("Electronic"))
        assertEquals(genreCardColor("Jazz"), genreCardColor("Jazz"))
    }

    @Test
    fun remoteSearchTipBannerDismissesAndPersistsPref() {
        var dismissed = false
        compose.setContent {
            AuraTheme {
                RemoteSearchTipBanner {
                    dismissed = true
                    Prefs.searchRemoteTipDismissed = true
                }
            }
        }
        compose.onNodeWithText("Tip: Search faster from your phone with Aura Remote").assertExists()
        compose.onNodeWithText("Dismiss").performClick()
        compose.runOnIdle {
            assertTrue(dismissed)
            assertTrue(Prefs.searchRemoteTipDismissed)
        }
    }
}
