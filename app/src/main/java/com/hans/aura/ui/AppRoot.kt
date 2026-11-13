package com.hans.aura.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.hans.aura.AuraApp
import com.hans.aura.data.AuthStore
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.LibraryCache
import com.hans.aura.ui.settings.SettingsScreen
import com.hans.aura.ui.collection.ArtistScreen
import com.hans.aura.ui.collection.CollectionScreen
import com.hans.aura.ui.home.HomeScreen
import com.hans.aura.ui.library.LibraryScreen
import com.hans.aura.ui.login.LoginScreen
import com.hans.aura.ui.nowplaying.NowPlayingScreen
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.player.PlayerConnection.Companion.isAtmos
import com.hans.aura.ui.queue.QueueScreen
import com.hans.aura.ui.search.SearchScreen
import com.hans.aura.ui.theme.Accent
import com.hans.aura.ui.theme.Canvas
import com.hans.aura.ui.theme.Surface
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary

/** Simple back-stack navigation between app destinations. */
private sealed interface Screen {
    data object Home : Screen
    data object Artists : Screen
    data object Albums : Screen
    data object Tracks : Screen
    data object Playlists : Screen
    data object Search : Screen
    data object Settings : Screen
    data object NowPlaying : Screen
    data object Queue : Screen
    data class Collection(val item: JellyfinItem) : Screen
    data class Artist(val item: JellyfinItem) : Screen

    companion object {
        /** The root tabs shown in the top bar (Search/Settings are icon buttons). */
        val tabs = listOf(
            Home to "Home",
            Artists to "Artists",
            Albums to "Albums",
            Tracks to "Tracks",
            Playlists to "Playlists"
        )

        fun key(s: Screen): String = when (s) {
            is Home -> "home"
            is Artists -> "artists"
            is Albums -> "albums"
            is Tracks -> "tracks"
            is Playlists -> "playlists"
            is Search -> "search"
            is Settings -> "settings"
            is NowPlaying -> "nowplaying"
            is Queue -> "queue"
            is Collection -> "col:${s.item.id}"
            is Artist -> "artist:${s.item.id}"
        }

        fun libraryKind(s: Screen) = when (s) {
            is Artists -> "Artists"
            is Albums -> "Albums"
            is Tracks -> "Tracks"
            is Playlists -> "Playlists"
            else -> null
        }
    }
}

/** Top bar: tabs, then [now-playing pill] [search] [settings] at the right.
 *  Visible on every screen so navigation is always one press away. */
@Composable
private fun TopBar(
    current: Screen,
    onSelect: (Screen) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    player: PlayerConnection
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Screen.tabs.forEach { (tab, label) ->
            val selected = tab::class == current::class
            TabButton(label = label, selected = selected) { onSelect(tab) }
            Spacer(Modifier.width(10.dp))
        }
        Spacer(Modifier.weight(1f))
        NowPlayingPill(player, current) { onOpenNowPlaying() }
        Spacer(Modifier.width(10.dp))
        IconNavButton(
            icon = androidx.compose.ui.res.painterResource(com.hans.aura.R.drawable.ic_nav_search),
            contentDescription = "Search",
            active = current is Screen.Search
        ) { onOpenSearch() }
        Spacer(Modifier.width(10.dp))
        IconNavButton(
            icon = androidx.compose.ui.res.painterResource(com.hans.aura.R.drawable.ic_nav_settings),
            contentDescription = "Settings",
            active = current is Screen.Settings
        ) { onOpenSettings() }
    }
}

