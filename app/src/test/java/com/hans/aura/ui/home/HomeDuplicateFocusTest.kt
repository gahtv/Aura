package com.hans.aura.ui.home

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.hans.aura.AuraApp
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.LibraryCache
import com.hans.aura.data.MusicSource
import com.hans.aura.data.MusicSources
import com.hans.aura.data.Session
import com.hans.aura.playback.PlaybackService
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.AuraTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h1800dp-land")
class HomeDuplicateFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun backRestoresLowerCopyOfAlbumSharedByTwoShelves() = restoreCopy(2)

    @Test
    fun backRestoresUpperCopyOfAlbumSharedByTwoShelves() = restoreCopy(1)

    private fun restoreCopy(copy: Int) {
        compose.mainClock.autoAdvance = false
        val app = ApplicationProvider.getApplicationContext<AuraApp>()
        app.jellyfin.auth.installSession(Session("http://example.test", "duplicate-user", "Test", "token", "device"))
        val album = JellyfinItem(id = "duplicate", name = "Duplicate album", type = "MusicAlbum")
        LibraryCache.clear()
        for (key in listOf("home_new", "home_atmos")) LibraryCache.put("$key:duplicate-user", listOf(album))
        for (key in listOf("home_artists", "home_playlists")) LibraryCache.put("$key:duplicate-user", emptyList())
        val source = MusicSources.current!!
        MusicSources.install(object : MusicSource by source {
            override val hasPlayHistory = false
            override suspend fun atmosAlbumIds() = setOf(album.id)
        })
        shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
        val player = PlayerConnection(app, app.jellyfin) { app.jellyfin.auth.session }
        val model = HomeViewModel(app)
        val away = mutableStateOf(false)
        val tick = mutableStateOf(0)
        val detailFocus = FocusRequester()
        try {
            compose.setContent {
                val holder = rememberSaveableStateHolder()
                AuraTheme {
                    if (away.value) Box(Modifier.size(40.dp).focusRequester(detailFocus).focusable())
                    else holder.SaveableStateProvider("home") {
                        HomeScreen(app, player, onSignOut = {}, onOpenSearch = {},
                            onOpenCollection = { away.value = true }, onOpenArtist = {},
                            onPlayTrack = { _, _, _ -> }, focusTick = tick.value, viewModel = model)
                    }
                }
            }
            frames()
            val copies = compose.onAllNodes(hasText(album.name) and isFocusable())
            org.junit.Assert.assertEquals(3, copies.fetchSemanticsNodes().size)
            copies[copy].performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            frames()
            copies[copy].assertIsFocused().performClick()
            frames()
            compose.runOnIdle { detailFocus.requestFocus() }
            compose.runOnIdle { org.junit.Assert.assertTrue(away.value); tick.value++; away.value = false }
            frames()
            copies[copy].assertIsFocused()
        } finally {
            player.disconnect()
            MusicSources.reset()
            LibraryCache.clear()
        }
    }

    private fun frames() {
        repeat(20) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
    }
}
