@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.hans.aura.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hans.aura.AuraApp
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.LibraryCache
import com.hans.aura.ui.theme.Border
import com.hans.aura.ui.theme.Surface
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import com.hans.aura.data.SourceQueries

/**
 * Pick an existing playlist (or type a name for a new one) to add tracks to.
 * Used for "Add current track to playlist" and "Save queue as playlist".
 */
@Composable
fun PlaylistPickerDialog(
    app: AuraApp,
    tracksToAdd: List<JellyfinItem>,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    // Every mutation below needs a user id; without a session there is nothing
    // to add to, so the dialog simply does not render (it can only be opened
    // while signed in).
    val userId = app.jellyfin.auth.session?.userId ?: return
    // BACK dismisses the dialog; without this it pops the screen underneath.
    androidx.activity.compose.BackHandler { onDismiss() }
    // Pull focus INTO the dialog immediately and trap it here — otherwise the
    // button that opened the dialog keeps focus and OK keeps hitting it.
    val firstFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        repeat(5) {
            val ok = runCatching { firstFocus.requestFocus() }.isSuccess
            if (ok) return@LaunchedEffect
            kotlinx.coroutines.delay(100)
        }
    }
    var playlists by remember { mutableStateOf<List<JellyfinItem>?>(null) }
    var creatingName by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    // The name field grabs every D-pad key, so without explicit focus routing
    // DOWN never reached Create and a new playlist could never be made (the
    // user could type a name and then sit there: "doesn't actually add a new
    // playlist"). DOWN/UP jump to the buttons, and the remote's OK submits via
    // the IME Done action as well.
    val createFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    var creating by remember { mutableStateOf(false) }
    val submitNew: () -> Unit = {
        val name = newName.trim()
        if (name.isNotBlank()) {
            creating = true
            scope.launch {
                runCatching {
                    app.jellyfin.api.createPlaylist(
                        name = name,
                        ids = tracksToAdd.joinToString(",") { it.id },
                        userId = userId,
                        body = com.hans.aura.data.PlaylistRequest(
                            name = name,
                            ids = tracksToAdd.map { it.id },
                            userId = userId
                        )
                    )
                }.onSuccess { created ->
                    android.util.Log.d(
                        "AURA_MENU",
                        "picker created playlist \"$name\" (${created.id}) with ${tracksToAdd.size} track(s)"
                    )
                    LibraryCache.evict("home_playlists:$userId")
                    onDismiss()
                }.onFailure { e ->
                    // Surface it in the dialog: closing on failure looked like
                    // success (Retrofit bodies throw, but the dialog vanished
                    // before anyone could read the log).
                    android.util.Log.w("AURA_MENU", "create playlist failed: ${e.message}")
                    status = "Could not create playlist: ${e.message ?: "server rejected it"}"
                    creating = false
                }
            }
        }
    }
    // Put the cursor in the name field as soon as create mode opens.
    LaunchedEffect(creatingName) {
        if (creatingName != null) {
            kotlinx.coroutines.delay(120)
            repeat(5) {
                if (runCatching { fieldFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                kotlinx.coroutines.delay(100)
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching {
            SourceQueries.items(
                userId = userId,
                includeItemTypes = "Playlist", recursive = true,
                sortBy = "SortName", limit = 100
            ).items
        }.onSuccess {
            playlists = it
            LibraryCache.put("home_playlists:$userId", it)
        }.onFailure { playlists = emptyList() }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(720.dp)
            .padding(48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .widthIn(max = 720.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Surface)
                .focusProperties { exit = { FocusRequester.Cancel } }
                .padding(32.dp)
        ) {
            androidx.tv.material3.Text(
                if (tracksToAdd.size == 1) "Add to playlist" else "Save ${tracksToAdd.size} tracks as playlist",
                fontSize = 24.sp, color = TextPrimary
            )
            Spacer(Modifier.height(20.dp))

            // Create-new row (inline for "save queue as" flow too)
            androidx.tv.material3.Button(
                onClick = { creatingName = "" },
                modifier = Modifier.focusRequester(firstFocus)
            ) {
                androidx.tv.material3.Text("New playlist\u2026")
            }
            Spacer(Modifier.height(16.dp))

            if (creatingName != null) {
                androidx.compose.material3.TextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { androidx.compose.material3.Text("Playlist name") },
                    singleLine = true,
                    enabled = !creating,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    // OK on the remote submits even while the field owns focus.
                    keyboardActions = KeyboardActions(onDone = { submitNew() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fieldFocus)
                        // Route D-pad out of the text field to the buttons; the
                        // field otherwise swallows every direction key.
                        .focusProperties { down = createFocus }
                        // focusProperties alone is not enough: the text field
                        // consumes arrow keys for caret movement before focus
                        // search runs, so the remote could never reach Create.
                        // Intercept in the preview pass: DOWN jumps to Create,
                        // OK/ENTER submits the name.
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.DirectionDown -> {
                                    createFocus.requestFocus()
                                    true
                                }
                                Key.DirectionCenter, Key.Enter -> {
                                    submitNew()
                                    true
                                }
                                else -> false
                            }
                        }
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.tv.material3.Button(
                        enabled = newName.isNotBlank() && !creating,
                        onClick = submitNew,
                        modifier = Modifier.focusRequester(createFocus)
                    ) { androidx.tv.material3.Text(if (creating) "Creating\u2026" else "Create") }
                    androidx.tv.material3.Button(onClick = {
                        creatingName = null
                        newName = ""
                        status = null
                    }) {
                        androidx.tv.material3.Text("Cancel")
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            status?.let {
                androidx.tv.material3.Text(it, color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
            }

            when (val loaded = playlists) {
                null -> androidx.tv.material3.Text("Loading playlists\u2026", color = TextSecondary)
                else -> {
                    if (loaded.isEmpty() && creatingName == null) {
                        androidx.tv.material3.Text(
                            "No playlists yet \u2014 create one above",
                            color = TextSecondary
                        )
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .height(360.dp)
                            // Scrollable: the fixed 360dp box silently clipped
                            // everything past the ~6th playlist.
                            .verticalScroll(rememberScrollState())
                            .background(Border.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                    ) {
                        loaded.forEach { pl ->
                            var rowFocused by remember(pl.id) { mutableStateOf(false) }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (rowFocused) Color(0xFF3E362D) else Color.Transparent
                                    )
                                    .border(
                                        width = if (rowFocused) 2.dp else 0.dp,
                                        color = if (rowFocused) Color(0xFFD6CCBE)
                                        else Color.Transparent,
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    // Must precede the focus target.
                                    .onFocusChanged { rowFocused = it.isFocused }
                                    .clickable {
                                        scope.launch {
                                            runCatching {
                                                val resp = app.jellyfin.api.addToPlaylist(
                                                    playlistId = pl.id,
                                                    ids = tracksToAdd.joinToString(",") { it.id },
                                                    userId = userId
                                                )
                                                // Retrofit's Response<T> does NOT throw on
                                                // 4xx/5xx — without this check a rejected
                                                // add looked identical to success and the
                                                // dialog just closed.
                                                android.util.Log.d(
                                                    "AURA_MENU",
                                                    "picker add -> HTTP ${resp.code()} for \"${pl.name}\"" +
                                                        (resp.errorBody()?.string()?.take(160)?.let { " body=$it" } ?: "")
                                                )
                                                if (!resp.isSuccessful) {
                                                    throw IllegalStateException("HTTP ${resp.code()}")
                                                }
                                                resp
                                            }.onSuccess {
                                                android.util.Log.d(
                                                    "AURA_MENU",
                                                    "picker added ${tracksToAdd.size} track(s) to \"${pl.name}\" (${pl.id})"
                                                )
                                                LibraryCache.evict("col:${pl.id}")
                                                LibraryCache.evict("home_playlists:$userId")
                                                onDismiss()
                                            }.onFailure { e ->
                                                android.util.Log.w(
                                                    "AURA_MENU",
                                                    "add to \"${pl.name}\" failed: ${e.message}"
                                                )
                                                status = "Could not add: ${e.message}"
                                            }
                                        }
                                    }
                                    .padding(horizontal = 20.dp, vertical = 14.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    androidx.tv.material3.Text(
                                        pl.name,
                                        color = TextPrimary,
                                        fontSize = 17.sp,
                                        fontWeight = if (rowFocused) FontWeight.SemiBold
                                        else FontWeight.Normal
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    androidx.tv.material3.Text(
                                        "${tracksToAdd.size} track" +
                                            if (tracksToAdd.size == 1) "" else "s",
                                        color = TextSecondary, fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            androidx.tv.material3.Button(onClick = onDismiss) {
                androidx.tv.material3.Text("Close")
            }
        }
    }
}
