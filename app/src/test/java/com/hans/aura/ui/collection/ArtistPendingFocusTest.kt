package com.hans.aura.ui.collection

import com.hans.aura.data.Fixtures
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.hans.aura.data.JellyfinItem
import com.hans.aura.ui.common.InitialFocusOnPush
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ArtistPendingFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun emptyDiscographyWaitsForFallbackTopTracks() = pendingArtist(false)

    @Test
    fun filteredAlbumsWaitForAtmosCatalog() = pendingArtist(true)

    @Test
    fun settledEmptyResultDoesNotFocusLaterRefresh() = pendingArtist(false, terminal = true)

    @Test
    fun settledErrorDoesNotFocusLaterRefresh() = pendingArtist(false, terminal = true, error = true)

    private fun pendingArtist(atmos: Boolean, terminal: Boolean = false, error: Boolean = false) {
        compose.mainClock.autoAdvance = false
        val album = Fixtures.domainAlbum("album")
        val track = Fixtures.domainTrack("track").copy(albumId = "album")
        val state = mutableStateOf(ArtistUiState(
            albums = if (atmos) listOf(album) else emptyList(),
            similar = emptyList(), topTracks = if (atmos || terminal) emptyList() else null,
            contentLoading = !(atmos || terminal),
            error = if (error) "failed" else null,
            atmosView = atmos
        ))
        val outside = FocusRequester()
        val content = FocusRequester()
        compose.setContent {
            val loaded = state.value
            val ready = loaded.visibleAlbums.isNotEmpty() || loaded.visibleTopTracks.isNotEmpty()
            Column {
                Box(Modifier.size(40.dp).testTag("outside").focusRequester(outside).focusable())
                if (ready) Box(Modifier.size(40.dp).testTag("content").focusRequester(content).focusable())
            }
            LaunchedEffect(Unit) { outside.requestFocus() }
            InitialFocusOnPush(true, content, ready = ready,
                cancelled = !ready && loaded.focusContentSettled)
        }
        repeat(20) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithTag("outside").assertIsFocused()
        compose.runOnIdle {
            state.value = if (atmos) state.value.copy(atmosAlbumIds = setOf("album"), atmosLoading = false)
                else state.value.copy(topTracks = listOf(track), contentLoading = false)
        }
        repeat(6) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithTag(if (terminal) "outside" else "content").assertIsFocused()
    }
}
