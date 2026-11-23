package com.hans.aura.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.hans.aura.AuraApp
import com.hans.aura.ui.collection.ArtistScreen
import com.hans.aura.ui.collection.CollectionScreen
import com.hans.aura.ui.common.ContextMenuHosts
import com.hans.aura.ui.common.FocusHandoffHost
import com.hans.aura.ui.common.NavRequests
import com.hans.aura.ui.common.OverlayVisibility
import com.hans.aura.ui.common.LocalArtworkTransition
import com.hans.aura.ui.common.LocalArtworkVisibility
import com.hans.aura.ui.common.LocalDestinationActive
import com.hans.aura.ui.common.LocalArtworkSource
import com.hans.aura.ui.common.LocalArtworkCorners
import com.hans.aura.ui.common.ArtworkCorners
import com.hans.aura.ui.common.DestinationFocusGate
import com.hans.aura.ui.genres.GenreScreen
import com.hans.aura.ui.genres.GenresScreen
import com.hans.aura.ui.home.HomeScreen
import com.hans.aura.ui.library.LibraryScreen
import com.hans.aura.ui.login.LoginScreen
import com.hans.aura.ui.nav.NavSpec
import com.hans.aura.ui.nav.Screen
import com.hans.aura.ui.nav.rememberNavState
import com.hans.aura.ui.nowplaying.NowPlayingScreen
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.queue.QueueScreen
import com.hans.aura.ui.search.SearchScreen
import com.hans.aura.ui.settings.SettingsScreen
import com.hans.aura.ui.shell.DebugWiring
import com.hans.aura.ui.shell.MirrorWiring
import com.hans.aura.ui.shell.NavigationRailLayout
import com.hans.aura.ui.shell.RailDestination
import com.hans.aura.ui.shell.endSession
import com.hans.aura.ui.shell.rememberRailPlayback
import com.hans.aura.ui.shell.switchSession
import com.hans.aura.ui.theme.Canvas
import com.hans.aura.ui.theme.AuraMotion
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * App shell: auth gate, then the navigation rail + a single destination host over the
 * back stack in [com.hans.aura.ui.nav.NavState]. All navigation intents arrive
 * through [com.hans.aura.ui.nav.NavActions]; remote and debug wiring live in
 * ui/shell.
 */
