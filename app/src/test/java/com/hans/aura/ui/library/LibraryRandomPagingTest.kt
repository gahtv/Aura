package com.hans.aura.ui.library

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.test.ComposeUiTestConfig
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.hans.aura.AuraApp
import com.hans.aura.data.AtmosFilter
import com.hans.aura.data.ItemsResponse
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.LibraryCache
import com.hans.aura.data.LibraryFilter
import com.hans.aura.data.MusicSource
import com.hans.aura.data.MusicSources
import com.hans.aura.data.PagedLibrary
import com.hans.aura.data.Session
import com.hans.aura.playback.PlaybackService
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.AuraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class LibraryRandomPagingTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>(
        ComposeUiTestConfig(inputMode = InputMode.Keyboard)
    )

    @Test
    fun randomTrackPagesRenderEachTrackOnceAfterADuplicateOnlyPage() =
        renderOverlappingPages("Tracks", "Audio", duplicateOnlyPage = true)

    @Test
    fun randomAlbumPagesRenderEachAlbumOnceAndKeepTheOffsetOnReturn() =
        renderOverlappingPages("Albums", "MusicAlbum", duplicateOnlyPage = false)

    private fun renderOverlappingPages(kind: String, type: String, duplicateOnlyPage: Boolean) {
        val app = ApplicationProvider.getApplicationContext<AuraApp>()
        app.jellyfin.auth.installSession(Session("http://localhost", "random-user", "Test", "token", "device"))
        AtmosFilter.setEnabled(false)
        LibraryCache.clear()
        PagedLibrary.clear()
        val repeated = JellyfinItem(id = "repeated", name = "Repeated item", type = type)
        val first = JellyfinItem(id = "first", name = "First item", type = type)
        val last = JellyfinItem(id = "last", name = "Last item", type = type)
        val offsets = CopyOnWriteArrayList<Int>()
        val originalSource = checkNotNull(MusicSources.current)
        MusicSources.install(object : MusicSource by originalSource {
            override val id = "random-paging-test"
            override suspend fun atmosAlbumIds(): Set<String> = emptySet()
            override suspend fun browse(query: LibraryFilter.ItemsQuery, cachePrefix: String?): ItemsResponse {
                if (query.sortBy != "Random") return ItemsResponse()
                offsets += query.startIndex
                val items = when (query.startIndex) {
                    0 -> listOf(repeated, first)
                    2 -> listOf(repeated, if (duplicateOnlyPage) first else last)
                    4 -> if (duplicateOnlyPage) listOf(first, last) else emptyList()
                    else -> error("Unexpected offset ${query.startIndex}")
                }
                return ItemsResponse(items = items, totalRecordCount = 6)
            }
        })
        shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
        val player = PlayerConnection(app, app.jellyfin) { app.jellyfin.auth.session }
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val original = LibraryViewModel(app, kind)
        try {
            val displayed = mutableStateOf<LibraryViewModel?>(null)
            compose.setContent {
                AuraTheme {
                    displayed.value?.let { model ->
                        LibraryScreen(
                            app, kind, player, onOpenCollection = {}, onOpenArtist = {}, viewModel = model
                        )
                    }
                }
            }
            compose.runOnIdle { original.onSortClick(SortMode.RANDOM) }
            compose.waitUntil(timeoutMillis = 10_000) {
                val state = original.state.value
                state.sort == SortMode.RANDOM && state.initialized && !state.loading
            }
            assertEquals("State: ${original.state.value}; offsets: $offsets", 2, original.state.value.items.size)
            compose.runOnIdle { original.loadNextPage() }
            compose.waitUntil(timeoutMillis = 10_000) { 2 in offsets && !original.state.value.loading }
            val expectedIds = listOf("repeated", "first", "last")
            assertEquals(expectedIds, original.state.value.items.map { it.id })

            val model = if (duplicateOnlyPage) original else LibraryViewModel(app, kind).also { restored ->
                compose.waitUntil(timeoutMillis = 10_000) { restored.state.value.initialized }
                assertEquals(expectedIds, restored.state.value.items.map { it.id })
                compose.runOnIdle { restored.loadNextPage() }
                compose.waitUntil(timeoutMillis = 10_000) { 4 in offsets && !restored.state.value.loading }
            }
            assertEquals(listOf(0, 2, 4), offsets.toList())
            assertEquals(expectedIds, model.state.value.items.map { it.id })
            assertEquals(expectedIds.size, model.state.value.total)
            compose.runOnIdle { displayed.value = model }
            compose.onAllNodesWithText("Repeated item").assertCountEquals(1)
            compose.onAllNodesWithText("Last item").assertCountEquals(1)
            assertEquals(listOf(0, 2, 4), offsets.toList())
        } finally {
            Dispatchers.resetMain()
            player.disconnect()
            MusicSources.install(originalSource)
            app.jellyfin.auth.clear()
            LibraryCache.clear()
            PagedLibrary.clear()
        }
    }
}
