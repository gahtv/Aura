package com.hans.aura.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Warm near-black base — deliberately not Spotify's cold grey.
val Canvas = Color(0xFF0E0C0A)
val Surface = Color(0xFF1A1714)
val SurfaceHigh = Color(0xFF262019)
val Border = Color(0xFF3A322A)
val TextPrimary = Color(0xFFF2EDE6)
val TextSecondary = Color(0xFFB0A79B)
val Accent = Color(0xFFE8A44C) // warm amber; later derived from album art
val AccentDim = Color(0xFF8A5F2E)
// Neutral outline for "the cursor is here" states where a full accent ring
// would be too loud (context-menu rows, unselected tabs). Deliberately grey so
// it never competes with the amber selection fill.
val FocusOutline = Color(0xFF9A8F82)

@Composable
fun AuraTheme(content: @Composable () -> Unit) {
    androidx.tv.material3.MaterialTheme(
        colorScheme = androidx.tv.material3.darkColorScheme(
            background = Canvas,
            surface = Surface,
            primary = Accent,
            onBackground = TextPrimary,
            onSurface = TextPrimary,
            onSurfaceVariant = TextSecondary,
            border = Border,
            secondary = AccentDim
        ),
        content = content
    )
}
