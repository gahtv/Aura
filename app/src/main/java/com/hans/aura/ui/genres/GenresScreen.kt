@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.hans.aura.ui.genres

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.MaterialTheme
import coil.compose.AsyncImage
import com.hans.aura.AuraApp
import com.hans.aura.data.AuthStore
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.Mixes
import com.hans.aura.ui.common.ArtworkCard
import com.hans.aura.ui.common.ErrorPane
import com.hans.aura.ui.common.TrackRow
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.Accent
import com.hans.aura.ui.theme.AuraMotion
import com.hans.aura.ui.theme.FocusOutline
import com.hans.aura.ui.theme.Surface
import com.hans.aura.ui.theme.SurfaceHigh
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary
import com.hans.aura.ui.theme.AuraShapes
import kotlinx.coroutines.launch

/**
 * Music genres, reached from the Genres pill inside the Albums tab (not a sixth
 * top-bar tab — five tabs + pill + two icon buttons already fill 960dp).
 *
 * The genre list comes from the library's own tracks: there is no
 * GET /MusicGenres collection endpoint in the 12.4.0 spec (only
 * /MusicGenres/{name} and /MusicGenres/{name}/InstantMix), so it is
 * `getItems(IncludeItemTypes=Audio, Fields=Genres)` through LibraryFilter,
 * exactly like every other browse query, which keeps hidden libraries hidden.
 */
