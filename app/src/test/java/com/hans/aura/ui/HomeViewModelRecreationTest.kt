package com.hans.aura.ui

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.test.ComposeUiTestConfig
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.hans.aura.AuraApp
import com.hans.aura.data.AtmosFilter
import com.hans.aura.data.ItemsResponse
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.LibraryFilter
import com.hans.aura.data.MusicSource
import com.hans.aura.data.MusicSources
import com.hans.aura.data.Session
import com.hans.aura.playback.PlaybackService
import com.hans.aura.ui.theme.AuraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class HomeViewModelRecreationTest {
    @get:Rule
    val compose = createEmptyComposeRule(ComposeUiTestConfig(inputMode = InputMode.Keyboard))

    @Test
    fun recreatingActivityKeepsTheHomeViewModelAndItsLoadedShelf() {
        val app = ApplicationProvider.getApplicationContext<AuraApp>()
        val auth = app.jellyfin.auth
        auth.installSession(Session("http://localhost", "home-user", "Test", "token", "device"))
        AtmosFilter.setEnabled(false)
        shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
        val loads = AtomicInteger()
        val originalSource = requireNotNull(MusicSources.current)
        MusicSources.install(object : MusicSource by originalSource {
            override val hasPlayHistory = false
            override suspend fun cached(prefix: String): List<JellyfinItem>? = null
            override suspend fun atmosAlbumIds(): Set<String> = emptySet()
            override suspend fun artists(sortBy: String, sortOrder: String, limit: Int, startIndex: Int) =
                ItemsResponse()

            override suspend fun browse(query: LibraryFilter.ItemsQuery, cachePrefix: String?): ItemsResponse {
                if (query.includeItemTypes != "MusicAlbum") return ItemsResponse()
                val load = loads.incrementAndGet()
                val album = JellyfinItem(id = "album-$load", name = "Loaded album $load", type = "MusicAlbum")
                return ItemsResponse(items = listOf(album))
            }
        })
        try {
            Robolectric.buildActivity(HomeActivity::class.java).setup().use { activity ->
                waitForLoadedAlbum()
                activity.recreate()
                waitForLoadedAlbum()

                assertEquals("Home reloads from the source if its ViewModel is replaced", 1, loads.get())
                compose.onAllNodesWithText("Loaded album 1")[0].assertExists()
            }
        } finally {
            MusicSources.install(originalSource)
            auth.clear()
        }
    }

    private fun waitForLoadedAlbum() {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Loaded album", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    class HomeActivity : ComponentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContent { AuraTheme { AppRoot() } }
        }
    }
}
