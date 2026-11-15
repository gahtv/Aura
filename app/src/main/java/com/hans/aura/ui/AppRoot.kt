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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.focus.onFocusChanged
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
    data object Genres : Screen
    data class Genre(val name: String) : Screen
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
            is Genres -> "genres"
            is Genre -> "genre:${s.name}"
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
        // Cap the pill width so a long title can't push Search/Settings
        // off the right edge of the top bar.
        Column(Modifier.padding(start = 12.dp).widthIn(max = 340.dp)) {
            // Shrink then marquee inside the cap, so a 60-character track name
            // can never widen the pill and push Search/Settings off screen.
            com.hans.aura.ui.common.AutoFitMarqueeText(
                text = playing.name,
                // Upper bound only; the row hands out whatever is left, so the
                // pill can never push Search/Settings off the top bar.
                widthCap = 200.dp,
                sizes = listOf(14.sp, 13.sp, 12.sp),
                color = TextPrimary
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
                    focused -> Color(0xFF3E362D)
                    else -> Color(0xFF221D18)
                }
            )
            .then(
                // Same grey cursor ring as the tabs, so the whole bar reads
                // consistently: grey = cursor, amber = current.
                if (focused) Modifier.border(2.dp, Color(0xFFD6CCBE), shape) else Modifier
            )
            .onFocusChanged { focused = it.isFocused }
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
    // An unfocused-vs-focused unselected tab used to differ only by a warm-brown
    // amber ring on a warm-brown bar, which read as "nothing is focused" — the
    // cursor now gets a light GREY ring plus a lifted fill so it separates from
    // both the bar and the amber selection.
    val bg = when {
        selected && focused -> Color(0x66E8A44C)
        selected -> Color(0xFFE8A44C)
        focused -> Color(0xFF3E362D)
        else -> Color.Transparent
    }
    Box(
        Modifier
            .clip(shape)
            .background(bg)
            .then(
                if (focused) Modifier.border(
                    2.dp, if (selected) Color(0xFFF2EDE6) else Color(0xFFD6CCBE), shape
                ) else Modifier
            )
            // Without this the `focused` flag never flips and NONE of the focus
            // styling below renders — the tabs showed only the amber selection.
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            // Tighter horizontal padding: five tabs + pill + two icon buttons
            // have to coexist inside 960dp on a 1080p TV.
            .padding(horizontal = 14.dp, vertical = 12.dp)
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
        // Debug deep link: --es aura_debug_screen quickconnect renders the
        // Quick Connect panel with a fake code (no network) so the layout can
        // be checked without a second Jellyfin client.
        val debugLink by com.hans.aura.ui.DebugNav.request.collectAsState()
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
                    signedIn = true
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
        return
    }

    val player = remember { PlayerConnection(app, app.jellyfin) { app.jellyfin.auth.session } }

    /**
     * Every way out of a session funnels through here (sign in again, change
     * server, sign out). Order: stop playback and disconnect the controller
     * FIRST — the service owns ExoPlayer, so a live controller keeps the old
     * server streaming under the login screen — then evict everything keyed by
     * its library data, then drop the credentials.
     *
     * Mixing two servers' item ids in one cache renders the wrong artwork and
     * the wrong tracklists, and PaletteCache is keyed by URL, so it survives
     * a server swap with colliding item ids unless it is cleared here too.
     */
    fun endSession(action: String, forgetServer: Boolean) {
        val oldUrl = app.jellyfin.auth.session?.serverUrl ?: app.jellyfin.auth.lastServerUrl
        val oldHost = com.hans.aura.data.AuthStore.host(oldUrl)
        val playerItems = player.teardown()
        val library = LibraryCache.size
        val paged = com.hans.aura.data.PagedLibrary.size
        val atmos = com.hans.aura.data.AtmosCatalog.size
        val palette = com.hans.aura.ui.theme.PaletteCache.size
        LibraryCache.clear()
        com.hans.aura.data.PagedLibrary.clear()
        com.hans.aura.data.AtmosCatalog.clear()
        com.hans.aura.ui.theme.PaletteCache.clear()
        if (forgetServer) app.jellyfin.auth.clearServer() else app.jellyfin.auth.clearSession()
        val evicted = "evicted library=$library paged=$paged atmos=$atmos " +
            "palette=$palette playerItems=$playerItems"
        android.util.Log.i(
            "AURA_AUTH",
            when (action) {
                "change server" ->
                    "change server: old=$oldHost -> cleared (prefill=none); $evicted"
                "sign in again" ->
                    "sign in again: kept server=$oldHost token=cleared " +
                        "(prefill=$oldHost); $evicted"
                else -> "$action: server=$oldHost token=cleared; $evicted"
            }
        )
        signedIn = false
    }

    // Mirror link also carries playback state and commands (Connect-style):
    // the remote never sees a keystroke, it sees the queue and controls it.
    LaunchedEffect(player) { MirrorLink.bindPlayer(player) }
    val mirrorQueue by player.queue.collectAsState()
    val mirrorQueueIndex by player.queueIndex.collectAsState()
    LaunchedEffect(mirrorQueue, mirrorQueueIndex) {
        MirrorLink.publishQueue(
            mirrorQueue.map { Triple(it.id, it.name, it.artistNames) },
            mirrorQueueIndex
        )
    }
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

    // Nav breadcrumb: logcat `adb logcat -s AURA_NAV` shows exactly which
    // screen is up, so adb-driven verification doesn't need screenshots.
    LaunchedEffect(stack.value) {
        android.util.Log.d(
            "AURA_NAV",
            "top=${Screen.key(top)} stack=${stack.value.joinToString(">") { Screen.key(it) }}"
        )
    }

    // Mirror link (ui/MirrorLink.kt): publish where we are so a remote can draw
    // its own native version of this screen, and honour the navigation it asks
    // for — remote opens album -> Aura opens the album.
    LaunchedEffect(top) {
        // Published names are the remote's vocabulary, not Screen.key()'s
        // internals ("col:<id>" would leak as "col").
        val (kind, itemId) = when (top) {
            is Screen.Collection -> "collection" to top.item.id
            is Screen.Artist -> "artist" to top.item.id
            is Screen.Home -> "home" to null
            is Screen.Artists -> "artists" to null
            is Screen.Albums -> "albums" to null
            is Screen.Tracks -> "tracks" to null
            is Screen.Playlists -> "playlists" to null
            is Screen.Search -> "search" to null
            is Screen.Settings -> "settings" to null
            is Screen.NowPlaying -> "nowplaying" to null
            is Screen.Queue -> "queue" to null
            // Genres are reached from the Albums tab and are the remote's
            // vocabulary only as far as "you are browsing albums": publish the
            // parent tab rather than inventing a screen kind senders don't know.
            is Screen.Genres -> "albums" to null
            is Screen.Genre -> "albums" to null
        }
        MirrorLink.publish(kind, itemId)
    }

    LaunchedEffect(Unit) {
        MirrorLink.nav.collect { nav ->
            val userId = app.jellyfin.auth.session?.userId
            val tab = when (nav.screen) {
                "home" -> Screen.Home
                "artists" -> Screen.Artists
                "albums" -> Screen.Albums
                "tracks" -> Screen.Tracks
                "playlists" -> Screen.Playlists
                "search" -> Screen.Search
                "settings" -> Screen.Settings
                else -> null
            }
            when {
                tab != null -> {
                    stack.value = if (tab == Screen.Home) listOf(tab) else listOf(Screen.Home, tab)
                    pendingFocus = true
                    focusTick++
                }
                nav.screen == "nowplaying" -> push(Screen.NowPlaying)
                nav.screen == "queue" -> push(Screen.Queue)
                (nav.screen == "collection" || nav.screen == "artist") && userId != null && nav.itemId != null -> {
                    runCatching { app.jellyfin.api.getItem(userId, nav.itemId!!) }
                        .onSuccess { item ->
                            stack.value = listOf(
                                Screen.Home,
                                if (nav.screen == "artist") Screen.Artist(item) else Screen.Collection(item)
                            )
                            pendingFocus = true
                            focusTick++
                        }
                        .onFailure { android.util.Log.w("AURA_MIRROR", "nav fetch failed: $it") }
                }
            }
        }
    }

    // Debug deep links (adb extras, see MainActivity): jump straight to a
    // screen; optional autoplay queues one random track first, which also
    // triggers the auto-push into Now Playing.
    LaunchedEffect(Unit) {
        DebugNav.request.collect { link ->
            if (link == null) return@collect
            DebugNav.request.value = null
            // Debug hook: exercise the session-exit paths (sign in again /
            // change server / sign out) without driving the Settings DPAD.
            // Same code path as the buttons, minus the confirm dialog.
            link.auth?.let { auth ->
                when (auth) {
                    "signinagain" -> endSession("sign in again", forgetServer = false)
                    "changeserver" -> endSession("change server", forgetServer = true)
                    "signout" -> endSession("sign out", forgetServer = true)
                    // "qcstart" belongs to the login screen's Quick Connect
                    // hook (LoginScreen reads it while signed out); reaching
                    // here means the app is signed in, so there is nothing to do.
                    "qcstart" -> Unit
                    else -> android.util.Log.w("AURA_AUTH", "unknown aura_debug_auth=$auth")
                }
                return@collect
            }
            if (link.autoplay) {
                runCatching {
                    val session = app.jellyfin.auth.session ?: return@collect
                    val result = com.hans.aura.data.LibraryFilter.queryItems(
                        app.jellyfin.api, session.userId,
                        com.hans.aura.data.LibraryFilter.ItemsQuery(
                            includeItemTypes = "Audio",
                            recursive = true, sortBy = "Random", limit = 1, startIndex = 0
                        )
                    )
                    if (result.items.isNotEmpty()) player.playQueue(result.items, 0)
                    android.util.Log.d(
                        "AURA_NAV",
                        "autoplay queued ${result.items.size} track(s): ${result.items.firstOrNull()?.name} id=${result.items.firstOrNull()?.id}"
                    )
                }.onFailure { android.util.Log.d("AURA_NAV", "autoplay failed: $it") }
            } else {
                val screen: Screen? = when (link.screen) {
                    "home" -> Screen.Home
                    "artists" -> Screen.Artists
                    "albums" -> Screen.Albums
                    "tracks" -> Screen.Tracks
                    "playlists" -> Screen.Playlists
                    "search" -> Screen.Search
                    "settings" -> Screen.Settings
                    "nowplaying" -> Screen.NowPlaying
                    "queue" -> Screen.Queue
                    else -> null
                }
                screen?.let {
                    pendingFocus = true
                    stack.value = if (it == Screen.Home) listOf(it) else listOf(Screen.Home, it)
                }
            }
            // Debug: open the long-press context menu for a specific item and
            // optionally auto-select one of its actions. Everything lands in
            // logcat (AURA_MENU / AURA_QUEUE) so tests need no screenshots.
            link.menuItem?.let { itemId ->
                kotlinx.coroutines.delay(1000)
                runCatching {
                    val session = app.jellyfin.auth.session ?: return@collect
                    val item = app.jellyfin.api.getItem(session.userId, itemId)
                    val spec = if (item.type == "Audio") {
                        com.hans.aura.ui.common.ContextMenus.forTrack(
                            app, player, this, item,
                            onOpenArtist = { push(Screen.Artist(it)) },
                            onOpenCollection = { push(Screen.Collection(it)) }
                        )
                    } else {
                        com.hans.aura.ui.common.ContextMenus.forAlbum(
                            app, player, this, item,
                            onOpenArtist = { push(Screen.Artist(it)) }
                        )
                    }
                    android.util.Log.d(
                        "AURA_MENU",
                        "open \"${spec.title}\" actions=${spec.actions.map { it.label }}"
                    )
                    com.hans.aura.ui.common.ContextMenuHost.open(spec)
                    if (link.menuAction >= 0) {
                        kotlinx.coroutines.delay(500)
                        val action = spec.actions.getOrNull(link.menuAction)
                        android.util.Log.d("AURA_MENU", "select[" + link.menuAction + "]=" + (action?.label ?: "OUT OF RANGE"))
                        action?.onSelect?.invoke()
                        kotlinx.coroutines.delay(2000)
                        android.util.Log.d(
                            "AURA_QUEUE",
                            "queue=${player.queue.value.map { it.name }} index=${player.queueIndex.value}"
                        )
                    }
                }.onFailure { android.util.Log.d("AURA_MENU", "failed: $it") }
            }
            // Debug: drive a queue mutation without DPAD keys, so reordering
            // and clear-upcoming are verifiable from AURA_QUEUE logcat alone.
            link.queueAction?.let { op ->
                kotlinx.coroutines.delay(1500) // queue mirror settled
                android.util.Log.d(
                    "AURA_QUEUE",
                    "debug op=$op before=${player.queue.value.map { it.id }} index=${player.queueIndex.value}"
                )
                when {
                    op == "clear" -> player.clearUpcoming()
                    op.startsWith("move:") -> {
                        val parts = op.removePrefix("move:").split(',')
                        val from = parts.getOrNull(0)?.trim()?.toIntOrNull()
                        val to = parts.getOrNull(1)?.trim()?.toIntOrNull()
                        if (from != null && to != null) player.moveInQueue(from, to)
                        else android.util.Log.d("AURA_QUEUE", "debug move: bad args \"$op\"")
                    }
                    // Headless hooks for the playback batch: sleep timer (in
                    // seconds, so a 5s test is possible) and smart shuffle.
                    op.startsWith("sleep:") -> {
                        val secs = op.removePrefix("sleep:").trim().toIntOrNull() ?: 0
                        if (secs == 0) player.cancelSleepTimer() else player.startSleepTimer(secs)
                    }
                    op == "smart" -> player.applySmartOrder()
                    op == "normalorder" -> player.restoreNormalOrder()
                    else -> android.util.Log.d("AURA_QUEUE", "debug op unknown: $op")
                }
                kotlinx.coroutines.delay(1500)
                android.util.Log.d(
                    "AURA_QUEUE",
                    "debug after=${player.queue.value.map { it.id }} index=${player.queueIndex.value}"
                )
            }
        }
    }

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

    // Explicit "show me what's playing" requests (e.g. tapping the track that is
    // already playing): push Now Playing instead of restarting the song.
    LaunchedEffect(Unit) {
        com.hans.aura.ui.common.NavRequests.openNowPlaying.collect {
            if (stack.value.last() !is Screen.NowPlaying) {
                pendingFocus = true
                stack.value = stack.value + Screen.NowPlaying
            }
        }
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
            // screen); the top bar comes back on any other screen. Any open
            // full-screen overlay also hides it so it can't steal focus.
            if (top != Screen.NowPlaying && !com.hans.aura.ui.common.OverlayVisibility.dialogOpen) TopBar(
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
                        player = player,
                        onSignOut = {
                            // Home's sign-out is the same exit path as Settings.
                            endSession("sign out", forgetServer = true)
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
                        onOpenArtist = { push(Screen.Artist(it)) },
                        onOpenGenres = { push(Screen.Genres) }
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
                        player = player,
                        onRefreshLibrary = {
                            LibraryCache.clear()
                            com.hans.aura.data.PagedLibrary.clear()
                            com.hans.aura.data.AtmosCatalog.clear()
                            LibraryCache.markManualRefresh()
                            refreshKey++
                        },
                        onSignInAgain = { endSession("sign in again", forgetServer = false) },
                        onChangeServer = { endSession("change server", forgetServer = true) },
                        onSignOut = { endSession("sign out", forgetServer = true) },
                        onLibrariesChanged = { refreshKey++ }
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
                        onOpenQueue = { push(Screen.Queue) },
                        onOpenAlbum = { push(Screen.Collection(it)) },
                        onOpenArtist = { push(Screen.Artist(it)) }
                    )

                    Screen.Queue -> QueueScreen(player = player, onBack = pop, initialFocus = pendingFocus)

                    Screen.Genres -> com.hans.aura.ui.genres.GenresScreen(
                        app = app,
                        player = player,
                        onBack = pop,
                        onOpenGenre = { push(Screen.Genre(it)) },
                        focusTick = focusTick,
                        initialFocus = pendingFocus
                    )

                    is Screen.Genre -> com.hans.aura.ui.genres.GenreScreen(
                        app = app,
                        player = player,
                        genreName = top.name,
                        onBack = pop,
                        onOpenCollection = { push(Screen.Collection(it)) },
                        focusTick = focusTick,
                        initialFocus = pendingFocus
                    )

                    is Screen.Collection -> CollectionScreen(
                        app = app,
                        collection = top.item,
                        player = player,
                        onBack = pop,
                        focusTick = focusTick,
                        initialFocus = pendingFocus,
                        onOpenArtist = { push(Screen.Artist(it)) }
                    )

                    is Screen.Artist -> ArtistScreen(
                        app = app,
                        artist = top.item,
                        player = player,
                        onBack = pop,
                        focusTick = focusTick,
                        initialFocus = pendingFocus,
                        onOpenCollection = { push(Screen.Collection(it)) },
                        onOpenArtist = { push(Screen.Artist(it)) }
                    )
                }
                }
            }
        }
        // Long-press context menus + their playlist picker, always on top.
        com.hans.aura.ui.common.ContextMenuHosts(app)

        // Pairing consent (USB-debugging style): drawn last so nothing can sit
        // over it, and it owns focus while it is up.
        MirrorPairingHost()
    }
}

/** Compact now-playing pill helper lives above; bottom bar removed — the pill in the top bar replaces it. */
