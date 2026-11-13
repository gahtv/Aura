package com.hans.aura.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hans.aura.AuraApp
import com.hans.aura.data.LibraryCache
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary

/**
 * Minimal settings: server/session info, manual library refresh, sign out.
 */
@Composable
fun SettingsScreen(
    app: AuraApp,
    onRefreshLibrary: () -> Unit,
    onSignOut: () -> Unit
) {
    val session = app.jellyfin.auth.session!!

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
        androidx.compose.foundation.layout.Spacer(Modifier.height(32.dp))

        androidx.tv.material3.Text("Library", fontSize = 18.sp, color = TextPrimary)
        androidx.tv.material3.Text(
            "Home screens and library lists refresh automatically every 24 hours.",
            fontSize = 13.sp, color = TextSecondary
        )
        androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
        Row {
            androidx.tv.material3.Button(onClick = onRefreshLibrary) {
                androidx.tv.material3.Text("Refresh library now")
            }
            androidx.compose.foundation.layout.Spacer(Modifier.width(16.dp))
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(32.dp))

        androidx.tv.material3.Text("Session", fontSize = 18.sp, color = TextPrimary)
        androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
        androidx.tv.material3.Button(onClick = {
            app.jellyfin.auth.clear()
            LibraryCache.clear()
            onSignOut()
        }) { androidx.tv.material3.Text("Sign out") }

        androidx.compose.foundation.layout.Spacer(Modifier.height(120.dp))
    }
}
