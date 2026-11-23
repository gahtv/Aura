package com.hans.aura.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Hct
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.scheme.SchemeVibrant
import com.materialkolor.score.Score

const val AuraSeedArgb: Int = 0xFFE8A44C.toInt()

fun extractSeed(pixels: IntArray): Int {
    if (pixels.isEmpty()) return AuraSeedArgb
    return Score.score(
        QuantizerCelebi.quantize(pixels, 128),
        desired = 1,
        fallbackColorArgb = AuraSeedArgb
    ).firstOrNull() ?: AuraSeedArgb
}

fun dynamicColorScheme(seedArgb: Int): ColorScheme {
    val scheme = SchemeVibrant(Hct.fromInt(seedArgb), isDark = true, contrastLevel = 0.0)
    val colors = MaterialDynamicColors()
    return darkColorScheme(
        primary = Color(colors.primary().getArgb(scheme)),
        onPrimary = Color(colors.onPrimary().getArgb(scheme)),
        primaryContainer = Color(colors.primaryContainer().getArgb(scheme)),
        onPrimaryContainer = Color(colors.onPrimaryContainer().getArgb(scheme)),
        inversePrimary = Color(colors.inversePrimary().getArgb(scheme)),
        secondary = Color(colors.secondary().getArgb(scheme)),
        onSecondary = Color(colors.onSecondary().getArgb(scheme)),
        secondaryContainer = Color(colors.secondaryContainer().getArgb(scheme)),
        onSecondaryContainer = Color(colors.onSecondaryContainer().getArgb(scheme)),
        tertiary = Color(colors.tertiary().getArgb(scheme)),
        onTertiary = Color(colors.onTertiary().getArgb(scheme)),
        tertiaryContainer = Color(colors.tertiaryContainer().getArgb(scheme)),
        onTertiaryContainer = Color(colors.onTertiaryContainer().getArgb(scheme)),
        background = Color(colors.background().getArgb(scheme)),
        onBackground = Color(colors.onBackground().getArgb(scheme)),
        surface = Color(colors.surface().getArgb(scheme)),
        onSurface = Color(colors.onSurface().getArgb(scheme)),
        surfaceVariant = Color(colors.surfaceVariant().getArgb(scheme)),
        onSurfaceVariant = Color(colors.onSurfaceVariant().getArgb(scheme)),
        surfaceTint = Color(colors.surfaceTint().getArgb(scheme)),
        inverseSurface = Color(colors.inverseSurface().getArgb(scheme)),
        inverseOnSurface = Color(colors.inverseOnSurface().getArgb(scheme)),
        error = Color(colors.error().getArgb(scheme)),
        onError = Color(colors.onError().getArgb(scheme)),
        errorContainer = Color(colors.errorContainer().getArgb(scheme)),
        onErrorContainer = Color(colors.onErrorContainer().getArgb(scheme)),
        outline = Color(colors.outline().getArgb(scheme)),
        outlineVariant = Color(colors.outlineVariant().getArgb(scheme)),
        scrim = Color(colors.scrim().getArgb(scheme)),
        surfaceBright = Color(colors.surfaceBright().getArgb(scheme)),
        surfaceDim = Color(colors.surfaceDim().getArgb(scheme)),
        surfaceContainer = Color(colors.surfaceContainer().getArgb(scheme)),
        surfaceContainerHigh = Color(colors.surfaceContainerHigh().getArgb(scheme)),
        surfaceContainerHighest = Color(colors.surfaceContainerHighest().getArgb(scheme)),
        surfaceContainerLow = Color(colors.surfaceContainerLow().getArgb(scheme)),
        surfaceContainerLowest = Color(colors.surfaceContainerLowest().getArgb(scheme)),
        primaryFixed = Color(colors.primaryFixed().getArgb(scheme)),
        primaryFixedDim = Color(colors.primaryFixedDim().getArgb(scheme)),
        onPrimaryFixed = Color(colors.onPrimaryFixed().getArgb(scheme)),
        onPrimaryFixedVariant = Color(colors.onPrimaryFixedVariant().getArgb(scheme)),
        secondaryFixed = Color(colors.secondaryFixed().getArgb(scheme)),
        secondaryFixedDim = Color(colors.secondaryFixedDim().getArgb(scheme)),
        onSecondaryFixed = Color(colors.onSecondaryFixed().getArgb(scheme)),
        onSecondaryFixedVariant = Color(colors.onSecondaryFixedVariant().getArgb(scheme)),
        tertiaryFixed = Color(colors.tertiaryFixed().getArgb(scheme)),
        tertiaryFixedDim = Color(colors.tertiaryFixedDim().getArgb(scheme)),
        onTertiaryFixed = Color(colors.onTertiaryFixed().getArgb(scheme)),
        onTertiaryFixedVariant = Color(colors.onTertiaryFixedVariant().getArgb(scheme))
    )
}

