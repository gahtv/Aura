package com.hans.aura.ui.nowplaying

import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import com.hans.aura.data.JellyfinClient
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.JellyfinSource
import com.hans.aura.data.MediaRepository
import com.hans.aura.data.MusicSource
import com.hans.aura.data.ProviderLyricMatch
import com.hans.aura.data.ProviderLyricsResult
import com.hans.aura.ui.theme.AuraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class ProviderLyricsFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun backFromPreviewRestoresTheFocusedResultAndItsScrollPosition() {
        shadowOf(compose.activity.packageManager)
            .setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        val client = JellyfinClient(compose.activity.applicationContext)
        val jellyfin = JellyfinSource(client, MediaRepository(client, { client.auth.session }))
        val source = object : MusicSource by jellyfin {
            override suspend fun searchProviderLyrics(itemId: String) =
                ProviderLyricsResult.Success(List(12) { index ->
                    ProviderLyricMatch("$index", "Match $index", true, listOf("Lyric $index"))
                })
        }
        compose.setContent {
            AuraTheme {
                ProviderLyricsDialog(source, JellyfinItem("track", "Test track"), {}, {})
            }
        }
        fun result(index: Int) = compose.onNodeWithText("Match $index · Synced")

        compose.onNode(hasScrollAction()).performScrollToIndex(4)
        result(5).performSemanticsAction(SemanticsActions.RequestFocus)
        val selected = result(5).assertIsFocused()
        val initialTop = selected.getUnclippedBoundsInRoot().top
        selected.performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Preview lyrics").assertExists()
        compose.onNodeWithText("Back").performSemanticsAction(SemanticsActions.OnClick)

        val restored = result(5).assertIsFocused()
        assertEquals(initialTop, restored.getUnclippedBoundsInRoot().top)
    }
}
