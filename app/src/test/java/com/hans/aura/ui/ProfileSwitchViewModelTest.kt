package com.hans.aura.ui

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTestConfig
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
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
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class ProfileSwitchViewModelTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>(
        ComposeUiTestConfig(inputMode = InputMode.Keyboard)
    )

    @Test
    fun returningToProfileReloadsItsHomeInsteadOfShowingOldShelves() {
        val app = ApplicationProvider.getApplicationContext<AuraApp>()
        val serverUrl = MockWebServer().use { it.url("/").toString() }
        val auth = app.jellyfin.auth
        auth.save(Session(serverUrl, "b", "Profile B", "test-token", "device"))
        auth.save(Session(serverUrl, "a", "Profile A", "test-token", "device"))
        AtmosFilter.setEnabled(false)
        shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
        val updated = AtomicBoolean(false)
        val originalSource = requireNotNull(MusicSources.current)
        MusicSources.install(object : MusicSource by originalSource {
            override val hasPlayHistory = false
            override suspend fun atmosAlbumIds(): Set<String> = emptySet()
            override suspend fun artists(sortBy: String, sortOrder: String, limit: Int, startIndex: Int) =
                ItemsResponse()

            override suspend fun browse(query: LibraryFilter.ItemsQuery, cachePrefix: String?): ItemsResponse {
                if (query.includeItemTypes != "MusicAlbum") return ItemsResponse()
                val name = when {
                    auth.session?.userId == "b" -> "Profile B album"
                    updated.get() -> "Fresh profile A album"
                    else -> "Old profile A album"
                }
                return ItemsResponse(items = listOf(JellyfinItem(id = name, name = name, type = "MusicAlbum")))
            }
        })
        try {
            compose.setContent { AuraTheme { AppRoot() } }
            waitForText("Old profile A album")

            switchTo("Profile B")
            assertEquals("b", auth.session?.userId)
            waitForText("Profile B album")
            updated.set(true)
            switchTo("Profile A")
            assertEquals("a", auth.session?.userId)

            waitForText("profile A album")
            compose.onNodeWithText("Old profile A album").assertDoesNotExist()
            compose.onNodeWithText("Profile B album").assertDoesNotExist()
            compose.onAllNodesWithText("Fresh profile A album")[0].assertExists()
        } finally {
            MusicSources.install(originalSource)
            auth.clear()
        }
    }

    private fun switchTo(name: String) {
        compose.runOnIdle { DebugNav.post("settings") }
        waitForText("Signed in as")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(name))
        compose.onNodeWithText(name).performSemanticsAction(SemanticsActions.OnClick) { it() }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