fun ColorScheme.toTvColorScheme(): androidx.tv.material3.ColorScheme =
    androidx.tv.material3.darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = surfaceTint,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        border = outline,
        borderVariant = outlineVariant,
        scrim = scrim
    )

private fun lerpScheme(start: ColorScheme, end: ColorScheme, fraction: Float): ColorScheme =
    start.copy(
        primary = lerp(start.primary, end.primary, fraction),
        onPrimary = lerp(start.onPrimary, end.onPrimary, fraction),
        primaryContainer = lerp(start.primaryContainer, end.primaryContainer, fraction),
        onPrimaryContainer = lerp(start.onPrimaryContainer, end.onPrimaryContainer, fraction),
        inversePrimary = lerp(start.inversePrimary, end.inversePrimary, fraction),
        secondary = lerp(start.secondary, end.secondary, fraction),
        onSecondary = lerp(start.onSecondary, end.onSecondary, fraction),
        secondaryContainer = lerp(start.secondaryContainer, end.secondaryContainer, fraction),
        onSecondaryContainer = lerp(start.onSecondaryContainer, end.onSecondaryContainer, fraction),
        tertiary = lerp(start.tertiary, end.tertiary, fraction),
        onTertiary = lerp(start.onTertiary, end.onTertiary, fraction),
        tertiaryContainer = lerp(start.tertiaryContainer, end.tertiaryContainer, fraction),
        onTertiaryContainer = lerp(start.onTertiaryContainer, end.onTertiaryContainer, fraction),
        background = lerp(start.background, end.background, fraction),
        onBackground = lerp(start.onBackground, end.onBackground, fraction),
        surface = lerp(start.surface, end.surface, fraction),
        onSurface = lerp(start.onSurface, end.onSurface, fraction),
        surfaceVariant = lerp(start.surfaceVariant, end.surfaceVariant, fraction),
        onSurfaceVariant = lerp(start.onSurfaceVariant, end.onSurfaceVariant, fraction),
        surfaceTint = lerp(start.surfaceTint, end.surfaceTint, fraction),
        inverseSurface = lerp(start.inverseSurface, end.inverseSurface, fraction),
        inverseOnSurface = lerp(start.inverseOnSurface, end.inverseOnSurface, fraction),
        error = lerp(start.error, end.error, fraction),
        onError = lerp(start.onError, end.onError, fraction),
        errorContainer = lerp(start.errorContainer, end.errorContainer, fraction),
        onErrorContainer = lerp(start.onErrorContainer, end.onErrorContainer, fraction),
        outline = lerp(start.outline, end.outline, fraction),
        outlineVariant = lerp(start.outlineVariant, end.outlineVariant, fraction),
        scrim = lerp(start.scrim, end.scrim, fraction),
        surfaceBright = lerp(start.surfaceBright, end.surfaceBright, fraction),
        surfaceDim = lerp(start.surfaceDim, end.surfaceDim, fraction),
        surfaceContainer = lerp(start.surfaceContainer, end.surfaceContainer, fraction),
        surfaceContainerHigh = lerp(start.surfaceContainerHigh, end.surfaceContainerHigh, fraction),
        surfaceContainerHighest = lerp(start.surfaceContainerHighest, end.surfaceContainerHighest, fraction),
        surfaceContainerLow = lerp(start.surfaceContainerLow, end.surfaceContainerLow, fraction),
        surfaceContainerLowest = lerp(start.surfaceContainerLowest, end.surfaceContainerLowest, fraction),
        primaryFixed = lerp(start.primaryFixed, end.primaryFixed, fraction),
        primaryFixedDim = lerp(start.primaryFixedDim, end.primaryFixedDim, fraction),
        onPrimaryFixed = lerp(start.onPrimaryFixed, end.onPrimaryFixed, fraction),
        onPrimaryFixedVariant = lerp(start.onPrimaryFixedVariant, end.onPrimaryFixedVariant, fraction),
        secondaryFixed = lerp(start.secondaryFixed, end.secondaryFixed, fraction),
        secondaryFixedDim = lerp(start.secondaryFixedDim, end.secondaryFixedDim, fraction),
        onSecondaryFixed = lerp(start.onSecondaryFixed, end.onSecondaryFixed, fraction),
        onSecondaryFixedVariant = lerp(start.onSecondaryFixedVariant, end.onSecondaryFixedVariant, fraction),
        tertiaryFixed = lerp(start.tertiaryFixed, end.tertiaryFixed, fraction),
        tertiaryFixedDim = lerp(start.tertiaryFixedDim, end.tertiaryFixedDim, fraction),
        onTertiaryFixed = lerp(start.onTertiaryFixed, end.onTertiaryFixed, fraction),
        onTertiaryFixedVariant = lerp(start.onTertiaryFixedVariant, end.onTertiaryFixedVariant, fraction)
    )