@Composable
fun AppRoot() {
    val app = LocalContext.current.applicationContext as AuraApp
    var signedIn by remember { mutableStateOf(app.jellyfin.auth.isSignedIn) }
    var sessionGeneration by remember { mutableIntStateOf(0) }

    if (!signedIn) {
        SignedOutGate(app) { signedIn = true }
        return
    }

    key(sessionGeneration) {
    val player = remember { PlayerConnection(app, app.jellyfin) { app.jellyfin.auth.session } }
    val nav = rememberNavState()

    val onSignedOut: () -> Unit = { signedIn = false }
    val exitSession: (String, Boolean) -> Unit = { action, forgetServer ->
        endSession(app, player, action, forgetServer, onSignedOut)
    }
    val onSwitchProfile: (com.hans.aura.data.Session) -> Unit = { target ->
        switchSession(app, player, target) { sessionGeneration++ }
    }

    MirrorWiring(app = app, player = player, nav = nav)
    DebugWiring(app = app, player = player, nav = nav, endSession = exitSession)

    // System intents: Android TV Global Search + Home Screen Channel deep links.
    LaunchedEffect(Unit) {
        AppIntents.request.collect { req ->
            if (req == null) return@collect
            AppIntents.request.value = null
            when (req) {
                is AppIntentRequest.OpenSearch -> nav.openSearch()
                is AppIntentRequest.OpenItem -> {
                    if (req.type == "Home") {
                        nav.resetToHome()
                        return@collect
                    }
                    val uid = app.jellyfin.auth.session?.userId ?: return@collect
                    runCatching { app.jellyfin.api.getItem(itemId = req.itemId, userId = uid) }
                        .onSuccess { item ->
                            when (item.type) {
                                "MusicArtist" -> nav.jumpTo(Screen.Artist(item), bumpFocus = true)
                                "Audio" -> player.playQueue(listOf(item), 0, resume = false)
                                else -> nav.jumpTo(Screen.Collection(item), bumpFocus = true)
                            }
                        }
                        .onFailure { android.util.Log.w("AURA_NAV", "intent open failed: ${it.message}") }
                }
            }
        }
    }

    // Nav breadcrumb: logcat `adb logcat -s AURA_NAV` shows exactly which
    // screen is up, so adb-driven verification doesn't need screenshots.
    LaunchedEffect(nav.stack) {
        android.util.Log.d(
            "AURA_NAV",
            "top=${NavSpec.key(nav.top)} stack=${nav.stack.joinToString(">") { NavSpec.key(it) }}"
        )
    }

    // System BACK pops our navigation stack (device back button / remote BACK).
    // On a root tab other than Home, BACK goes Home instead of exiting the app.
    BackHandler(enabled = nav.stack.size > 1 || nav.top != Screen.Home, onBack = nav::back)

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
            nav.push(Screen.NowPlaying)
        }
    }

    // Explicit "show me what's playing" requests (e.g. tapping the track that is
    // already playing): push Now Playing instead of restarting the song.
    // drop(1) skips the replayed initial value (0) — a StateFlow collector fires
    // immediately, which pushed Now Playing on every cold start with nothing playing.
    LaunchedEffect(Unit) {
        NavRequests.openNowPlaying.drop(1).collect { nav.openNowPlaying() }
    }

    // Bumped by Settings > Refresh library; keys into data LaunchedEffects.
    var refreshKey by remember { mutableStateOf(0) }

    // Library selection: refresh the known /UserViews list once per launch when
    // something is hidden. A library added on the server must be visible by
    // default, not silently filtered out by a stale "known libraries" list.
    LaunchedEffect(Unit) {
        if (com.hans.aura.data.LibraryFilter.hidden().isNotEmpty()) {
            val session = app.jellyfin.auth.session ?: return@LaunchedEffect
            runCatching {
                com.hans.aura.data.LibraryFilter.refreshLibraryList(app.jellyfin.api, session.userId)
            }.onFailure { android.util.Log.w("AURA_LIBS", "library list refresh failed: ${it.message}") }
        }
    }

    // Keep the screen awake while music plays (no screensaver mid-album).
    KeepScreenOnWhilePlaying(player)

    // Per-screen-entry saveable state: scrolling/focus/text survive BACK. Key
    // includes the stack index + screen identity so a new push at the same
    // index never inherits an older screen's state.
    val stateHolder = rememberSaveableStateHolder()

    FocusHandoffHost(Modifier.fillMaxSize().background(Canvas)) {
        val showRail = nav.top != Screen.NowPlaying && !OverlayVisibility.dialogOpen
        NavigationRailLayout(
            showRail = showRail,
            selected = RailDestination.forStack(nav.stack),
            playback = if (showRail) rememberRailPlayback(player, app.jellyfin.auth.session?.serverUrl) else null,
            onSelect = { destination ->
                when (destination) {
                    RailDestination.Search -> nav.openSearch()
                    RailDestination.NowPlaying -> nav.openNowPlaying()
                    else -> nav.selectTab(destination.screen)
                }
            }
        ) {
            SharedTransitionLayout {
                val artworkCorners = remember { mutableMapOf<String, ArtworkCorners>() }
                AnimatedContent(
                    targetState = nav.stack.lastIndex to nav.top,
                    contentKey = { (index, screen) -> "$index:${NavSpec.key(screen)}" },
                    transitionSpec = {
                        fadeIn(AuraMotion.scheme.fastEffectsSpec()) togetherWith
                            fadeOut(AuraMotion.scheme.fastEffectsSpec())
                    },
                    label = "destination"
                ) { (index, screen) ->
                    val active = index == nav.stack.lastIndex && screen == nav.top
                    var entryFocusTick by remember { mutableIntStateOf(nav.focusTick) }
                    var entryPendingFocus by remember { mutableStateOf(nav.pendingFocus) }
                    if (active) {
                        entryFocusTick = nav.focusTick
                        entryPendingFocus = nav.pendingFocus
                    }
                    stateHolder.SaveableStateProvider(key = "$index:${NavSpec.key(screen)}") {
                        val artworkSource = rememberSaveable { mutableStateOf<Long?>(null) }
                        CompositionLocalProvider(
                            LocalArtworkTransition provides this@SharedTransitionLayout,
                            LocalArtworkVisibility provides this@AnimatedContent,
                            LocalDestinationActive provides active,
                            LocalArtworkSource provides artworkSource,
                            LocalArtworkCorners provides artworkCorners
                        ) {
                            DestinationFocusGate(active) {
                                DestinationHost(
                                    app = app,
                                    player = player,
                                    nav = nav,
                                    top = screen,
                                    focusTick = entryFocusTick,
                                    pendingFocus = entryPendingFocus,
                                    endSession = exitSession,
                                    onSwitchProfile = onSwitchProfile,
                                    refreshKey = refreshKey,
                                    onRefreshKey = { refreshKey++ }
                                )
                            }
                        }
                    }
                }
            }
        }
        // Long-press context menus + their playlist picker, always on top.
        ContextMenuHosts(app)

        // Pairing consent (USB-debugging style): drawn last so nothing can sit
        // over it, and it owns focus while it is up.
        MirrorPairingHost()
    }
    }
}

