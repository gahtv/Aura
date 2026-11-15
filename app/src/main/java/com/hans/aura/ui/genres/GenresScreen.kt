@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.hans.aura.ui.genres

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hans.aura.AuraApp
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.LibraryCache
import com.hans.aura.data.LibraryFilter
import com.hans.aura.data.Mixes
import com.hans.aura.ui.common.ArtworkCard
import com.hans.aura.ui.common.TrackRow
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.Accent
import com.hans.aura.ui.theme.FocusOutline
import com.hans.aura.ui.theme.Surface
import com.hans.aura.ui.theme.SurfaceHigh
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * Music genres, reached from the Genres pill inside the Albums tab (not a sixth
 * top-bar tab — five tabs + pill + two icon buttons already fill 960dp).
 *
 * The genre list comes from the library's own items: there is no
 * GET /MusicGenres collection endpoint in the 12.4.0 spec (only
 * /MusicGenres/{name} and /MusicGenres/{name}/InstantMix), so it is
 * `getItems(IncludeItemTypes=MusicGenre)` through LibraryFilter, exactly like
 * every other browse query, which keeps hidden libraries hidden.
 */
@Composable
fun GenresScreen(
    app: AuraApp,
    player: PlayerConnection,
    onBack: () -> Unit,
    onOpenGenre: (String) -> Unit,
    focusTick: Int = 0,
    initialFocus: Boolean = false
) {
    val session = app.jellyfin.auth.session!!
    var genres by remember { mutableStateOf(LibraryCache.get("genres:${session.userId}")) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (genres != null) return@LaunchedEffect
        runCatching {
            loadGenres(app.jellyfin.api, session.userId)
        }.onSuccess {
            LibraryCache.put("genres:${session.userId}", it)
            genres = it
        }.onFailure { error = it.message }
    }

    val contentFocus = remember { FocusRequester() }
    val focusKeeper = remember { com.hans.aura.ui.common.FocusKeeper() }
    var focusedId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    com.hans.aura.ui.common.RestoreFocusOnBack(focusTick, contentFocus, focusedId, focusKeeper)
    com.hans.aura.ui.common.InitialFocusOnPush(initialFocus, contentFocus)

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "Genres", onBack = onBack)
        when {
            error != null && genres == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                androidx.tv.material3.Text("Library error: $error", color = TextPrimary)
            }
            genres == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                androidx.tv.material3.Text("Loading\u2026", color = TextSecondary, fontSize = 16.sp)
            }
            genres!!.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                androidx.tv.material3.Text("No genres", color = TextSecondary, fontSize = 16.sp)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                state = rememberLazyGridState(),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp)
                    .focusRequester(contentFocus)
                    .focusRestorer(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(genres!!, key = { "genre_${it.id}" }) { genre ->
                    GenreTile(
                        name = genre.name,
                        focusKeeper = focusKeeper,
                        onFocused = { focusedId = it },
                        id = genre.id,
                        onClick = { onOpenGenre(genre.name) }
                    )
                }
                item {
                    Spacer(Modifier.height(140.dp))
                }
            }
        }
    }
}

/**
 * One genre: its albums grid plus "Play genre radio" (Mixes.genreMix, which
 * hits GET /MusicGenres/{name}/InstantMix). Falls back to a track list when a
 * genre has no albums of its own.
 */