internal val AuraComposeColorScheme = darkColorScheme(
    surfaceContainerLowest = Canvas,
    surfaceContainerLow = Surface,
    surfaceContainer = Surface,
    surfaceContainerHigh = SurfaceHigh,
    surfaceContainerHighest = SurfaceHigh,
    primary = AuraColorScheme.primary,
    onPrimary = AuraColorScheme.onPrimary,
    primaryContainer = AuraColorScheme.primaryContainer,
    onPrimaryContainer = AuraColorScheme.onPrimaryContainer,
    inversePrimary = AuraColorScheme.inversePrimary,
    secondary = AuraColorScheme.secondary,
    onSecondary = AuraColorScheme.onSecondary,
    secondaryContainer = AuraColorScheme.secondaryContainer,
    onSecondaryContainer = AuraColorScheme.onSecondaryContainer,
    tertiary = AuraColorScheme.tertiary,
    onTertiary = AuraColorScheme.onTertiary,
    tertiaryContainer = AuraColorScheme.tertiaryContainer,
    onTertiaryContainer = AuraColorScheme.onTertiaryContainer,
    background = AuraColorScheme.background,
    onBackground = AuraColorScheme.onBackground,
    surface = AuraColorScheme.surface,
    onSurface = AuraColorScheme.onSurface,
    surfaceVariant = AuraColorScheme.surfaceVariant,
    onSurfaceVariant = AuraColorScheme.onSurfaceVariant,
    surfaceTint = AuraColorScheme.surfaceTint,
    inverseSurface = AuraColorScheme.inverseSurface,
    inverseOnSurface = AuraColorScheme.inverseOnSurface,
    error = AuraColorScheme.error,
    onError = AuraColorScheme.onError,
    errorContainer = AuraColorScheme.errorContainer,
    onErrorContainer = AuraColorScheme.onErrorContainer,
    outline = AuraColorScheme.border,
    outlineVariant = AuraColorScheme.borderVariant,
    scrim = AuraColorScheme.scrim
)

internal val LocalDynamicTheme = staticCompositionLocalOf { false }

@Composable
fun AuraDynamicTheme(artworkUrl: String?, content: @Composable () -> Unit) {
    val seed = rememberArtworkSeed(artworkUrl)
    val target = remember(seed) { seed?.let(::dynamicColorScheme) ?: AuraComposeColorScheme }
    var start by remember { mutableStateOf(target) }
    var end by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(target) {
        val current = lerpScheme(start, end, progress.value)
        progress.snapTo(0f)
        start = current
        end = target
        progress.animateTo(1f, tween(durationMillis = 500))
    }
    val scheme = lerpScheme(start, end, progress.value)
    CompositionLocalProvider(LocalDynamicTheme provides (seed != null)) {
        androidx.compose.material3.MaterialTheme(colorScheme = scheme) {
            androidx.tv.material3.MaterialTheme(
                colorScheme = scheme.toTvColorScheme(),
                typography = AuraTypography.expressive,
                content = content
            )
        }
    }
}