/** Compact now-playing pill: art, title, artist. Opens the full Now Playing view. */
@Composable
private fun NowPlayingPill(player: PlayerConnection, current: Screen, onOpen: () -> Unit) {
    val item by player.current.collectAsState()
    val app = LocalContext.current.applicationContext as AuraApp
    val serverUrl = app.jellyfin.auth.session?.serverUrl ?: return
    val playing = item ?: return

    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .clip(shape)
            .background(if (focused) com.hans.aura.ui.theme.SurfaceHigh else Color(0xFF221D18))
            .then(
                if (focused) Modifier.border(
                    3.dp, com.hans.aura.ui.theme.Accent, shape
                ) else Modifier
            )
            .clickable { onOpen() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        playing.primaryImageTag?.let { tag ->
            AsyncImage(
                model = AuthStore.imageUrl(serverUrl, playing.id, tag, maxWidth = 160),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(44.dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Surface)
            )
        }
        Column(Modifier.padding(start = 12.dp)) {
            androidx.tv.material3.Text(
                playing.name,
                color = TextPrimary, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            androidx.tv.material3.Text(
                playing.artistNames.ifBlank { "" },
                color = TextSecondary, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Square icon button for Search / Settings in the top bar. */
@Composable
private fun IconNavButton(
    icon: androidx.compose.ui.graphics.painter.Painter,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .clip(shape)
            .background(
                when {
                    active -> Color(0x33E8A44C)
                    focused -> Color(0xFF3A322A)
                    else -> Color(0xFF221D18)
                }
            )
            .then(
                if (focused) Modifier.border(
                    3.dp, com.hans.aura.ui.theme.Accent, shape
                ) else Modifier
            )
            .clickable { onClick() }
            .padding(10.dp)
    ) {
        androidx.compose.material3.Icon(
            painter = icon,
            contentDescription = contentDescription,
            tint = if (active) com.hans.aura.ui.theme.Accent else TextPrimary,
            modifier = Modifier.width(22.dp).height(22.dp)
        )
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    // Loud, unambiguous states: selected = filled amber, focused = thick outline.
    val bg = when {
        selected && focused -> Color(0x66E8A44C)
        selected -> Color(0xFFE8A44C)
        focused -> Color(0xFF3A322A)
        else -> Color.Transparent
    }
    Box(
        Modifier
            .clip(shape)
            .background(bg)
            .then(
                if (focused) Modifier.border(
                    3.dp, if (selected) Color(0xFFF2EDE6) else com.hans.aura.ui.theme.Accent, shape
                ) else Modifier
            )
            .clickable { onClick() }
            .padding(horizontal = 22.dp, vertical = 12.dp)
    ) {
        androidx.tv.material3.Text(
            label,
            color = if (selected) Color(0xFF14100C) else TextPrimary,
            fontSize = 17.sp,
            fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold
            else androidx.compose.ui.text.font.FontWeight.Normal
        )
    }
}

@Composable
fun AppRoot() {
    val app = LocalContext.current.applicationContext as AuraApp
    var signedIn by remember { mutableStateOf(app.jellyfin.auth.isSignedIn) }

    if (!signedIn) {
        LoginScreen(app = app, onSignedIn = { signedIn = true })
        return
    }

    val player = remember { PlayerConnection(app, app.jellyfin) { app.jellyfin.auth.session } }
    val stack = remember { mutableStateOf(listOf<Screen>(Screen.Home)) }
    val top = stack.value.last()
    // Set when a screen is PUSHED so it claims focus on entry (TV has no
    // automatic initial focus for new content).
    var pendingFocus by remember { mutableStateOf(false) }
    val push: (Screen) -> Unit = {
        pendingFocus = true
        stack.value = stack.value + it
    }
    // Bumped on BACK so the revealed screen pulls focus back into its content.
    var focusTick by remember { mutableStateOf(0) }
    val pop: () -> Unit = {
        if (stack.value.size > 1) {
            stack.value = stack.value.dropLast(1)
            focusTick++
            pendingFocus = false
        }
    }

    // Per-screen-entry saveable state: scrolling/focus/text survive BACK.
    // Key includes the stack index + screen identity so a new push at the same
    // index never inherits an older screen's state.
    val stateHolder = rememberSaveableStateHolder()

    // System BACK pops our navigation stack (device back button / remote BACK).
    // On a root tab other than Home, BACK goes Home instead of exiting the app.
    androidx.activity.compose.BackHandler(enabled = stack.value.size > 1 || top != Screen.Home) {
        if (stack.value.size > 1) {
            pop()
        } else {
            pendingFocus = false
            stack.value = listOf(Screen.Home)
        }
    }

    // First playback of a session auto-opens Now Playing; BACK returns to
    // whatever screen started it. Later track changes don't re-push.
    var hadQueue by remember { mutableStateOf(false) }
    val currentForAutoShow by player.current.collectAsState()
    val queueForAutoShow by player.queue.collectAsState()
    LaunchedEffect(currentForAutoShow, queueForAutoShow) {
        if (currentForAutoShow == null) {
            hadQueue = false
        } else if (!hadQueue && queueForAutoShow.isNotEmpty()) {
            hadQueue = true
            push(Screen.NowPlaying)
        }
    }

    // Bumped by Settings > Refresh library; keys into data LaunchedEffects.
    var refreshKey by remember { mutableStateOf(0) }

    // Keep the screen awake while music plays (no screensaver mid-album).
    val isPlayingNow by player.isPlaying.collectAsState()
    val activity = LocalContext.current as? android.app.Activity
    LaunchedEffect(isPlayingNow) {
        activity?.window?.let { w ->
            if (isPlayingNow) {
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Canvas)) {
        Column(Modifier.fillMaxSize()) {
            // Now Playing is an immersive view (lyrics/transport fill the
            // screen); the top bar comes back on any other screen.
            if (top != Screen.NowPlaying) TopBar(
                current = top,
                onSelect = { tab ->
                    // Tab switch resets the stack to that tab.
                    if (tab::class != top::class) {
                        pendingFocus = false
                        stack.value = listOf(tab)
                    }
                },
                onOpenSearch = {
                    if (top != Screen.Search) {
                        pendingFocus = true
                        stack.value = listOf(Screen.Search)
                    }
                },
                onOpenSettings = {
                    if (top != Screen.Settings) {
                        pendingFocus = false
                        stack.value = listOf(Screen.Settings)
                    }
                },
                onOpenNowPlaying = {
                    if (top != Screen.NowPlaying) {
                        pendingFocus = true
                        stack.value = stack.value + Screen.NowPlaying
                    }
                },
                player = player
            )

            Box(Modifier.fillMaxSize()) {
                stateHolder.SaveableStateProvider(key = "${stack.value.lastIndex}:${Screen.key(top)}") {
                    when (top) {
                    Screen.Home -> HomeScreen(
                        app = app,
                        onSignOut = {
                            app.jellyfin.auth.clear()
                            signedIn = false
                        },
                        onOpenSearch = { push(Screen.Search) },
                        onOpenCollection = { push(Screen.Collection(it)) },
                        onOpenArtist = { push(Screen.Artist(it)) },
                        onPlayTrack = { _, queue, idx -> player.playQueue(queue, idx) },
                        refreshKey = refreshKey,
                        focusTick = focusTick
                    )

                    Screen.Artists -> LibraryScreen(
                        app = app, kind = "Artists", player = player,
                        refreshKey = refreshKey,
                        focusTick = focusTick,
                        onOpenCollection = { push(Screen.Collection(it)) },
                        onOpenArtist = { push(Screen.Artist(it)) }
                    )

                    Screen.Albums -> LibraryScreen(
                        app = app, kind = "Albums", player = player,
                        refreshKey = refreshKey,
                        focusTick = focusTick,
                        onOpenCollection = { push(Screen.Collection(it)) },
                        onOpenArtist = { push(Screen.Artist(it)) }
                    )

                    Screen.Tracks -> LibraryScreen(
                        app = app, kind = "Tracks", player = player,
                        refreshKey = refreshKey,
                        focusTick = focusTick,
                        onOpenCollection = { push(Screen.Collection(it)) },
                        onOpenArtist = { push(Screen.Artist(it)) }
                    )

                    Screen.Playlists -> LibraryScreen(
                        app = app, kind = "Playlists", player = player,
                        refreshKey = refreshKey,
                        focusTick = focusTick,
                        onOpenCollection = { push(Screen.Collection(it)) },
                        onOpenArtist = { push(Screen.Artist(it)) }
                    )

                    Screen.Settings -> SettingsScreen(
                        app = app,
                        onRefreshLibrary = {
                            LibraryCache.clear()
                            com.hans.aura.data.PagedLibrary.clear()
                            refreshKey++
                        },
                        onSignOut = {
                            app.jellyfin.auth.clear()
                            signedIn = false
                        }
                    )

                    Screen.Search -> SearchScreen(
                        app = app,
                        player = player,
                        onBack = pop,
                        onOpenCollection = { push(Screen.Collection(it)) },
                        onOpenArtist = { push(Screen.Artist(it)) }
                    )

                    Screen.NowPlaying -> NowPlayingScreen(
                        app = app,
                        player = player,
                        onBack = pop,
                        onOpenQueue = { push(Screen.Queue) }
                    )

                    Screen.Queue -> QueueScreen(player = player, onBack = pop, initialFocus = pendingFocus)

                    is Screen.Collection -> CollectionScreen(
                        app = app,
                        collection = top.item,
                        player = player,
                        onBack = pop,
                        focusTick = focusTick,
                        initialFocus = pendingFocus
                    )

                    is Screen.Artist -> ArtistScreen(
                        app = app,
                        artist = top.item,
                        player = player,
                        onBack = pop,
                        focusTick = focusTick,
                        initialFocus = pendingFocus,
                        onOpenCollection = { push(Screen.Collection(it)) }
                    )
                }
                }
            }
        }
    }
}

/** Compact now-playing pill helper lives above; bottom bar removed — the pill in the top bar replaces it. */
