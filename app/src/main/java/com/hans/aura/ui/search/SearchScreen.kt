package com.hans.aura.ui.search

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.hans.aura.AuraApp
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.Prefs
import com.hans.aura.ui.common.FocusKeeper
import com.hans.aura.ui.common.ItemShelf
import com.hans.aura.ui.common.contextMenuKeys
import com.hans.aura.ui.common.rememberAtmosAlbumIds
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.Accent
import com.hans.aura.ui.theme.Border
import com.hans.aura.ui.theme.SurfaceHigh
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary
import kotlinx.coroutines.delay

// Search results render as home-style horizontal shelves (Artists / Albums /
// Tracks) stacked on one scrollable page — no tabs, all sections at once.
// The header and text field keep a 56dp inset; the shelf rows themselves
// bleed edge-to-edge (their inset lives inside the scroll row).
@Composable
fun SearchScreen(
    app: AuraApp,
    player: PlayerConnection,
    onBack: () -> Unit,
    onOpenCollection: (JellyfinItem) -> Unit,
    onOpenArtist: (JellyfinItem) -> Unit
) {
    val session = app.jellyfin.auth.session!!
    var query by rememberSaveable { mutableStateOf("") }
    var tracks by remember { mutableStateOf<List<JellyfinItem>?>(null) }
    var albums by remember { mutableStateOf<List<JellyfinItem>?>(null) }
    var artists by remember { mutableStateOf<List<JellyfinItem>?>(null) }
    var searching by remember { mutableStateOf(false) }
    // Bumped on every submit so ENTER re-runs a search whose query did not
    // change (live-as-you-type already covers the changing case).
    var searchTick by remember { mutableIntStateOf(0) }
    // Bumped when focus has to be placed back in the field (after a chip tap
    // makes the chip row disappear).
    var focusFieldTick by remember { mutableIntStateOf(0) }
    var recentSearches by remember { mutableStateOf(Prefs.recentSearches()) }
    var voiceMessage by remember { mutableStateOf<String?>(null) }
    val current by player.current.collectAsState()
    val atmosAlbumIds = rememberAtmosAlbumIds(app.jellyfin.api, session.userId)
    val focusKeeper = remember { FocusKeeper() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Debug hook: the parent verifies chip state from logcat.
    LaunchedEffect(recentSearches) {
        Log.d("AURA_SEARCH", "history=${recentSearches.size}")
    }

    /** Submit [raw]: record history, log the AURA_SEARCH hook, re-run. */
    fun submit(raw: String) {
        val q = raw.trim()
        if (q.length >= 2) recentSearches = Prefs.addRecentSearch(q)
        Log.d("AURA_SEARCH", "query=$q recent=${recentSearches.size}")
        searchTick++
    }

    // Voice search: a TV without a speech service returns RESULT_CANCELED, so
    // both "no recogniser" and "cancelled" land here and only ever log +
    // show a one-line note. No busy state is entered, nothing can crash.
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.trim()
        } else null
        if (!spoken.isNullOrBlank()) {
            query = spoken
            focusFieldTick++
            submit(spoken)
        } else {
            Log.d("AURA_SEARCH", "voice=unavailable")
            voiceMessage = "Voice search unavailable on this device"
        }
    }

    fun startVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to search")
        }
        // Feature detection: resolve first (emulators have no speech service),
        // then still guard launch, because resolution can succeed on packages
        // that fail to start.
        val resolvable = runCatching {
            context.packageManager.resolveActivity(intent, 0)
        }.getOrNull()
        if (resolvable == null) {
            Log.d("AURA_SEARCH", "voice=unavailable")
            voiceMessage = "Voice search unavailable on this device"
            return
        }
        runCatching { voiceLauncher.launch(intent) }.onFailure { e ->
            Log.d("AURA_SEARCH", "voice=unavailable (${e::class.java.simpleName})")
            voiceMessage = "Voice search unavailable on this device"
        }
    }

    // The one-line note clears itself so the screen never sits in a state.
    LaunchedEffect(voiceMessage) {
        if (voiceMessage != null) {
            delay(4000)
            voiceMessage = null
        }
    }

    LaunchedEffect(query, searchTick) {
        val q = query.trim()
        if (q.length < 2) { tracks = emptyList(); albums = emptyList(); artists = emptyList(); return@LaunchedEffect }
        searching = true
        runCatching {
            val api = app.jellyfin.api
            // Search is a browse query: hidden libraries are not queried.
            val result = com.hans.aura.data.LibraryFilter.queryItems(
                api, session.userId,
                com.hans.aura.data.LibraryFilter.ItemsQuery(
                    searchTerm = q, recursive = true,
                    includeItemTypes = "Audio,MusicAlbum,MusicArtist,Playlist",
                    limit = 50
                )
            )
            tracks = result.items.filter { it.type == "Audio" }
            albums = result.items.filter { it.type == "MusicAlbum" || it.type == "Playlist" }
            artists = result.items.filter { it.type == "MusicArtist" }
        }.onFailure {
            tracks = emptyList(); albums = emptyList(); artists = emptyList()
        }
        searching = false
    }

    Column(Modifier.fillMaxSize().padding(vertical = 32.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Search", fontSize = 32.sp, color = TextPrimary)
            Spacer(Modifier.weight(1f))
            Button(onClick = onBack) { Text("Back") }
        }
        Spacer(Modifier.height(16.dp))

        val searchFieldFocus = remember { FocusRequester() }
        val recentFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            var tries = 0
            while (tries < 10) {
                try { searchFieldFocus.requestFocus(); break } catch (_: Exception) { }
                delay(100)
                tries++
            }
        }
        // Return focus to the field after a chip tap / voice result removed the
        // focused chip; a submit must never leave the screen focus-less.
        LaunchedEffect(focusFieldTick) {
            if (focusFieldTick == 0) return@LaunchedEffect
            delay(80)
            repeat(5) {
                if (runCatching { searchFieldFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                delay(80)
            }
        }

        val showRecent = query.isBlank() && recentSearches.isNotEmpty()

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { androidx.compose.material3.Text("Artists, albums, tracks, playlists") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { submit(query) },
                    onDone = { submit(query) }
                ),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(searchFieldFocus)
                    // A focused material3 TextField eats D-pad arrows for caret
                    // movement before focus search runs (same trap as
                    // PlaylistDialogs' name field), so route DOWN to the recent
                    // chips here; ENTER/OK submits while the field keeps focus.
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionDown -> if (showRecent) {
                                recentFocus.requestFocus()
                                true
                            } else false
                            Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                                submit(query)
                                true
                            }
                            else -> false
                        }
                    }
            )
            Spacer(Modifier.width(16.dp))
            VoiceSearchButton(onClick = { startVoiceSearch() })
        }

        voiceMessage?.let { msg ->
            Text(
                msg, color = TextSecondary, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 56.dp, vertical = 6.dp)
            )
        }
        Spacer(Modifier.height(8.dp))

        // Recent searches: only while the field is empty, so the chips never
        // compete with live results. OK re-runs, long-press removes.
        if (showRecent) {
            Text(
                "Recent searches", fontSize = 13.sp, color = TextSecondary,
                modifier = Modifier.padding(horizontal = 56.dp)
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Spacer(Modifier.width(56.dp))
                recentSearches.forEachIndexed { idx, term ->
                    RecentQueryChip(
                        text = term,
                        firstFocus = if (idx == 0) recentFocus else null,
                        onRun = {
                            query = term
                            submit(term)
                            focusFieldTick++
                        },
                        onRemove = {
                            recentSearches = Prefs.removeRecentSearch(term)
                            focusFieldTick++
                        }
                    )
                }
                Spacer(Modifier.width(56.dp))
            }
            Text(
                "Hold OK to remove a recent search", fontSize = 12.sp, color = TextSecondary,
                modifier = Modifier.padding(horizontal = 56.dp, vertical = 6.dp)
            )
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            val q = query.trim()
            if (q.length >= 2 && !searching &&
                tracks?.isEmpty() == true && albums?.isEmpty() == true && artists?.isEmpty() == true
            ) {
                Text(
                    "No results for \u201C$q\u201D", color = TextSecondary, fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 56.dp, vertical = 8.dp)
                )
            }

            ItemShelf(
                title = "Artists",
                items = artists,
                serverUrl = session.serverUrl,
                focusKeeper = focusKeeper,
                onFocused = { },
                onOpen = { item, _ -> onOpenArtist(item) }
            )

            ItemShelf(
                title = "Albums & playlists",
                items = albums,
                serverUrl = session.serverUrl,
                focusKeeper = focusKeeper,
                onFocused = { },
                badgeFor = { if (it.type == "MusicAlbum" && it.id in atmosAlbumIds) "ATMOS" else null },
                onLongPress = { item ->
                    if (item.type == "MusicAlbum" || item.type == "Playlist") {
                        com.hans.aura.ui.common.ContextMenuHost.open(
                            com.hans.aura.ui.common.ContextMenus.forAlbum(
                                app, player, scope, item, onOpenArtist = onOpenArtist
                            )
                        )
                    }
                },
                onOpen = { item, _ -> onOpenCollection(item) }
            )

            ItemShelf(
                title = "Tracks",
                items = tracks,
                serverUrl = session.serverUrl,
                focusKeeper = focusKeeper,
                onFocused = { },
                showLoading = false,
                badgeFor = { track ->
                    if (track.albumId != null && track.albumId in atmosAlbumIds) "ATMOS" else null
                },
                onLongPress = { item ->
                    com.hans.aura.ui.common.ContextMenuHost.open(
                        com.hans.aura.ui.common.ContextMenus.forTrack(
                            app, player, scope, item,
                            onOpenArtist = onOpenArtist, onOpenCollection = onOpenCollection
                        )
                    )
                },
                onOpen = { _, idx ->
                    // Play the clicked track with the rest of the track results queued behind it.
                    tracks?.let { list -> player.playQueue(list, idx.coerceIn(0, list.lastIndex), resume = true) }
                }
            )

            Spacer(Modifier.height(120.dp)) // clear of the now-playing bar
        }
    }
}

/**
 * One recent-search chip. Focus ring + fill follow the app's language
 * (SurfaceHigh fill, amber border). OK re-runs, a held OK removes.
 * .onFocusChanged MUST precede .clickable or the ring never renders.
 */
@Composable
private fun RecentQueryChip(
    text: String,
    firstFocus: FocusRequester?,
    onRun: () -> Unit,
    onRemove: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .then(if (firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
            .clip(shape)
            .background(if (focused) SurfaceHigh else Border.copy(alpha = 0.35f))
            .then(if (focused) Modifier.border(2.dp, Accent, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .contextMenuKeys(onRemove)
            .clickable { onRun() }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = if (focused) TextPrimary else TextSecondary, fontSize = 14.sp)
    }
}

/** Mic beside the query field; same focus language as the chips. */
@Composable
private fun VoiceSearchButton(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (focused) SurfaceHigh else Border.copy(alpha = 0.35f))
            .then(if (focused) Modifier.border(2.dp, Accent, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("Voice", color = if (focused) TextPrimary else TextSecondary, fontSize = 14.sp)
    }
}