@Composable
fun GenreScreen(
    app: AuraApp,
    player: PlayerConnection,
    genreName: String,
    onBack: () -> Unit,
    onOpenCollection: (JellyfinItem) -> Unit,
    focusTick: Int = 0,
    initialFocus: Boolean = false
) {
    val session = app.jellyfin.auth.session!!
    var albums by remember(genreName) { mutableStateOf(LibraryCache.get("genre_albums:$genreName")) }
    var tracks by remember(genreName) { mutableStateOf(LibraryCache.get("genre_tracks:$genreName")) }
    var error by remember { mutableStateOf<String?>(null) }
    var radioBusy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val current by player.current.collectAsState()
    // Atmos badges on the genre page cost no extra scan: the catalog is a
    // session-wide result already warmed by Home's "Your Atmos" shelf.
    val atmosAlbumIds = com.hans.aura.ui.common.rememberAtmosAlbumIds(app.jellyfin.api, session.userId)
    val focusKeeper = remember { com.hans.aura.ui.common.FocusKeeper() }
    var focusedId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val contentFocus = remember { FocusRequester() }
    val radioFocus = remember { FocusRequester() }

    LaunchedEffect(genreName) {
        if (albums != null && tracks != null) return@LaunchedEffect
        runCatching {
            if (albums == null) {
                LibraryFilter.queryItems(
                    app.jellyfin.api, session.userId,
                    LibraryFilter.ItemsQuery(
                        includeItemTypes = "MusicAlbum",
                        genres = genreName,
                        recursive = true,
                        sortBy = "SortName",
                        sortOrder = "Ascending",
                        limit = 200
                    )
                ).items.also {
                    LibraryCache.put("genre_albums:$genreName", it)
                    albums = it
                }
            }
            if (tracks == null) {
                LibraryFilter.queryItems(
                    app.jellyfin.api, session.userId,
                    LibraryFilter.ItemsQuery(
                        includeItemTypes = "Audio",
                        genres = genreName,
                        recursive = true,
                        sortBy = "SortName",
                        sortOrder = "Ascending",
                        limit = 100
                    )
                ).items.also {
                    LibraryCache.put("genre_tracks:$genreName", it)
                    tracks = it
                }
            }
        }.onFailure { error = it.message }
    }

    com.hans.aura.ui.common.RestoreFocusOnBack(focusTick, contentFocus, focusedId, focusKeeper)
    com.hans.aura.ui.common.InitialFocusOnPush(initialFocus, radioFocus)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                androidx.tv.material3.Text(genreName, fontSize = 34.sp, color = TextPrimary)
                androidx.tv.material3.Text(
                    listOfNotNull(
                        albums?.let { "${it.size} albums" },
                        tracks?.let { "${it.size} tracks" }
                    ).joinToString(" \u00B7 "),
                    fontSize = 13.sp, color = TextSecondary
                )
            }
            Spacer(Modifier.width(20.dp))
            RadioButton(
                label = if (radioBusy) "Starting\u2026" else "Play genre radio",
                modifier = Modifier.focusRequester(radioFocus),
                onClick = {
                    if (radioBusy) return@RadioButton
                    radioBusy = true
                    scope.launch {
                        Mixes.playMix(
                            api = app.jellyfin.api,
                            userId = session.userId,
                            player = player,
                            kind = Mixes.Kind.GENRE,
                            // The seed carries the genre NAME; the endpoint is
                            // /MusicGenres/{name}/InstantMix.
                            seed = JellyfinItem(id = "", name = genreName, type = "MusicGenre")
                        )
                        radioBusy = false
                    }
                }
            )
            Spacer(Modifier.width(12.dp))
            androidx.tv.material3.Button(onClick = onBack) {
                androidx.tv.material3.Text("Back")
            }
        }

        val albumList = albums ?: emptyList()
        when {
            error != null && albums == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                androidx.tv.material3.Text("Library error: $error", color = TextPrimary)
            }
            albums == null && tracks == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                androidx.tv.material3.Text("Loading\u2026", color = TextSecondary, fontSize = 16.sp)
            }
            albumList.isNotEmpty() -> LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                state = rememberLazyGridState(),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp)
                    .focusRequester(contentFocus)
                    .focusRestorer(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                items(albumList, key = { "galb_${it.id}" }) { album ->
                    ArtworkCard(
                        item = album,
                        serverUrl = session.serverUrl,
                        size = 210.dp,
                        badge = if (album.id in atmosAlbumIds) "ATMOS" else null,
                        focusKeeper = focusKeeper,
                        onFocused = { focusedId = it },
                        onClick = { onOpenCollection(album) }
                    )
                }
                item { Spacer(Modifier.height(140.dp)) }
            }
            else -> androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .focusRequester(contentFocus)
                    .focusRestorer()
            ) {
                itemsIndexed(tracks ?: emptyList()) { i, track ->
                    TrackRow(
                        index = i + 1,
                        track = track,
                        isCurrent = track.id == current?.id,
                        atmos = track.albumId?.let { it in atmosAlbumIds } == true,
                        onClick = { player.playQueue(tracks ?: listOf(track), i) },
                        focusKeeper = focusKeeper,
                        onFocused = { focusedId = it }
                    )
                }
                item { Spacer(Modifier.height(140.dp)) }
            }
        }
    }
}

