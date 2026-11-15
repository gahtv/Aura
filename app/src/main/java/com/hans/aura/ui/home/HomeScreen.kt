@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.hans.aura.ui.home

import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hans.aura.AuraApp
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.LibraryCache
import com.hans.aura.data.LibraryFilter
import com.hans.aura.ui.common.ArtworkCard
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary

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
    focusTick: Int = 0
) {
    val session = app.jellyfin.auth.session!!
    val userId = session.userId
    val serverUrl = session.serverUrl
    val scope = androidx.compose.runtime.rememberCoroutineScope()

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

    var recentlyPlayed by remember(refreshKey) { mutableStateOf(LibraryCache.get("home_recent:$userId")) }
    var recentAlbums by remember(refreshKey) { mutableStateOf(LibraryCache.get("home_new:$userId")) }
    // Discovery shelves (24h LibraryCache like every other Home shelf):
    //  - artists sorted by DatePlayed (MusicArtist supports that sort)
    //  - albums already known to be Atmos, from the session-cached AtmosCatalog
    var recentArtists by remember(refreshKey) { mutableStateOf(LibraryCache.get("home_recent_artists:$userId")) }
    var atmosAlbums by remember(refreshKey) { mutableStateOf(LibraryCache.get("home_atmos:$userId")) }
    var artists by remember(refreshKey) { mutableStateOf(LibraryCache.get("home_artists:$userId")) }
    var playlists by remember(refreshKey) { mutableStateOf(LibraryCache.get("home_playlists:$userId")) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshKey) {
        if (recentlyPlayed != null && recentAlbums != null && recentArtists != null &&
            atmosAlbums != null && artists != null && playlists != null
        ) {
            return@LaunchedEffect // fully cached
        }
        runCatching {
            val api = app.jellyfin.api
            // Browse queries go through LibraryFilter: hidden libraries are
            // never fetched, so their items cannot land in LibraryCache.
            if (recentlyPlayed == null) {
                LibraryFilter.queryItems(
                    api, userId,
                    LibraryFilter.ItemsQuery(
                        includeItemTypes = "Audio", recursive = true,
                        sortBy = "DatePlayed", sortOrder = "Descending", limit = 20
                    )
                ).items.also {
                    LibraryCache.put("home_recent:$userId", it)
                    recentlyPlayed = it
                }
            }
            if (recentAlbums == null) {
                LibraryFilter.queryItems(
                    api, userId,
                    LibraryFilter.ItemsQuery(
                        includeItemTypes = "MusicAlbum", recursive = true,
                        sortBy = "DateCreated", sortOrder = "Descending", limit = 20
                    )
                ).items.also {
                    LibraryCache.put("home_new:$userId", it)
                    recentAlbums = it
                }
            }
            if (recentArtists == null) {
                LibraryFilter.queryItems(
                    api, userId,
                    LibraryFilter.ItemsQuery(
                        includeItemTypes = "MusicArtist", recursive = true,
                        sortBy = "DatePlayed", sortOrder = "Descending", limit = 20
                    )
                ).items.also {
                    LibraryCache.put("home_recent_artists:$userId", it)
                    recentArtists = it
                }
            }
            if (atmosAlbums == null) {
                // Reuse the session-cached AtmosCatalog — never a second
                // whole-library scan. Its ids are matched against the already
                // fetched "Recently added" page first (zero extra queries); only
                // when that page holds no Atmos album do we page the album list.
                val atmosIds = runCatching {
                    com.hans.aura.data.AtmosCatalog.albumIds(api, userId)
                }.getOrDefault(emptySet())
                var picks = recentAlbums.orEmpty().filter { it.id in atmosIds }.take(20)
                if (picks.isEmpty() && atmosIds.isNotEmpty()) {
                    picks = LibraryFilter.queryItems(
                        api, userId,
                        LibraryFilter.ItemsQuery(
                            includeItemTypes = "MusicAlbum", recursive = true,
                            sortBy = "DateCreated", sortOrder = "Descending", limit = 120
                        )
                    ).items.filter { it.id in atmosIds }.take(20)
                }
                LibraryCache.put("home_atmos:$userId", picks)
                atmosAlbums = picks
            }
            if (artists == null) {
                LibraryFilter.queryAlbumArtists(api, userId, limit = 100).items.also {
                    LibraryCache.put("home_artists:$userId", it)
                    artists = it
                }
            }
            if (playlists == null) {
                api.getItems(
                    userId = userId, includeItemTypes = "Playlist", recursive = true,
                    sortBy = "SortName", limit = 50
                ).items.also {
                    LibraryCache.put("home_playlists:$userId", it)
                    playlists = it
                }
            }
        }.onFailure { error = it.message }
    }

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
        if (error != null && recentlyPlayed == null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                androidx.tv.material3.Text("Library error: $error", color = TextPrimary)
            }
            return@Column
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            com.hans.aura.ui.common.ItemShelf(
                title = "Recently played",
                items = recentlyPlayed,
                serverUrl = serverUrl,
                focusKeeper = focusKeeper,
                onFocused = { focusedCardId = it },
                onLongPress = { trackMenu(it) },
                onOpen = { item, idx ->
                    // A track is not a collection: play it directly, with the rest
                    // of the recently-played list queued behind it.
                    onPlayTrack(item, recentlyPlayed ?: listOf(item), idx)
                }
            )
            com.hans.aura.ui.common.ItemShelf(
                title = "Recently added",
                items = recentAlbums,
                serverUrl = serverUrl,
                focusKeeper = focusKeeper,
                onFocused = { focusedCardId = it },
                onLongPress = { albumMenu(it) },
                onOpen = { item, _ -> onOpenCollection(item) }
            )
            com.hans.aura.ui.common.ItemShelf(
                title = "Recently played artists",
                items = recentArtists,
                serverUrl = serverUrl,
                focusKeeper = focusKeeper,
                onFocused = { focusedCardId = it },
                onOpen = { item, _ -> onOpenArtist(item) }
            )
            com.hans.aura.ui.common.ItemShelf(
                title = "Your Atmos",
                items = atmosAlbums,
                serverUrl = serverUrl,
                focusKeeper = focusKeeper,
                onFocused = { focusedCardId = it },
                badgeFor = { "ATMOS" },
                onLongPress = { albumMenu(it) },
                onOpen = { item, _ -> onOpenCollection(item) }
            )
            com.hans.aura.ui.common.ItemShelf(
                title = "Artists",
                items = artists,
                serverUrl = serverUrl,
                focusKeeper = focusKeeper,
                onFocused = { focusedCardId = it },
                onOpen = { item, _ -> onOpenArtist(item) }
            )
            com.hans.aura.ui.common.ItemShelf(
                title = "Playlists",
                items = playlists,
                serverUrl = serverUrl,
                focusKeeper = focusKeeper,
                onFocused = { focusedCardId = it },
                onLongPress = { albumMenu(it) },
                onOpen = { item, _ -> onOpenCollection(item) }
            )
            Spacer(Modifier.height(140.dp)) // clear of the now-playing bar
        }
    }
}

