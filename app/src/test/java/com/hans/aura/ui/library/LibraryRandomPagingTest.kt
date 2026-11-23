package com.hans.aura.ui.library

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.test.ComposeUiTestConfig
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.hans.aura.AuraApp
import com.hans.aura.data.AtmosFilter
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.LibraryCache
import com.hans.aura.data.PagedLibrary
import com.hans.aura.data.Session
import com.hans.aura.playback.PlaybackService
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.AuraTheme
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class LibraryRandomPagingTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>(
        ComposeUiTestConfig(inputMode = InputMode.Keyboard)
    )

    @Test
    fun randomTrackPagesCanRenderTheSameTrack() = renderOverlappingPages("Tracks", "Audio")

    @Test
    fun randomAlbumPagesCanRenderTheSameAlbum() = renderOverlappingPages("Albums", "MusicAlbum")

    private fun renderOverlappingPages(kind: String, type: String) {
        val app = ApplicationProvider.getApplicationContext<AuraApp>()
        val serverUrl = MockWebServer().use { it.url("/").toString() }
        val userId = "random-user"
        app.jellyfin.auth.installSession(Session(serverUrl, userId, "Test", "token", "device"))
        AtmosFilter.setEnabled(false)
        LibraryCache.clear()
        PagedLibrary.clear()
        val item = JellyfinItem(id = "repeated", name = "Repeated item", type = type)
        PagedLibrary.put("${LibraryCache.tabKeyPrefix(kind, userId)}Random:false", listOf(item, item), total = 2)
        shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
        val player = PlayerConnection(app, app.jellyfin) { app.jellyfin.auth.session }
        val model = LibraryViewModel(app, kind)
        try {
            compose.setContent {
                AuraTheme {
                    LibraryScreen(
                        app, kind, player, onOpenCollection = {}, onOpenArtist = {}, viewModel = model
                    )
                }
            }
            compose.runOnIdle { model.onSortClick(SortMode.RANDOM) }
            compose.waitUntil(timeoutMillis = 10_000) { model.state.value.items.size == 2 }

            compose.onAllNodesWithText("Repeated item").assertCountEquals(2)
        } finally {
            player.disconnect()
            app.jellyfin.auth.clear()
            LibraryCache.clear()
            PagedLibrary.clear()
        }
    }
}
