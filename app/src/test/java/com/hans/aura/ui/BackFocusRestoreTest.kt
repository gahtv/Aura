package com.hans.aura.ui

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.test.ComposeUiTestConfig
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import com.hans.aura.AuraApp
import com.hans.aura.data.AtmosFilter
import com.hans.aura.data.ItemsResponse
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.LibraryCache
import com.hans.aura.data.LibraryFilter
import com.hans.aura.data.MusicSource
import com.hans.aura.data.MusicSources
import com.hans.aura.data.NameId
import com.hans.aura.data.Prefs
import com.hans.aura.data.Session
import com.hans.aura.playback.PlaybackService
import com.hans.aura.ui.theme.AuraTheme
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regression for the TV back-focus gap left unverified by PR #93 (Navigation 3
 * migration): open an album from a scrolled Library grid, press system BACK,
 * and the SAME album card must hold focus again with the grid still scrolled
 * to it - not reset to the top, not reloaded.
 *
 * Everything else in the migration tests checks content reload counts; this
 * checks the actual D-pad cursor and scroll position, the riskiest part of the
 * move to NavDisplay. The restore path exercised here is:
 *
 *  1. the Library tab root keeps its scroll (rememberLazyGridState) and the
 *     last-focused card id (rememberSaveable) under its session-scoped
 *     SaveableStateProvider while the pushed Collection entry covers it;
 *  2. BACK pops the entry, NavState bumps focusTick, and LibraryScreen's
 *     RestoreFocusOnBack hands focus to that card's FocusKeeper requester
 *     (falling back to the grid container when the id is unknown).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class BackFocusRestoreTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>(
        ComposeUiTestConfig(inputMode = InputMode.Keyboard)
    )

    @After
    fun tearDown() {
        MusicSources.reset()
        app.jellyfin.auth.clear()
        LibraryCache.clear()
    }

    // ------------------------------------------------------------------
    // Fake source: 25 albums so the 5-column grid has to scroll, plus the
    // track list the detail screen loads. No network, no playback.
    // ------------------------------------------------------------------

    private class FakeCounts {
        val albumLoads = AtomicInteger()
        val trackLoads: MutableMap<String, AtomicInteger> = ConcurrentHashMap()
        fun tracks(albumId: String) = trackLoads.computeIfAbsent(albumId) { AtomicInteger() }
    }

    private val app = ApplicationProvider.getApplicationContext<AuraApp>()

    private fun installFakeSource(): FakeCounts {
        val counts = FakeCounts()
        val base = requireNotNull(MusicSources.current)
        fun album(id: String) = JellyfinItem(
            id = id, name = "Album $id", type = "MusicAlbum",
            albumArtist = "Artist X", albumArtists = listOf(NameId("artist-x", "Artist X"))
        )
        fun track(albumId: String, index: Int) = JellyfinItem(
            id = "$albumId:track-$index", name = "Track $index", type = "Audio",
            album = "Album $albumId", albumId = albumId,
            artistItems = listOf(NameId("artist-x", "Artist X"))
        )
        val albums = ALBUM_IDS.map { album(it) }
        MusicSources.install(object : MusicSource by base {
            override val id = "back-focus-test"
            // Nothing cached and no play history: every load is observable and
            // a restored screen must come from retained state, not a refetch.
            override val hasPlayHistory = false
            override suspend fun cached(prefix: String): List<JellyfinItem>? = null
            override fun cache(prefix: String, items: List<JellyfinItem>) {}
            override fun cachedPage(key: String) = null
            override fun storePage(key: String, items: List<JellyfinItem>, total: Int, requiresRefresh: Boolean) {}

            override suspend fun browse(query: LibraryFilter.ItemsQuery, cachePrefix: String?): ItemsResponse {
                return when {
                    query.includeItemTypes == "MusicAlbum" -> {
                        counts.albumLoads.incrementAndGet()
                        ItemsResponse(items = albums)
                    }
                    query.includeItemTypes == "Audio" -> {
                        val albumId = query.parentId.orEmpty()
                        counts.tracks(albumId).incrementAndGet()
                        ItemsResponse(items = listOf(track(albumId, 1), track(albumId, 2)))
                    }
                    else -> ItemsResponse()
                }
            }

            override suspend fun item(itemId: String) =
                if (itemId in ALBUM_IDS) album(itemId) else null

            override suspend fun artists(sortBy: String, sortOrder: String, limit: Int, startIndex: Int) =
                ItemsResponse()

            override suspend fun atmosAlbumIds(): Set<String> = emptySet()
        })
        return counts
    }

    private fun setUpTestEnv() {
        AtmosFilter.setEnabled(false)
        shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
        Prefs.init(app)
        LibraryCache.clear()
    }

    private fun renderApp() {
        compose.setContent { AuraTheme { AppRoot() } }
        waitForText("Album a01")
    }

    private fun goBack() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    }

    private fun railTo(destination: String, waitFor: String) {
        compose.onNodeWithContentDescription(destination).performClick()
        waitForText(waitFor)
    }

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ------------------------------------------------------------------
    // The regression
    // ------------------------------------------------------------------

    @Test
    fun backFromOpenedAlbumRestoresFocusToSameCardWithGridStillScrolled() {
        setUpTestEnv()
        app.jellyfin.auth.installSession(Session("http://localhost", "nav-user", "Test", "token", "device"))
        val counts = installFakeSource()
        renderApp()

        // Library tab (Albums grid): exactly one album-list load.
        railTo("Library", "Title")
        waitForText("Album a01")
        val loadsAfterOpen = counts.albumLoads.get()

        // Scroll the grid deep enough that "Album a21" (row 5 of 5 columns) is
        // the top row on screen and "Album a01" is far out of the viewport.
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(20)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Album a21").fetchSemanticsNodes().isNotEmpty()
        }

        // A TV user focuses the card before selecting it: give the card focus
        // the same way its FocusKeeper records it, then press it.
        compose.onNodeWithText("Album a21").requestFocus()
        compose.waitForIdle()
        compose.onNodeWithText("Album a21").assertIsFocused()
        val rowsBefore = visibleAlbumRows()
        assertTrue("expected the grid to be scrolled away from the top", rowsBefore.isNotEmpty())
        assertTrue("expected Album a21 on screen before opening it: $rowsBefore", "Album a21" in rowsBefore)
        assertTrue("expected Album a01 off-screen before opening (rows=$rowsBefore)", !isDisplayedCard("Album a01"))

        compose.onNodeWithText("Album a21").performClick()
        waitForText("Track 1")
        compose.onNodeWithText("Shuffle").assertExists()

        // System BACK: the dispatcher path (MainActivity backs the nav host).
        goBack()

        // The Library screen returned - the detail screen is gone.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Title").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("Shuffle").fetchSemanticsNodes().isEmpty()
        }

        // Focus is back on the SAME card, and the grid still shows it - i.e.
        // the scroll was restored along with the focus, not reset to the top.
        compose.onNodeWithText("Album a21").assertIsFocused()
        compose.onNodeWithText("Album a21").assertIsDisplayed()
        val rowsAfter = visibleAlbumRows()
        assertTrue("Album a01 must not be on screen after BACK (grid reset to top?)", !isDisplayedCard("Album a01"))
        assertTrue(
            "Expected the same visible rows after BACK; before=$rowsBefore after=$rowsAfter",
            rowsAfter == rowsBefore
        )

        // The restored tab root kept its state: the grid was not refetched.
        assertEquals(loadsAfterOpen, counts.albumLoads.get())
        // The detail entry's ViewModel was popped: no track refetch on BACK.
        assertEquals(1, counts.tracks("a21").get())
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun isDisplayedCard(name: String): Boolean {
        val nodes = compose.onAllNodesWithText(name)
        return nodes.fetchSemanticsNodes().isNotEmpty() && nodes[0].isDisplayed()
    }

    /** Album cards that exist AND are on screen right now, in list order. */
    private fun visibleAlbumRows(): List<String> =
        ALBUM_IDS.map { "Album $it" }.filter { name -> isDisplayedCard(name) }

    companion object {
        private val ALBUM_IDS = (1..25).map { "a%02d".format(it) }
    }
}