@Composable
fun GenresScreen(
    app: AuraApp,
    player: PlayerConnection,
    onBack: () -> Unit,
    onOpenGenre: (String) -> Unit,
    focusTick: Int = 0,
    initialFocus: Boolean = false,
    viewModel: GenresViewModel = viewModel(
        key = "genres:${app.jellyfin.auth.session?.userId ?: "anon"}",
        factory = viewModelFactory { initializer { GenresViewModel(app) } }
    )
) {
    val session = app.jellyfin.auth.session ?: return
    val serverUrl = session.serverUrl
    val state by viewModel.state.collectAsState()

    val contentFocus = remember { FocusRequester() }
    val focusKeeper = remember { com.hans.aura.ui.common.FocusKeeper() }
    var focusedId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val contentReady = !state.genres.isNullOrEmpty()
    val emptyResult = !contentReady && (state.genres != null || state.error != null)
    com.hans.aura.ui.common.RestoreFocusOnBack(focusTick, contentFocus, focusedId, focusKeeper,
        ready = contentReady, cancelled = emptyResult)
    com.hans.aura.ui.common.InitialFocusOnPush(initialFocus, contentFocus,
        ready = contentReady, cancelled = emptyResult)

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "Genres", onBack = onBack)
        val genres = state.genres
        when {
            genres == null && state.error != null -> ErrorPane(
                message = "Couldn't load from server",
                detail = state.error,
                onRetry = { viewModel.retry() }
            )
            genres == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                androidx.tv.material3.Text("Loading\u2026", color = TextSecondary, fontSize = 16.sp)
            }
            genres.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
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
                items(genres, key = { "genre_${it.id}" }) { genre ->
                    val artUrl = genre.albumId?.let { aid ->
                        genre.albumPrimaryImageTag?.let { tag ->
                            AuthStore.imageUrl(serverUrl, aid, tag, maxWidth = 200)
                        }
                    }
                    GenreTile(
                        name = genre.name,
                        id = genre.id,
                        artworkUrl = artUrl,
                        focusKeeper = focusKeeper,
                        onFocused = { focusedId = it },
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
    initialFocus: Boolean = false,
    viewModel: GenreAlbumsViewModel = viewModel(
        key = "genre:$genreName:${app.jellyfin.auth.session?.userId ?: "anon"}",
        factory = viewModelFactory { initializer { GenreAlbumsViewModel(app, genreName) } }
    )
) {
    val session = app.jellyfin.auth.session ?: return
    val state by viewModel.state.collectAsState()
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

    com.hans.aura.ui.common.RestoreFocusOnBack(focusTick, contentFocus, focusedId, focusKeeper,
        ready = !state.loading && (state.visibleAlbums.isNotEmpty() || state.visibleTracks.isNotEmpty()),
        cancelled = (!state.loading || state.error != null) &&
            state.visibleAlbums.isEmpty() && state.visibleTracks.isEmpty())
    com.hans.aura.ui.common.InitialFocusOnPush(initialFocus, radioFocus)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                androidx.tv.material3.Text(
                    genreName, style = MaterialTheme.typography.displaySmall, color = TextPrimary
                )
                androidx.tv.material3.Text(
                    listOfNotNull(
                        state.albums?.let { com.hans.aura.ui.common.countLabel(it.size, "album") },
                        state.tracks?.let { com.hans.aura.ui.common.countLabel(it.size, "track") }
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

        val albumList = state.visibleAlbums
        val trackList = state.visibleTracks
        when {
            state.loading && state.error != null -> ErrorPane(
                message = "Couldn't load from server",
                detail = state.error,
                onRetry = { viewModel.retry() }
            )
            state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                androidx.tv.material3.Text("Loading\u2026", color = TextSecondary, fontSize = 16.sp)
            }
            albumList.isNotEmpty() -> LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                state = rememberLazyGridState(),
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(contentFocus)
                    .focusRestorer(),
                contentPadding = PaddingValues(
                    horizontal = 32.dp,
                    vertical = AuraMotion.artworkFocusInset(210.dp)
                ),
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
                itemsIndexed(trackList) { i, track ->
                    TrackRow(
                        index = i + 1,
                        track = track,
                        isCurrent = track.id == current?.id,
                        atmos = track.albumId?.let { it in atmosAlbumIds } == true,
                        onClick = { player.playQueue(trackList, i, resume = true) },
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
        androidx.tv.material3.Text(
            title, style = MaterialTheme.typography.headlineLarge, color = TextPrimary,
            modifier = Modifier.weight(1f)
        )
        androidx.tv.material3.Button(onClick = onBack) {
            androidx.tv.material3.Text("Back")
        }
    }
}

private val GenrePalette = listOf(
    Color(0xFF4A2545), // plum
    Color(0xFF1E3A5F), // deep navy
    Color(0xFF2E4A36), // forest
    Color(0xFF5C2B29), // crimson
    Color(0xFF3E3356), // indigo
    Color(0xFF5A3D1E), // amber-umber
    Color(0xFF1F4E5F), // teal
    Color(0xFF4C392B), // espresso
    Color(0xFF2D4356), // slate blue
    Color(0xFF543344), // berry
    Color(0xFF2B4C3F), // pine
    Color(0xFF4B3D1F)  // ochre
)

internal fun genreCardColor(name: String): Color =
    GenrePalette[(name.hashCode() and Int.MAX_VALUE) % GenrePalette.size]

/** Visual genre card: deterministic rich gradient + rotated cover thumbnail. */
@Composable
private fun GenreTile(
    name: String,
    id: String,
    artworkUrl: String?,
    focusKeeper: com.hans.aura.ui.common.FocusKeeper,
    onFocused: (String) -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.04f else 1f,
        animationSpec = AuraMotion.spatialFast,
        label = "genreScale"
    )
    val base = remember(name) { genreCardColor(name) }
    val shape = AuraShapes.cardHero
    Box(
        Modifier
            .height(128.dp)
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusRequester(focusKeeper.requesterFor(id))
            // onFocusChanged BEFORE clickable, or the flag never flips.
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused(id)
            }
            .clickable { onClick() }
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        if (focused) base.copy(alpha = 0.95f) else base,
                        Color(0xFF14110E)
                    )
                )
            )
            .then(if (focused) Modifier.border(3.dp, Accent, shape) else Modifier)
    ) {
        if (artworkUrl != null) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 10.dp, y = 10.dp)
                    .size(76.dp)
                    .graphicsLayer { rotationZ = 12f }
                    .clip(AuraShapes.card)
                    .background(SurfaceHigh)
            )
        }
        androidx.tv.material3.Text(
            text = name,
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 18.dp, top = 16.dp, end = 72.dp)
        )
    }
}

/** amber-filled action pill with a grey cursor ring when focused. */
@Composable
private fun RadioButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = AuraShapes.control
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
