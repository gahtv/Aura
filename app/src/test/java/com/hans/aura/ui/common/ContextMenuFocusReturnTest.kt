package com.hans.aura.ui.common

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTestConfig
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import com.hans.aura.AuraApp
import com.hans.aura.data.Fixtures
import com.hans.aura.data.Session
import com.hans.aura.playback.PlaybackService
import com.hans.aura.ui.collection.trackRowFocusId
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.AuraTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.MusicSource
import com.hans.aura.data.MusicSources
import com.hans.aura.model.Playlist
import com.hans.aura.ui.collection.CollectionScreen
import com.hans.aura.ui.collection.entryOccurrences
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class ContextMenuFocusReturnTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>(
        ComposeUiTestConfig(inputMode = InputMode.Keyboard)
    )

    private val app = ApplicationProvider.getApplicationContext<AuraApp>()

    @After
    fun tearDown() {
        ContextMenuHost.close()
        app.container.mutations.reset()
        MusicSources.reset()
        app.jellyfin.auth.clear()
    }

    @Test
    fun longPressPickerReturnsFocusToTheExactRepeatedEntry() {
        setUpPlaylistAba()
        // First "Song A" entry; the third row repeats the same track id.
        val firstA = compose.onAllNodesWithText("Song A")[0]
        firstA.performSemanticsAction(SemanticsActions.RequestFocus)
        firstA.assertIsFocused()

        // Hold OK: the long-press opens the track menu.
        firstA.performKeyInput { keyDown(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(600)
        firstA.performKeyInput { keyUp(Key.DirectionCenter) }
        openPickerThenPressBack()

        compose.onAllNodesWithText("Song A")[0].assertIsFocused()
        compose.onAllNodesWithText("Song A")[1].assertIsNotFocused()
    }

    @Test
    fun overflowPillPickerReturnsFocusToThatPill() {
        setUpPlaylistAba()
        compose.runOnIdle { keeper.requesterFor(trackRowFocusId("Song A", 1)).requestFocus() }
        val secondA = compose.onAllNodesWithText("Song A")[1]
        secondA.performKeyInput { pressKey(Key.DirectionRight) }
        compose.onAllNodesWithContentDescription("Add to favorites")[2]
            .performKeyInput { pressKey(Key.DirectionRight) }
        val pill = compose.onAllNodesWithText("\u2022\u2022\u2022")[2]
        pill.assertIsFocused()

        pill.performKeyInput { pressKey(Key.DirectionCenter) }
        openPickerThenPressBack()

        compose.onAllNodesWithText("\u2022\u2022\u2022")[2].assertIsFocused()
        compose.onAllNodesWithText("\u2022\u2022\u2022")[0].assertIsNotFocused()
    }

    @Test
    fun playlistScreenRestoresTheExactRepeatedEntry() {
        app.jellyfin.auth.save(Session("http://127.0.0.1:9/", "user", "test", "token", "device"))
        shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
        installPlaylist(listOf("A", "B", "A"))
        val player = PlayerConnection(app, app.jellyfin) { app.jellyfin.auth.session }
        var focusTick by mutableIntStateOf(0)
        compose.setContent {
            AuraTheme {
                Box {
                    CollectionScreen(
                        app = app,
                        collection = Playlist(id = "p", name = "Mix"),
                        player = player,
                        onBack = {},
                        focusTick = focusTick
                    )
                    ContextMenuHosts(app)
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Song A").fetchSemanticsNodes().size == 2 }

        // First "Song A" entry; the third row repeats its track id.
        val firstA = compose.onAllNodesWithText("Song A")[0]
        firstA.performSemanticsAction(SemanticsActions.RequestFocus)
        firstA.assertIsFocused()

        // The picker hands focus back to that entry...
        firstA.performKeyInput { keyDown(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(600)
        firstA.performKeyInput { keyUp(Key.DirectionCenter) }
        openPickerThenPressBack()
        compose.onAllNodesWithText("Song A")[0].assertIsFocused()
        compose.onAllNodesWithText("Song A")[1].assertIsNotFocused()

        // ...and so does BACK-restore after focus has left the list.
        compose.onNodeWithText("Play").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onAllNodesWithText("Song A")[0].assertIsNotFocused()
        compose.runOnIdle { focusTick++ }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(100)
        compose.onAllNodesWithText("Song A")[0].assertIsFocused()
        compose.onAllNodesWithText("Song A")[1].assertIsNotFocused()
    }

    @Test
    fun playlistScreenRemovesTheClickedRepeatedEntry() {
        app.jellyfin.auth.save(Session("http://127.0.0.1:9/", "user", "test", "token", "device"))
        shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
        val removals = installPlaylist(listOf("A", "B", "A", "C", "A"))
        val player = PlayerConnection(app, app.jellyfin) { app.jellyfin.auth.session }
        compose.setContent {
            AuraTheme {
                Box {
                    CollectionScreen(
                        app = app,
                        collection = Playlist(id = "p", name = "Mix"),
                        player = player,
                        onBack = {}
                    )
                    ContextMenuHosts(app)
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Song A").fetchSemanticsNodes().size == 3 }

        // Middle copy of A: row 3, second occurrence.
        val middleA = compose.onAllNodesWithText("Song A")[1]
        middleA.performSemanticsAction(SemanticsActions.RequestFocus)
        middleA.performKeyInput { keyDown(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(600)
        middleA.performKeyInput { keyUp(Key.DirectionCenter) }
        compose.onNodeWithText("Remove from playlist").performSemanticsAction(SemanticsActions.OnClick)

        compose.waitUntil(5_000) { removals.isNotEmpty() }
        assertEquals(listOf("A" to 1), removals.toList())
    }

    @Test
    fun entryOccurrencesCountEarlierCopiesOfTheSameTrack() {
        val tracks = listOf("A", "B", "A", "C", "A", "B").map { Fixtures.domainTrack(it) }
        assertEquals(listOf(0, 0, 1, 0, 2, 1), entryOccurrences(tracks).toList())
    }

    /** Installs a source serving [ids] as playlist "p"; returns recorded removals. */
    private fun installPlaylist(ids: List<String>): MutableList<Pair<String, Int>> {
        val removals = CopyOnWriteArrayList<Pair<String, Int>>()
        val base = requireNotNull(MusicSources.current)
        MusicSources.install(object : MusicSource by base {
            override val id = "repeated-entry-focus-test"
            override suspend fun cached(prefix: String): List<JellyfinItem>? = null
            override fun cache(prefix: String, items: List<JellyfinItem>) {}
            override suspend fun item(itemId: String): JellyfinItem? = null
            override suspend fun albumBio(itemId: String): String? = null
            override suspend fun atmosAlbumIds(): Set<String> = emptySet()
            override suspend fun playlistItems(playlistId: String) = ids.map {
                JellyfinItem(id = it, name = "Song $it", type = "Audio", playlistItemId = it)
            }
            override suspend fun removeFromPlaylist(playlistId: String, itemId: String, occurrence: Int) {
                removals.add(itemId to occurrence)
            }
        })
        return removals
    }

    private val keeper = FocusKeeper()

    /** Playlist order A, B, A rendered under the real AppRoot overlay host. */
    private fun setUpPlaylistAba() {
        // The picker renders only while signed in.
        app.jellyfin.auth.save(Session("http://127.0.0.1:9/", "user", "test", "token", "device"))
        shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
        val player = PlayerConnection(app, app.jellyfin) { app.jellyfin.auth.session }
        val a = Fixtures.domainTrack("Song A")
        val tracks = listOf(a, Fixtures.domainTrack("Song B"), a)
        compose.setContent {
            AuraTheme {
                val scope = rememberCoroutineScope()
                Box {
                    Column {
                        tracks.forEachIndexed { i, track ->
                            val occurrence = tracks.take(i).count { it.id == track.id }
                            TrackRow(
                                index = i + 1,
                                track = track,
                                isCurrent = false,
                                onClick = {},
                                focusKeeper = keeper,
                                favorite = false,
                                onToggleFavorite = {},
                                focusId = trackRowFocusId(track.id, occurrence),
                                onLongPress = {
                                    ContextMenuHost.open(
                                        ContextMenus.forTrack(app, player, scope, track, null, null)
                                    )
                                }
                            )
                        }
                    }
                    ContextMenuHosts(app)
                }
            }
        }
    }

    private fun openPickerThenPressBack() {
        compose.onNodeWithText("Add to playlist").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        // The picker traps focus while open.
        compose.onNodeWithText("New playlist\u2026").assertIsFocused()
        compose.onNodeWithText("New playlist\u2026").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("New playlist\u2026").assertIsFocused()

        // BACK goes through the picker's own BackHandler.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("New playlist\u2026").assertDoesNotExist()
    }
}
