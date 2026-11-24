package com.hans.aura.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import com.hans.aura.AuraApp
import com.hans.aura.data.LibraryCache
import com.hans.aura.data.LibraryFilter
import com.hans.aura.data.LibraryRef
import com.hans.aura.data.Prefs
import com.hans.aura.ui.common.ConfirmDialog
import com.hans.aura.ui.theme.Accent
import com.hans.aura.ui.theme.AuraShapes
import com.hans.aura.ui.theme.Border
import com.hans.aura.ui.theme.FocusOutline
import com.hans.aura.ui.theme.SurfaceHigh
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary

/**
 * Settings: server/session info and the three ways out of a session (sign in
 * again on this server, change server, sign out), manual library refresh, and
 * which Jellyfin libraries Aura shows.
 *
 * LazyColumn, not a Column: the library list makes the page taller than the
 * screen and a plain scrollable Column does not follow D-pad focus. The confirm
 * overlays are drawn at the SCREEN ROOT (see the Box below) — a dialog nested in
 * the scrolling column is measured against that subtree and renders as an
 * invisible sliver.
 */
@Composable
fun SettingsScreen(
    app: AuraApp,
    onRefreshLibrary: () -> Unit,
    onSignInAgain: () -> Unit,
    onChangeServer: () -> Unit,
    onSignOut: () -> Unit,
    onLibrariesChanged: () -> Unit = {},
    onSwitchProfile: (com.hans.aura.data.Session) -> Unit = {},
    // Optional so the existing call site keeps compiling; AppRoot wires it.
    // Without a player the Playback section still shows the autoplay toggle
    // (a plain preference) and hides the sleep-timer row.
    player: com.hans.aura.ui.player.PlayerConnection? = null
) {
    val session = app.jellyfin.auth.session ?: return
    // Stamps live in LibraryCache (plain fields), so bump a local counter after
    // a manual refresh or the labels would keep showing the pre-refresh values.
    var refreshTick by remember { mutableStateOf(0) }

    // Both session-ending actions are confirmed: a failed login on a TV has no
    // recovery path without adb, so a single mistaken OK must not be enough.
    var confirm by remember { mutableStateOf<String?>(null) }

    // ---- Libraries shown in Aura -------------------------------------------
    var libraries by remember { mutableStateOf<List<LibraryRef>?>(null) }
    var libsLoading by remember { mutableStateOf(true) }
    var libsError by remember { mutableStateOf<String?>(null) }
    var hidden by remember { mutableStateOf(Prefs.hiddenLibraries) }
    var libsTick by remember { mutableStateOf(0) }

    LaunchedEffect(libsTick) {
        libsLoading = true
        libsError = null
        runCatching {
            app.container.media.refreshLibraryList()
        }.onSuccess {
            libraries = it
            hidden = Prefs.hiddenLibraries
        }.onFailure { e ->
            libraries = Prefs.knownLibraries.ifEmpty { null }
            libsError = e.message ?: "could not load libraries"
        }
        libsLoading = false
    }

    fun toggleLibrary(lib: LibraryRef) {
        val next = Prefs.hiddenLibraries.toMutableSet()
        if (!next.add(lib.id)) next.remove(lib.id)
        Prefs.hiddenLibraries = next
        hidden = next
        // Nothing visible-library related may survive a selection change: the
        // 24h TTL cache, the paged lists and the Atmos scan all hold items that
        // belong to libraries the user may have just switched off.
        LibraryFilter.clearCaches()
        android.util.Log.i(
            "AURA_LIBS",
            "toggled \"${lib.name}\" (${lib.id}) -> ${if (lib.id in next) "hidden" else "shown"}"
        )
        LibraryFilter.logState()
        onLibrariesChanged()
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 56.dp, vertical = 8.dp)
        ) {
            item {
                androidx.tv.material3.Text("Settings", style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
                androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))

                androidx.tv.material3.Text("Server", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                androidx.tv.material3.Text(session.serverUrl, fontSize = 14.sp, color = TextSecondary)
                androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
                androidx.tv.material3.Text(
                    "Signed in as ${session.userName}",
                    fontSize = 14.sp, color = TextSecondary
                )
                androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
                Row {
                    // Same server, new token: keeps the URL on the login screen.
                    androidx.tv.material3.Button(onClick = { confirm = "again" }) {
                        androidx.tv.material3.Text("Sign in again")
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.width(16.dp))
                    // Different server: forgets the address too.
                    androidx.tv.material3.Button(onClick = { confirm = "server" }) {
                        androidx.tv.material3.Text("Change server")
                    }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
                androidx.tv.material3.Text(
                    "Sign in again keeps this server and the URL filled in. " +
                        "Change server clears the address so you can enter a different one.",
                    fontSize = 13.sp, color = TextSecondary
                )
                androidx.compose.foundation.layout.Spacer(Modifier.height(32.dp))

                androidx.tv.material3.Text("Library", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                androidx.tv.material3.Text(
                    "Home screens and library lists refresh automatically every 24 hours.",
                    fontSize = 13.sp, color = TextSecondary
                )
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                val lastManual = remember(refreshTick) { LibraryCache.lastManualRefreshAtMs }
                val lastLoaded = remember(refreshTick) { LibraryCache.lastLoadedAtMs }
                androidx.tv.material3.Text(
                    "Last manual refresh: " + com.hans.aura.ui.common.relativeTime(lastManual),
                    fontSize = 14.sp, color = TextPrimary
                )
                androidx.tv.material3.Text(
                    "Data loaded: " + com.hans.aura.ui.common.relativeTime(lastLoaded),
                    fontSize = 14.sp, color = TextSecondary
                )
                androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
                Row {
                    androidx.tv.material3.Button(
                        onClick = {
                            onRefreshLibrary()
                            refreshTick++
                        }
                    ) {
                        androidx.tv.material3.Text("Refresh library now")
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.width(16.dp))
                }
                androidx.compose.foundation.layout.Spacer(Modifier.height(32.dp))

                androidx.tv.material3.Text("Libraries shown in Aura", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                androidx.tv.material3.Text(
                    "Switch a library off and Aura stops querying it — its music disappears " +
                        "from Home, the library tabs and search.",
                    fontSize = 13.sp, color = TextSecondary
                )
                androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
            }

            when {
                libsLoading && libraries == null -> item {
                    androidx.tv.material3.Text(
                        "Loading libraries\u2026", fontSize = 14.sp, color = TextSecondary
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
                }
                libsError != null && libraries == null -> item {
                    androidx.tv.material3.Text(
                        "Could not load libraries: $libsError", fontSize = 14.sp, color = TextPrimary
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                    androidx.tv.material3.Button(onClick = { libsTick++ }) {
                        androidx.tv.material3.Text("Retry")
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
                }
                libraries.isNullOrEmpty() -> item {
                    androidx.tv.material3.Text(
                        "No libraries returned by the server.", fontSize = 14.sp, color = TextSecondary
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
                }
                else -> {
                    val libs = libraries.orEmpty()
                    items(libs.size, key = { libs[it].id }) { i ->
                        val lib = libs[i]
                        LibraryToggleRow(
                            library = lib,
                            shown = lib.id !in hidden,
                            onToggle = { toggleLibrary(lib) }
                        )
                    }
                    item {
                        if (libsError != null) {
                            androidx.tv.material3.Text(
                                "Showing the last known list \u2014 $libsError",
                                fontSize = 12.sp, color = TextSecondary
                            )
                        }
                        androidx.tv.material3.Text(
                            (libs.size - hidden.count { id -> libs.any { it.id == id } }).toString() +
                                " of ${libs.size} shown",
                            fontSize = 12.sp, color = TextSecondary
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.height(32.dp))
                    }
                }
            }

            item {
                androidx.tv.material3.Text("Playback", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
                var atmosOnly by remember { mutableStateOf(Prefs.atmosOnly) }
                SettingsToggleRow(
                    title = "Only show Atmos content",
                    subtitle = "Hides anything that is not Dolby Atmos from the music views, " +
                        "including the standard copy of an album you also have in Atmos. Use it " +
                        "when one library holds both versions.",
                    checked = atmosOnly,
                    onToggle = {
                        atmosOnly = !atmosOnly
                        com.hans.aura.data.AtmosFilter.setEnabled(atmosOnly)
                    }
                )
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                var autoplay by remember { mutableStateOf(Prefs.autoplayMusic) }
                SettingsToggleRow(
                    title = "Keep the music going",
                    subtitle = "When the queue reaches its last track, add an instant mix " +
                        "based on it instead of stopping.",
                    checked = autoplay,
                    onToggle = {
                        autoplay = !autoplay
                        Prefs.autoplayMusic = autoplay
                        android.util.Log.i("AURA_AUTOPLAY", "pref=${autoplay}")
                    }
                )
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                var ambientNp by remember { mutableStateOf(Prefs.ambientNowPlaying) }
                SettingsToggleRow(
                    title = "Ambient mode in Now Playing",
                    subtitle = "Fade out controls after 6 seconds of inactivity while playing.",
                    checked = ambientNp,
                    onToggle = {
                        ambientNp = !ambientNp
                        Prefs.ambientNowPlaying = ambientNp
                    }
                )
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                player?.let { p -> SleepTimerRow(p) }
                androidx.compose.foundation.layout.Spacer(Modifier.height(32.dp))
            }

            item {
                androidx.tv.material3.Text("Lyrics", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
                var webLyrics by remember { mutableStateOf(Prefs.lyricsWebFallback) }
                SettingsToggleRow(
                    title = "Look up lyrics online",
                    subtitle = "When the server has no lyrics for a track, search LRCLIB. " +
                        "${appName()} sends the track, artist, album and length \u2014 nothing else.",
                    checked = webLyrics,
                    onToggle = {
                        webLyrics = !webLyrics
                        Prefs.lyricsWebFallback = webLyrics
                        android.util.Log.i("AURA_LYRICS", "web fallback=$webLyrics")
                    }
                )
                androidx.compose.foundation.layout.Spacer(Modifier.height(32.dp))
            }

            item {
                androidx.tv.material3.Text("Profiles", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                val profiles = remember(session.serverUrl, session.userId) {
                    app.jellyfin.auth.profilesForServer(session.serverUrl)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    profiles.forEach { profile ->
                        val active = profile.userId == session.userId
                        androidx.tv.material3.Button(
                            onClick = { if (!active) onSwitchProfile(profile) }
                        ) {
                            androidx.tv.material3.Text(
                                if (active) "\u2713 ${profile.userName}" else profile.userName
                            )
                        }
                    }
                    androidx.tv.material3.Button(onClick = { confirm = "again" }) {
                        androidx.tv.material3.Text("Add account")
                    }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.height(32.dp))
            }

            item {
                androidx.tv.material3.Text("Session", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
                // AppRoot owns the teardown + auth clearing for every exit path.
                androidx.tv.material3.Button(onClick = { onSignOut() }) {
                    androidx.tv.material3.Text("Sign out")
                }

                androidx.compose.foundation.layout.Spacer(Modifier.height(120.dp))
            }
        }

        when (confirm) {
            "again" -> ConfirmDialog(
                title = "Sign in again?",
                message = "Playback stops and the cached library is cleared. " +
                    "You'll come back to the login screen with ${session.serverUrl} " +
                    "filled in — you only need to enter your username and password again.",
                confirmLabel = "Sign in again",
                onConfirm = {
                    confirm = null
                    onSignInAgain()
                },
                onDismiss = { confirm = null }
            )

            "server" -> ConfirmDialog(
                title = "Change server?",
                message = "Playback stops, the cached library is cleared and this " +
                    "server's address is forgotten. You'll return to the login screen " +
                    "and have to type the new server's URL.",
                confirmLabel = "Change server",
                onConfirm = {
                    confirm = null
                    onChangeServer()
                },
                onDismiss = { confirm = null }
            )
        }
    }
}

/**
 * Generic on/off row for the Playback section. Focus flag wired BEFORE
 * .clickable (the app-wide rule) so the focused border actually renders.
 */
@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = AuraShapes.control
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(if (focused) SurfaceHigh else Color.Transparent, shape)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) FocusOutline else Border,
                shape = shape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable { onToggle() }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // weight(1f): without it a long two-line subtitle measures at the full
        // row width and pushes the Switch past the card's right border.
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            androidx.tv.material3.Text(title, fontSize = 16.sp, color = TextPrimary)
            // bodySmall (12sp / 16sp line height): with only fontSize set, the
            // wrapped lines inherited the default 24sp line height — a 24dp
            // pitch that read as a blank band between the description lines.
            androidx.tv.material3.Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF191410),
                checkedTrackColor = Accent,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = SurfaceHigh
            )
        )
    }
}

/**
 * Sleep-timer row for Settings: the same Off -> 15 -> 30 -> 45 -> 60 cycle as
 * the Now Playing chip, showing the time left. The timer pauses playback and
 * leaves the queue intact.
 */
@Composable
private fun SleepTimerRow(player: com.hans.aura.ui.player.PlayerConnection) {
    val remaining by player.sleepRemaining.collectAsState()
    var step by remember { mutableStateOf(0) }
    val presets = listOf(15, 30, 45, 60)
    LaunchedEffect(remaining != null) { if (remaining == null) step = 0 }
    val leftMs = remaining
    val value = when {
        leftMs != null -> com.hans.aura.ui.common.formatDuration(leftMs) + " left"
        step in 1..4 -> "${presets[step - 1]} min"
        else -> "Off"
    }
    SettingsToggleRow(
        title = "Sleep timer",
        subtitle = "Pauses playback after $value — the queue stays where it is.",
        checked = remaining != null,
        onToggle = {
            step = (step + 1) % (presets.size + 1)
            if (step == 0) player.cancelSleepTimer()
            else player.startSleepTimer(presets[step - 1] * 60)
        }
    )
}

/**
 * One library on/off row. Focusable via clickable, so the focus flag must be
 * wired BEFORE .clickable or the styling never renders.
 */
@Composable
private fun LibraryToggleRow(
    library: LibraryRef,
    shown: Boolean,
    onToggle: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = AuraShapes.control
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(if (focused) SurfaceHigh else Color.Transparent, shape)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) FocusOutline else Border,
                shape = shape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable { onToggle() }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // weight(1f): same fix as SettingsToggleRow — a long library name must
        // wrap inside the card, not shove the Switch out of its box.
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            androidx.tv.material3.Text(
                library.name,
                fontSize = 16.sp,
                color = if (shown) TextPrimary else TextSecondary
            )
            library.collectionType?.let {
                androidx.tv.material3.Text(it, fontSize = 12.sp, color = TextSecondary)
            }
        }
        // The row owns the click; the switch is a pure indicator.
        Switch(
            checked = shown,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF191410),
                checkedTrackColor = Accent,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = SurfaceHigh
            )
        )
    }
}

/**
 * The installed flavour's name — "Aura" for the Jellyfin build, or the
 * secondary build's app name. Flavours share this screen, and a privacy note that names the
 * wrong app is exactly the confusion the flavour banners exist to prevent.
 */
@Composable
private fun appName(): String =
    androidx.compose.ui.res.stringResource(com.hans.aura.R.string.app_name)
