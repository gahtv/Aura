package com.hans.aura.ui.library

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.test.ComposeUiTestConfig
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class LibraryOfflinePagingTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>(
        ComposeUiTestConfig(inputMode = InputMode.Keyboard)
    )

    @Test
    fun cachedAlbumsStopShowingLoadingAfterPageRequestFails() {
        val app = ApplicationProvider.getApplicationContext<AuraApp>()
        val serverUrl = MockWebServer().use { it.url("/").toString() }
        app.jellyfin.auth.installSession(Session(serverUrl, "offline-user", "Test", "token", "device"))
        val album = JellyfinItem(id = "cached", name = "Cached album", type = "MusicAlbum")
        AtmosFilter.setEnabled(false)
        LibraryCache.clear()
        PagedLibrary.clear()
        LibraryCache.put("lib_albums:offline-user:SortName:false", listOf(album))
        shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
        val player = PlayerConnection(app, app.jellyfin) { app.jellyfin.auth.session }
        try {
            val model = LibraryViewModel(app, "Albums")
            compose.setContent {
                AuraTheme {
                    LibraryScreen(
                        app, "Albums", player, onOpenCollection = {}, onOpenArtist = {}, viewModel = model
                    )
                }
            }
            compose.waitUntil(timeoutMillis = 10_000) { model.state.value.error != null }

            assertFalse(model.state.value.loading)
            compose.onNodeWithText(album.name).assertExists()
            compose.onNodeWithContentDescription("Loading more library items").assertDoesNotExist()
        } finally {
            player.disconnect()
            app.jellyfin.auth.clear()
            LibraryCache.clear()
            PagedLibrary.clear()
        }
    }
}