/** Signed-out branch: login screen (with debug Quick Connect hooks) + pairing. */
@Composable
private fun SignedOutGate(app: AuraApp, onSignedIn: () -> Unit) {
    // Debug deep link: --es aura_debug_screen quickconnect renders the
    // Quick Connect panel with a fake code (no network) so the layout can
    // be checked without a second Jellyfin client.
    val debugLink by DebugNav.request.collectAsState()
    Box(Modifier.fillMaxSize()) {
        // "Sign in again" leaves the server address behind, so the login
        // screen comes back with the URL already filled in; "change server"
        // clears it and this is null.
        LoginScreen(
            app = app,
            initialServerUrl = app.jellyfin.auth.lastServerUrl,
            debugDemoCode = if (debugLink?.screen == "quickconnect" && debugLink?.auth != "qcstart") "123456" else null,
            debugStartQuickConnect = debugLink?.screen == "quickconnect" && debugLink?.auth == "qcstart",
            onSignedIn = {
                onSignedIn()
                val s = app.jellyfin.auth.session
                android.util.Log.i(
                    "AURA_AUTH",
                    "signed in: server=${com.hans.aura.data.AuthStore.host(s?.serverUrl)} " +
                        "user=${s?.userName}"
                )
            }
        )
        // A remote can pair before anyone signs in on the TV.
        MirrorPairingHost()
    }
}

/** The one place a [Screen] turns into a screen composable. */
@Composable
private fun DestinationHost(
    app: AuraApp,
    player: PlayerConnection,
    nav: com.hans.aura.ui.nav.NavState,
    top: Screen,
    focusTick: Int,
    pendingFocus: Boolean,
    endSession: (String, Boolean) -> Unit,
    onSwitchProfile: (com.hans.aura.data.Session) -> Unit,
    refreshKey: Int,
    onRefreshKey: () -> Unit
) {
    val actions = nav.actions
    val scope = rememberCoroutineScope()
    when (top) {
        Screen.Home -> HomeScreen(
            app = app,
            player = player,
            onSignOut = { endSession("sign out", true) },
            onOpenSearch = actions::onOpenSearch,
            onOpenCollection = actions::onOpenCollection,
            onOpenArtist = actions::onOpenArtist,
            onPlayTrack = { _, queue, idx -> player.playQueue(queue, idx, resume = true) },
            refreshKey = refreshKey,
            focusTick = focusTick
        )

        Screen.Artists, Screen.Albums, Screen.Tracks, Screen.Playlists, Screen.Liked -> LibraryScreen(
            app = app, kind = requireNotNull(NavSpec.libraryKind(top)), player = player,
            refreshKey = refreshKey,
            focusTick = focusTick,
            onOpenCollection = actions::onOpenCollection,
            onOpenArtist = actions::onOpenArtist,
            onSwitchCategory = actions::onSwitchLibraryCategory,
            likedOnly = top == Screen.Liked
        )

        Screen.Settings -> SettingsScreen(
            app = app,
            player = player,
            onRefreshLibrary = {
                scope.launch {
                    app.container.source.refreshAll()
                    onRefreshKey()
                }
            },
            onSignInAgain = { endSession("sign in again", false) },
            onChangeServer = { endSession("change server", true) },
            onSignOut = { endSession("sign out", true) },
            onLibrariesChanged = onRefreshKey,
            onSwitchProfile = onSwitchProfile
        )

        Screen.Search -> SearchScreen(
            app = app,
            player = player,
            onBack = actions::onBack,
            onOpenCollection = actions::onOpenCollection,
            onOpenArtist = actions::onOpenArtist,
            // A catalog import just landed in the library: same key Settings uses.
            onLibraryChanged = onRefreshKey
        )

        Screen.NowPlaying -> NowPlayingScreen(
            app = app,
            player = player,
            onBack = actions::onBack,
            onOpenQueue = actions::onOpenQueue,
            onOpenAlbum = actions::onOpenCollection,
            onOpenArtist = actions::onOpenArtist
        )

        Screen.Queue -> QueueScreen(player = player, onBack = actions::onBack, initialFocus = pendingFocus)

        Screen.Genres -> GenresScreen(
            app = app,
            player = player,
            onBack = actions::onBack,
            onOpenGenre = actions::onOpenGenre,
            focusTick = focusTick,
            initialFocus = pendingFocus
        )

        is Screen.Genre -> GenreScreen(
            app = app,
            player = player,
            genreName = top.name,
            onBack = actions::onBack,
            onOpenCollection = actions::onOpenCollection,
            focusTick = focusTick,
            initialFocus = pendingFocus
        )

        is Screen.Collection -> CollectionScreen(
            app = app,
            collection = top.item,
            player = player,
            onBack = actions::onBack,
            focusTick = focusTick,
            initialFocus = pendingFocus,
            onOpenArtist = actions::onOpenArtist
        )

        is Screen.Artist -> ArtistScreen(
            app = app,
            artist = top.item,
            player = player,
            onBack = actions::onBack,
            focusTick = focusTick,
            initialFocus = pendingFocus,
            onOpenCollection = actions::onOpenCollection,
            onOpenArtist = actions::onOpenArtist
        )
    }
}

/** Keeps the display awake while audio is playing (no screensaver mid-album). */
@Composable
private fun KeepScreenOnWhilePlaying(player: PlayerConnection) {
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
}
