@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.hans.aura.ui.home

import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hans.aura.AuraApp
import com.hans.aura.data.JellyfinItem
import com.hans.aura.ui.common.ErrorPane
import com.hans.aura.ui.theme.TextPrimary

@Composable
fun HomeScreen(
    app: AuraApp,
    player: com.hans.aura.ui.player.PlayerConnection,
    onSignOut: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenCollection: (JellyfinItem) -> Unit,
    onOpenArtist: (JellyfinItem) -> Unit,
    onPlayTrack: (track: JellyfinItem, queue: List<JellyfinItem>, index: Int) -> Unit,
    refreshKey: Int = 0,
    focusTick: Int = 0,
    viewModel: HomeViewModel = viewModel(
        key = "home:${app.jellyfin.auth.session?.userId ?: "anon"}",
        factory = viewModelFactory { initializer { HomeViewModel(app) } }
    )
) {
    val session = app.jellyfin.auth.session ?: return
    val serverUrl = session.serverUrl
    val state by viewModel.state.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(refreshKey) { viewModel.onRefreshKey(refreshKey) }

    fun trackMenu(track: JellyfinItem) = com.hans.aura.ui.common.ContextMenuHost.open(
        com.hans.aura.ui.common.ContextMenus.forTrack(
            app, player, scope, track,
            onOpenArtist = onOpenArtist, onOpenCollection = onOpenCollection
        )
    )

    fun albumMenu(item: JellyfinItem) = com.hans.aura.ui.common.ContextMenuHost.open(
        com.hans.aura.ui.common.ContextMenus.forAlbum(
            app, player, scope, item, onOpenArtist = onOpenArtist
        )
    )

    val contentFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val focusKeeper = remember { com.hans.aura.ui.common.FocusKeeper() }
    var focusedCardId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    com.hans.aura.ui.common.RestoreFocusOnBack(focusTick, contentFocus, focusedCardId, focusKeeper)

    Column(
        Modifier
            .fillMaxSize()
            .padding(vertical = 8.dp)
            .focusRequester(contentFocus)
            .focusRestorer()
    ) {
        if (!state.loading && state.error != null && state.allShelvesEmpty) {
            ErrorPane(
                message = "Couldn't load from server",
                detail = state.error,
                onRetry = { viewModel.retry() }
            )
            return@Column
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (!state.loading && state.error != null) {
                ErrorPane(
                    message = "Some shelves couldn't load",
                    onRetry = { viewModel.retry() },
                    modifier = Modifier.height(200.dp)
                )
            }
            // History shelves only exist on a backend that records play history; on a
            // backend without it they are absent rather than empty.
            if (!state.recentlyPlayed.isNullOrEmpty()) {
                MotionShelf(
                    title = "Recently played",
                    items = state.recentlyPlayed,
                    showLoading = state.loading,
                    serverUrl = serverUrl,
                    focusKeeper = focusKeeper,
                    onFocused = { focusedCardId = it },
                    onLongPress = { trackMenu(it) },
                    onOpen = { item, idx ->
                        onPlayTrack(item, state.recentlyPlayed ?: listOf(item), idx)
                    }
                )
            }
            if (!state.recentArtists.isNullOrEmpty()) {
                MotionShelf(
                    title = "Recently played artists",
                    items = state.recentArtists,
                    showLoading = state.loading,
                    serverUrl = serverUrl,
                    focusKeeper = focusKeeper,
                    onFocused = { focusedCardId = it },
                    onOpen = { item, _ -> onOpenArtist(item) }
                )
            }
            MotionShelf(
                title = "Recently added",
                items = state.recentAlbums,
                showLoading = state.loading,
                serverUrl = serverUrl,
                focusKeeper = focusKeeper,
                onFocused = { focusedCardId = it },
                onLongPress = { albumMenu(it) },
                onOpen = { item, _ -> onOpenCollection(item) }
            )
            MotionShelf(
                title = "Your Atmos",
                items = state.atmosAlbums,
                showLoading = state.loading,
                serverUrl = serverUrl,
                focusKeeper = focusKeeper,
                onFocused = { focusedCardId = it },
                badgeFor = { "ATMOS" },
                onLongPress = { albumMenu(it) },
                onOpen = { item, _ -> onOpenCollection(item) }
            )
            MotionShelf(
                title = "Artists",
                items = state.artists,
                showLoading = state.loading,
                serverUrl = serverUrl,
                focusKeeper = focusKeeper,
                onFocused = { focusedCardId = it },
                onOpen = { item, _ -> onOpenArtist(item) }
            )
            // Only when the source actually has playlists: a backend without
            // them reports an empty shelf and no empty "Playlists" row is drawn.
            if (!state.playlists.isNullOrEmpty()) {
                MotionShelf(
                    title = "Playlists",
                    items = state.playlists,
                    showLoading = state.loading,
                    serverUrl = serverUrl,
                    focusKeeper = focusKeeper,
                    onFocused = { focusedCardId = it },
                    onLongPress = { albumMenu(it) },
                    onOpen = { item, _ -> onOpenCollection(item) }
                )
            }
            Spacer(Modifier.height(140.dp)) // clear of the now-playing bar
        }
    }
}

/**
 * One home shelf whose covers only move while the row is on screen.
 *
 * Home is a plain scrolling `Column`, so there is no lazy layoutInfo to read
 * the way the Albums grid does: the row measures itself against the window
 * instead (`rememberOnScreen`) and hands that flag down. Two shelf rows of
 * slack count as "close enough", which is the vertical half of the prefetch
 * the grid gets from its lookahead. ItemShelf narrows it again horizontally,
 * and the process-wide budget caps the total at three players, so a screen full
 * of shelves still cannot start a decoder per cover.
 */
@Composable
private fun MotionShelf(
    title: String,
    items: List<JellyfinItem>?,
    showLoading: Boolean,
    serverUrl: String,
    focusKeeper: com.hans.aura.ui.common.FocusKeeper,
    onFocused: (String) -> Unit,
    onOpen: (JellyfinItem, Int) -> Unit,
    badgeFor: ((JellyfinItem) -> String?)? = null,
    onLongPress: ((JellyfinItem) -> Unit)? = null
) {
    val onScreen = com.hans.aura.ui.common.rememberOnScreen()
    com.hans.aura.ui.common.ItemShelf(
        title = title,
        items = items,
        showLoading = showLoading,
        serverUrl = serverUrl,
        focusKeeper = focusKeeper,
        onFocused = onFocused,
        onOpen = onOpen,
        badgeFor = badgeFor,
        onLongPress = onLongPress,
        animate = onScreen.visible,
        modifier = onScreen.modifier
    )
}
