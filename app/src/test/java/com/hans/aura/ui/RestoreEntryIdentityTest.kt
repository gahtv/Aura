package com.hans.aura.ui

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTestConfig
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.hans.aura.AuraApp
import com.hans.aura.MainActivity
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
import com.hans.aura.shouldDispatchLaunchIntent
import com.hans.aura.ui.theme.AuraTheme
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regression tests for the two Devin findings on the navigation-3 back-stack
 * restore PR:
 *
 *  1. the signed-in subtree must be keyed by a recreation-stable session
 *     identity, not by an in-memory counter, or a profile switch followed by
 *     process death / Activity recreation opens Home instead of the last
 *     screen;
 *  2. a recreated Activity must not replay its original launch intent (an
 *     ACTION_VIEW album deep link would replace the restored stack with
 *     Home + album), while a fresh launch and onNewIntent still must.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class RestoreEntryIdentityTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>(
        ComposeUiTestConfig(inputMode = InputMode.Keyboard)
    )

    private val app = ApplicationProvider.getApplicationContext<AuraApp>()

    @After
    fun tearDown() {
        MusicSources.reset()
        app.jellyfin.auth.clear()
        LibraryCache.clear()
        AppIntents.request.value = null
        DebugNav.request.value = null
    }

    // ---------------------------------------------------------------------
    // Fix A: restore identity after a profile switch.
    // ---------------------------------------------------------------------

    /**
     * A -> B through Settings, open an album as B, then emulate the system
     * killing the app. The saved stack belongs to B's session identity, so the
     * restore must land back on B's album, and BACK must return to B's Albums
     * tab (the pre-album stack) rather than a freshly created Home.
     */
    @Test
    fun processDeathAfterProfileSwitchRestoresTheNewProfilesStack() {
        setUpTestEnv()
        val auth = app.jellyfin.auth
        auth.save(Session(SERVER, "b", "Profile B", "token", "device"))
        auth.save(Session(SERVER, "a", "Profile A", "token", "device"))
        installFakeSource()

        val restoration = StateRestorationTester(compose)
        restoration.setContent { AuraTheme { AppRoot() } }
        waitForText("Album album-a")

        switchToProfile("Profile B")
        waitForText("Album album-a profile b")

        // Pre-album stack is the Albums library, not Home, so BACK afterwards
        // distinguishes a real restore from a fresh Home.
        railTo("Library", "Album album-a profile b")
        openAlbum("album-a")
        waitForText("Track 1")

        restoration.emulateSavedInstanceStateRestore()

        waitForText("Track 1")
        // Album detail is the top destination: its content is up, the library's
        // sort pills are not.
        compose.onNodeWithText("Track 1").assertExists()
        assertTrue(
            "album detail must hide the Albums library behind it",
            compose.onAllNodesWithText("Date added").fetchSemanticsNodes().isEmpty()
        )

        goBack()
        waitForText("Date added")
        compose.onNodeWithText("Date added").assertExists()
        assertTrue(
            "BACK after restore must return to the Albums tab, not Home",
            compose.onAllNodesWithText("Recently added").fetchSemanticsNodes().isEmpty()
        )
    }

    /**
     * A profile switch still starts a fresh stack on Home: the previous
     * profile's album detail must not survive (its saveable state is disposed
     * together with the old session key).
     */
    @Test
    fun switchingProfilesStartsOnHomeAndDropsTheOldStack() {
        setUpTestEnv()
        val auth = app.jellyfin.auth
        auth.save(Session(SERVER, "b", "Profile B", "token", "device"))
        auth.save(Session(SERVER, "a", "Profile A", "token", "device"))
        installFakeSource()

        compose.setContent { AuraTheme { AppRoot() } }
        waitForText("Album album-a")

        openAlbum("album-a")
        waitForText("Track 1")

        switchToProfile("Profile B")
        waitForText("Album album-a profile b")

        assertTrue(
            "the previous profile's album detail must not survive the switch",
            compose.onAllNodesWithText("Track 1").fetchSemanticsNodes().isEmpty()
        )
    }

    // ---------------------------------------------------------------------
    // Fix B: launch intents only on a fresh launch.
    // ---------------------------------------------------------------------

    /**
     * A fresh Activity applies the ACTION_VIEW launch intent; recreating it
     * (rotation / process-death restore, same intent attached) must not, or the
     * deep link would overwrite the restored nav stack. Delivering a new intent
     * to the running Activity (`am start`) must still apply.
     */
    @Test
    fun recreatedActivityDoesNotReplayTheLaunchDeepLink() {
        val controller = Robolectric
            .buildActivity(MainActivity::class.java, deepLinkIntent("Album", "album-a"))
            .setup()
        try {
            assertEquals(
                AppIntentRequest.OpenItem("Album", "album-a"),
                AppIntents.request.value
            )

            AppIntents.request.value = null
            controller.recreate()
            assertNull(
                "a restored Activity must not re-post its launch intent",
                AppIntents.request.value
            )

            // onNewIntent (am start into a running app) is unchanged.
            controller.newIntent(deepLinkIntent("Album", "album-b"))
            assertEquals(
                AppIntentRequest.OpenItem("Album", "album-b"),
                AppIntents.request.value
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun launchIntentPolicyAllowsOnlyAFreshLaunch() {
        assertTrue(shouldDispatchLaunchIntent(null))
        assertFalse(shouldDispatchLaunchIntent(Bundle()))
    }

    private fun deepLinkIntent(type: String, id: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse("aura://item/$type/$id"))
            .setClass(app, MainActivity::class.java)

    // ---------------------------------------------------------------------
    // App harness + fake source (copied from Navigation3ScopingTest so this
    // file exercises the real AppRoot wiring without touching that test).
    // ---------------------------------------------------------------------

    private class FakeCounts {
        val homeShelfLoads = AtomicInteger()
        val libraryAlbumLoads = AtomicInteger()
        val trackLoads: MutableMap<String, AtomicInteger> = ConcurrentHashMap()
        fun tracks(albumId: String) = trackLoads.computeIfAbsent(albumId) { AtomicInteger() }
    }

    /** Installs a deterministic source and returns what it was asked to do. */
    private fun installFakeSource(): FakeCounts {
        val counts = FakeCounts()
        val base = requireNotNull(MusicSources.current)
        val albumName: (String) -> String = { id ->
            val uid = app.jellyfin.auth.session?.userId.orEmpty()
            if (uid == "b") "Album $id profile b" else "Album $id"
        }
        fun album(id: String) = JellyfinItem(
            id = id, name = albumName(id), type = "MusicAlbum",
            albumArtist = "Artist X", albumArtists = listOf(NameId("artist-x", "Artist X"))
        )
        fun track(albumId: String, index: Int) = JellyfinItem(
            id = "$albumId:track-$index", name = "Track $index", type = "Audio",
            album = "Album $albumId", albumId = albumId,
            artistItems = listOf(NameId("artist-x", "Artist X"))
        )
        MusicSources.install(object : MusicSource by base {
            override val id = "restore-entry-identity-test"
            // No play history and nothing cached, so every load is observable
            // and a fresh ViewModel always refetches.
            override val hasPlayHistory = false
            override suspend fun cached(prefix: String): List<JellyfinItem>? = null
            override fun cache(prefix: String, items: List<JellyfinItem>) {}
            override fun cachedPage(key: String) = null
            override fun storePage(key: String, items: List<JellyfinItem>, total: Int, requiresRefresh: Boolean) {}

            override suspend fun browse(query: LibraryFilter.ItemsQuery, cachePrefix: String?): ItemsResponse {
                return when {
                    query.includeItemTypes == "MusicAlbum" && query.sortBy == "DateCreated" -> {
                        counts.homeShelfLoads.incrementAndGet()
                        ItemsResponse(items = listOf(album("album-a"), album("album-b")))
                    }
                    query.includeItemTypes == "MusicAlbum" -> {
                        counts.libraryAlbumLoads.incrementAndGet()
                        ItemsResponse(items = listOf(album("album-a"), album("album-b")))
                    }
                    query.includeItemTypes == "Audio" -> {
                        val albumId = query.parentId.orEmpty()
                        counts.tracks(albumId).incrementAndGet()
                        ItemsResponse(items = listOf(track(albumId, 1), track(albumId, 2)))
                    }
                    else -> ItemsResponse()
                }
            }

            override suspend fun item(itemId: String) = when (itemId) {
                "album-a" -> album("album-a")
                "album-b" -> album("album-b")
                else -> null
            }

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

    private fun switchToProfile(name: String) {
        railTo("Settings", "Signed in as")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(name))
        compose.onNodeWithText(name).performSemanticsAction(SemanticsActions.OnClick) { it() }
    }

    private fun openAlbum(id: String) {
        compose.onAllNodes(hasText("Album $id", substring = true) and hasClickAction())[0].performClick()
        waitForText("Track 1")
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

    companion object {
        private const val SERVER = "http://localhost"
    }
}
