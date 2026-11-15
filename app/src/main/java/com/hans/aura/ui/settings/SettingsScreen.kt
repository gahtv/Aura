package com.hans.aura.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hans.aura.AuraApp
import com.hans.aura.ui.common.ConfirmDialog
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary

/**
 * Minimal settings: server/session info, manual library refresh, and the three
 * ways out of a session — sign in again (same server), change server, sign out.
 */
@Composable
fun SettingsScreen(
    app: AuraApp,
    onRefreshLibrary: () -> Unit,
    onSignInAgain: () -> Unit,
    onChangeServer: () -> Unit,
    onSignOut: () -> Unit
) {
    val session = app.jellyfin.auth.session!!
    // Stamps live in LibraryCache (plain fields), so bump a local counter after
    // a manual refresh or the labels would keep showing the pre-refresh values.
    var refreshTick by remember { mutableStateOf(0) }

    // Both session-ending actions are confirmed: a failed login on a TV has no
    // recovery path without adb, so a single mistaken OK must not be enough.
    var confirm by remember { mutableStateOf<String?>(null) }

    // Overlays live at the SCREEN ROOT (a dialog nested in the Column below is
    // measured against that subtree and renders as an invisible sliver).
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 56.dp, vertical = 8.dp)
        ) {
            androidx.tv.material3.Text("Settings", fontSize = 32.sp, color = TextPrimary)
            androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))

            androidx.tv.material3.Text("Server", fontSize = 18.sp, color = TextPrimary)
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
                Spacer(Modifier.width(16.dp))
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

            androidx.tv.material3.Text("Library", fontSize = 18.sp, color = TextPrimary)
            androidx.tv.material3.Text(
                "Home screens and library lists refresh automatically every 24 hours.",
                fontSize = 13.sp, color = TextSecondary
            )
            androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
            // Last refresh: when the user forced one, and how old the loaded data is.
            val lastManual = remember(refreshTick) { com.hans.aura.data.LibraryCache.lastManualRefreshAtMs }
            val lastLoaded = remember(refreshTick) { com.hans.aura.data.LibraryCache.lastLoadedAtMs }
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

            androidx.tv.material3.Text("Session", fontSize = 18.sp, color = TextPrimary)
            androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
            androidx.tv.material3.Button(onClick = {
                // AppRoot owns the teardown + auth clearing for every exit path.
                onSignOut()
            }) { androidx.tv.material3.Text("Sign out") }

            androidx.compose.foundation.layout.Spacer(Modifier.height(120.dp))
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