/** Screen title row with a Back button; D-pad reachable like every other header. */
@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.tv.material3.Text(title, fontSize = 30.sp, color = TextPrimary, modifier = Modifier.weight(1f))
        androidx.tv.material3.Button(onClick = onBack) {
            androidx.tv.material3.Text("Back")
        }
    }
}

/** Genre tile: no artwork exists for genres, so it is a text plate. Focus is a
 *  thick amber ring + lifted fill so the cursor is never ambiguous. */
@Composable
private fun GenreTile(
    name: String,
    id: String,
    focusKeeper: com.hans.aura.ui.common.FocusKeeper,
    onFocused: (String) -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .height(96.dp)
            .fillMaxWidth()
            .focusRequester(focusKeeper.requesterFor(id))
            // onFocusChanged BEFORE clickable, or the flag never flips.
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused(id)
            }
            .clickable { onClick() }
            .clip(shape)
            .background(if (focused) SurfaceHigh else Surface)
            .then(if (focused) Modifier.border(3.dp, Accent, shape) else Modifier)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        androidx.tv.material3.Text(
            name,
            color = if (focused) TextPrimary else TextSecondary,
            fontSize = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** amber-filled action pill with a grey cursor ring when focused. */
@Composable
private fun RadioButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            // onFocusChanged BEFORE clickable (focus-ring ordering rule).
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .clip(shape)
            .background(if (focused) Color(0xCCE8A44C) else Accent)
            .border(3.dp, if (focused) FocusOutline else Color.Transparent, shape)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        androidx.tv.material3.Text(
            label,
            color = Color(0xFF191410),
            fontSize = 15.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
        )
    }
}

/**
 * Genres of the MUSIC libraries only.
 *
 * `getItems(IncludeItemTypes=MusicGenre)` is not a "music" item type for
 * LibraryFilter's per-library strategy (data/LibraryFilter.kt is owned
 * elsewhere), so an unscoped query also returns movie/TV genres — "Drama",
 * "Action" and friends would sit in the genre grid. Scoping each query to the
 * visible music libraries' roots keeps the list musical and keeps hidden
 * libraries hidden. With no known library list (first run, before /UserViews is
 * cached) it falls back to the single unfiltered query.
 */
private suspend fun loadGenres(
    api: com.hans.aura.data.JellyfinApi,
    userId: String
): List<JellyfinItem> {
    // Built from the genre strings the tracks actually carry, NOT from the
    // server's MusicGenre table: that table keeps every genre the library has
    // ever seen (264 entries here, most of them matching nothing), and the
    // `Genres=` filter compares the FULL string, so half its entries open an
    // empty screen. Scanning Audio costs a few pages once per cache TTL and
    // every entry it produces is guaranteed to have music behind it.
    val names = LinkedHashSet<String>()
    var start = 0
    var total = Int.MAX_VALUE
    var pages = 0
    while (start < total && pages < 12) {
        val result = runCatching {
            com.hans.aura.data.LibraryFilter.queryItems(
                api, userId,
                com.hans.aura.data.LibraryFilter.ItemsQuery(
                    includeItemTypes = "Audio",
                    recursive = true,
                    sortBy = "SortName",
                    sortOrder = "Ascending",
                    limit = 500,
                    startIndex = start,
                    fields = "Genres"
                )
            )
        }.getOrNull() ?: break
        result.items.forEach { item ->
            item.genres.orEmpty().forEach { g ->
                g.trim().takeIf { it.isNotBlank() }?.let(names::add)
            }
        }
        if (result.items.isEmpty()) break
        total = result.totalRecordCount
        start += result.items.size
        pages++
    }
    android.util.Log.i("AURA_GENRES", "scanned=$start distinct=${names.size} pages=$pages")
    return names.sortedBy { it.lowercase() }.map { name ->
        // A synthetic item: the grid only needs a stable id and a label, and
        // the real id would collide with a library item's.
        JellyfinItem(id = "genre:$name", name = name, type = "MusicGenre")
    }
}
