package com.hans.aura.ui

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTestConfig
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
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
import com.hans.aura.ui.nav.NavSpec
import com.hans.aura.ui.nav.NavState
import com.hans.aura.ui.nav.Screen
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
 * Navigation 3 migration scoping (issue #82):
 *
 *  - pushed detail entries get their own ViewModelStore, which NavDisplay
 *    clears when the entry is popped (no retained CollectionViewModels);
 *  - the same screen can be pushed twice without a key collision
 *    (entries are distinct by monotonic id);
 *  - rail tab roots keep their ViewModels across tab switches
 *    (sessionOwner + tabStateHolder), and BACK on a root tab goes Home;
 *  - a profile switch clears everything the session owns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class Navigation3ScopingTest {
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

    // ---------------------------------------------------------------------
    // Mechanism: NavDisplay + the two entry decorators AppRoot uses.
    // ---------------------------------------------------------------------

    /**
     * A NavDisplay over NavState with the exact decorator stack AppRoot uses.
     * A counting ViewModel per entry proves: duplicate screens stay distinct,
     * entries keep their ViewModel below the top, pop clears it (onCleared),
     * and clearing the session store clears every live entry ViewModel (what
     * SessionViewModels.reset() does on a profile switch).
     */
    @Test
    fun navDisplayKeepsDuplicateEntriesDistinctAndClearsTheirViewModels() {
        CountingViewModel.created.set(0)
        CountingViewModel.cleared.set(0)
        val store = ViewModelStore()
        val sessionOwner = object : ViewModelStoreOwner {
            override val viewModelStore = store
        }
        val nav = NavState()
        val sessionGeneration = androidx.compose.runtime.mutableIntStateOf(0)
        compose.setContent {
            AuraTheme {
                androidx.compose.runtime.key(sessionGeneration.intValue) {
                    if (sessionGeneration.intValue == 0) {
                        NavDisplay(
                            backStack = nav.entries,
                            onBack = nav::pop,
                            entryDecorators = listOf(
                                rememberSaveableStateHolderNavEntryDecorator(),
                                rememberViewModelStoreNavEntryDecorator(viewModelStoreOwner = sessionOwner)
                            ),
                            entryProvider = { entry ->
                                NavEntry(
                                    key = entry,
                                    contentKey = "${entry.id}:${NavSpec.key(entry.screen)}"
                                ) {
                                    viewModel { CountingViewModel() }
                                    androidx.tv.material3.Text("${entry.id}:${NavSpec.key(entry.screen)}")
                                }
                            }
                        )
                    }
                }
            }
        }
        waitForText("0:home")
        assertEquals(1, CountingViewModel.created.get())

        // Album A -> Artist X -> Album A: the two Collection entries must be
        // distinct (no key collision) even though NavSpec.key is identical,
        // and each composes its own ViewModel instance.
        compose.runOnIdle { nav.push(Screen.Collection(FixturesAlbumA)) }
        waitForText("1:col:a")
        assertEquals(2, CountingViewModel.created.get())

        compose.runOnIdle { nav.push(Screen.Artist(FixturesArtistX)) }
        waitForText("2:artist:x")
        assertEquals(3, CountingViewModel.created.get())

        compose.runOnIdle { nav.push(Screen.Collection(FixturesAlbumA)) }
        waitForText("3:col:a")
        assertEquals(listOf<Long>(0, 1, 2, 3), nav.entries.map { it.id })
        assertEquals(4, CountingViewModel.created.get())

        // BACK: entry 3 pops and its ViewModel is cleared (onCleared), while
        // the Collection below the top keeps its retained instance.
        compose.runOnIdle { nav.back() }
        waitForText("2:artist:x")
        assertEquals(4, CountingViewModel.created.get())
        assertEquals(1, CountingViewModel.cleared.get())

        compose.runOnIdle { nav.back() }
        waitForText("1:col:a")
        assertEquals(4, CountingViewModel.created.get())
        assertEquals(2, CountingViewModel.cleared.get())

        // BACK to the single Home entry clears it too; a fresh push creates a
        // fresh ViewModel instead of resurrecting the popped one.
        compose.runOnIdle { nav.back() }
        waitForText("0:home")
        assertEquals(3, CountingViewModel.cleared.get())

        compose.runOnIdle { nav.push(Screen.Collection(FixturesAlbumA)) }
        waitForText("4:col:a")
        assertEquals(5, CountingViewModel.created.get())

        // Profile-switch mechanism: SessionViewModels.reset() clears the
        // session store and key(sessionGeneration) disposes NavDisplay, which
        // clears every live entry ViewModel (both 0:home and 4:col:a).
        val liveBefore = CountingViewModel.cleared.get()
        compose.runOnIdle {
            store.clear()
            sessionGeneration.intValue++
        }
        compose.waitForIdle()
        assertEquals(liveBefore + 2, CountingViewModel.cleared.get())
    }

    /**
     * Pure back-stack semantics behind the UI wiring: BACK on a non-Home root
     * tab goes Home, BACK on a pushed detail pops to the previous screen,
     * BACK on Home exits (no-op), and duplicate pushes never collide.
     */
    @Test
    fun backStackSemanticsWithDuplicatePushes() {
        val nav = NavState()

        nav.selectTab(Screen.Settings)
        assertEquals(listOf<Screen>(Screen.Settings), nav.stack)
        nav.back()
        assertEquals(Screen.Home, nav.top)

        // BACK on Home with a single entry is the app exit: nothing changes.
        nav.back()
        assertEquals(Screen.Home, nav.top)

        nav.selectTab(Screen.Albums)
        nav.actions.onOpenCollection(FixturesAlbumA)
        nav.actions.onOpenArtist(FixturesArtistX)
        nav.actions.onOpenCollection(FixturesAlbumA)
        assertEquals(listOf("albums", "col:a", "artist:x", "col:a"), nav.stack.map { NavSpec.key(it) })
        // Same saveable key, distinct entries: the id is what keeps them apart.
        assertEquals(4, nav.entries.map { it.id }.distinct().size)

        nav.back()
        assertTrue(nav.top is Screen.Artist)
        nav.back()
        assertTrue(nav.top is Screen.Collection)
        nav.back()
        assertEquals(Screen.Albums, nav.top)
    }

    // ---------------------------------------------------------------------
    // The real app: AppRoot wiring over the fake source.
    // ---------------------------------------------------------------------

    private class FakeCounts {
        val homeShelfLoads = AtomicInteger()
        val libraryAlbumLoads = AtomicInteger()
        val trackLoads: MutableMap<String, AtomicInteger> = ConcurrentHashMap()
        fun tracks(albumId: String) = trackLoads.computeIfAbsent(albumId) { AtomicInteger() }
    }

    private val app = ApplicationProvider.getApplicationContext<AuraApp>()

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
            override val id = "nav3-scoping-test"
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

    private fun renderApp() {
        compose.setContent { AuraTheme { AppRoot() } }
        waitForText("Album album-a")
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

    /**
     * Pushed detail entries are scoped per NavDisplay entry: popping clears
     * them, so opening albums A, B and A again loads each fresh — the popped
     * CollectionViewModels are never resurrected and B's store is not A's.
     */
    @Test
    fun pushedCollectionViewModelsAreFreshPerEntry() {
        setUpTestEnv()
        app.jellyfin.auth.installSession(Session("http://localhost", "nav-user", "Test", "token", "device"))
        val counts = installFakeSource()
        renderApp()
        val shelvesBefore = counts.homeShelfLoads.get()

        openAlbum("album-a")
        assertEquals(1, counts.tracks("album-a").get())
        goBack()
        waitForText("Album album-a")

        openAlbum("album-b")
        assertEquals(1, counts.tracks("album-b").get())
        assertEquals(1, counts.tracks("album-a").get())
        goBack()
        waitForText("Album album-a")

        openAlbum("album-a")
        assertEquals(2, counts.tracks("album-a").get())

        // Home itself was never reloaded during all of this: its ViewModel
        // stays with the session, not with the popped entries.
        assertEquals(shelvesBefore, counts.homeShelfLoads.get())
    }

    /**
     * BACK on a single-entry root tab goes Home, and Home -> Library -> Home
     * keeps the HomeViewModel (no shelf reload after the first load).
     */
    @Test
    fun backOnRootTabGoesHomeAndRailSwitchesRetainTheHomeViewModel() {
        setUpTestEnv()
        app.jellyfin.auth.installSession(Session("http://localhost", "nav-user", "Test", "token", "device"))
        val counts = installFakeSource()
        renderApp()

        railTo("Settings", "Signed in as")
        goBack()
        waitForText("Album album-a")

        val shelvesBefore = counts.homeShelfLoads.get()
        railTo("Library", "Album album-a")
        assertTrue(counts.libraryAlbumLoads.get() >= 1)
        railTo("Home", "Album album-a")
        assertEquals(shelvesBefore, counts.homeShelfLoads.get())
    }

    /**
     * Rapidly switching Home -> Library -> Home mid-crossfade (or jumping to
     * Home while already on Home) must not compose two tab-root entries with
     * the same SaveableStateProvider key.
     */
    @Test
    fun rapidTabSwitchAndSameTabJumpDoNotCrashWithDuplicateSaveableKey() {
        setUpTestEnv()
        app.jellyfin.auth.installSession(Session("http://localhost", "nav-user", "Test", "token", "device"))
        installFakeSource()
        renderApp()

        // Same-tab jump while already on Home.
        compose.runOnIdle { DebugNav.post("home") }
        compose.waitForIdle()
        waitForText("Album album-a")

        // Mid-transition rapid switch: Home -> Library -> Home before fade completes.
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription("Library")
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithContentDescription("Home")
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.mainClock.advanceTimeBy(1_000)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        waitForText("Album album-a")
    }

    /**
     * A profile switch wipes the session: Home (tab root) reloads from the
     * source, the stack resets to Home, and re-opening the album fetches its
     * tracks again — nothing survives the reset.
     */
    @Test
    fun profileSwitchClearsTabRootAndEntryViewModels() {
        setUpTestEnv()
        val auth = app.jellyfin.auth
        auth.save(Session("http://localhost", "b", "Profile B", "token", "device"))
        auth.save(Session("http://localhost", "a", "Profile A", "token", "device"))
        val counts = installFakeSource()
        renderApp()
        val shelvesBefore = counts.homeShelfLoads.get()

        openAlbum("album-a")
        goBack()
        waitForText("Album album-a")

        railTo("Settings", "Signed in as")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Profile B"))
        compose.onNodeWithText("Profile B").performSemanticsAction(SemanticsActions.OnClick) { it() }

        // New session: a fresh HomeViewModel fetches the shelves again, and
        // the profile-b album name proves the old Home is not being reused.
        waitForText("Album album-a profile b")
        assertTrue(counts.homeShelfLoads.get() > shelvesBefore)

        openAlbum("album-a")
        // Entry-scoped ViewModel: fresh after the switch (2 = once before,
        // once after), even though the same album id was opened.
        assertEquals(2, counts.tracks("album-a").get())
    }

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    companion object {
        private val FixturesAlbumA = com.hans.aura.model.Album(id = "a", name = "Album A")
        private val FixturesArtistX = com.hans.aura.model.Artist(id = "x", name = "Artist X")

        /** Counts creations and clears of entry-scoped ViewModels. */
        class CountingViewModel : ViewModel() {
            companion object {
                val created = AtomicInteger()
                val cleared = AtomicInteger()
            }

            init {
                created.incrementAndGet()
            }

            override fun onCleared() {
                cleared.incrementAndGet()
            }
        }
    }
}
