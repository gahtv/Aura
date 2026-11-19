package com.hans.aura.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Aura design tokens — one 10-foot (1080p, D-pad) scale for the whole app.
 *
 * The base palette still lives in Theme.kt as plain `val`s because every screen
 * imports them directly; everything that used to be an inline `Color(0x…)`,
 * `dp` or `sp` literal in the UI layer comes from here instead.
 *
 * Colour rules: text tokens are picked for >= 4.5:1 contrast on [Canvas] /
 * [Surface] (large text >= 3:1); focus is always communicated by BOTH a ring
 * ([AuraColors.CursorRing]) and a lifted fill ([AuraColors.FocusedFill]) so it
 * survives a dim TV panel.
 */
object AuraColors {
    // ---- Cursor / focus language -------------------------------------------------
    /** Neutral outline for "the cursor is here" states; never competes with amber. */
    val CursorRing = Color(0xFFD6CCBE)

    /** Lifted plate under a focused button / pill. */
    val FocusedFill = Color(0xFF3E362D)

    /** Softer lift used by filter pills and list rows (sits on Surface). */
    val FocusedFillSubtle = Color(0xFF3A322A)

    // ---- Resting controls -------------------------------------------------------
    /** Resting fill of top-bar / now-playing buttons. */
    val PillBackground = Color(0xFF221D18)

    /** Slightly raised variant used on the darker layer of Now Playing. */
    val PillBackgroundRaised = Color(0xFF2A2620)

    // ---- Text drawn ON amber ----------------------------------------------------
    /** Ink on the amber accent (solid pills, selected tabs). */
    val OnAccent = Color(0xFF14100C)

    /** Slightly warmer ink for text over a dynamic accent. */
    val OnAccentText = Color(0xFF191410)

    // ---- Scrims -----------------------------------------------------------------
    /** Flat neutral wash behind content that must recede. */
    val NeutralScrim = Color(0x99101010)

    /** Confirm-style dialog scrim. */
    val DialogScrim = Color(0xCC080706)

    /** Quick-connect style dialog scrim (a touch warmer). */
    val DialogScrimSoft = Color(0xCC0A0806)

    /** Full-screen overlay behind menus / bio dialogs. */
    val Scrim = Color(0xE60E0C0A)

    /** Partial scrim for the now-playing chip / lyric track card. */
    val CardScrim = Color(0xCC0E0C0A)

    /** Queue-peek panel: nearly opaque so the transport behind it never bleeds. */
    val PeekScrim = Color(0xF20E0C0A)

    /** Badge backing (ATMOS / quality chips on artwork). */
    val BadgeScrim = Color(0xCC14100C)

    // ---- Tints ------------------------------------------------------------------
    /** Selected nav pill that also holds focus. */
    val ActiveTint = Color(0x33E8A44C)

    /** Selected + focused library tab. */
    val SelectedFocusedAccent = Color(0x66E8A44C)

    /** "Filled" plate of the primary transport button. */
    val GlassFill = Color(0x22FFFFFF)

    /** Disabled button plate. */
    val GlassFillMuted = Color(0x14FFFFFF)

    // ---- Feedback ---------------------------------------------------------------
    val ErrorText = Color(0xFFE57373)
}

/** 8dp grid. Every padding/spacer in the UI layer is one of these. */
object AuraSpacing {
    val xxs: Dp = 4.dp
    val xs: Dp = 8.dp
    val sm: Dp = 12.dp
    val md: Dp = 16.dp
    val lg: Dp = 24.dp
    val xl: Dp = 32.dp
    val xxl: Dp = 48.dp

    /** Resting screen inset (library, queue, settings). */
    val screenHorizontal: Dp = 56.dp

    /** Now Playing inset — wider, the cover is the hero. */
    val npHorizontal: Dp = 64.dp

    // Budget-locked insets: these are NOT on the 8dp grid because the TV rows
    // they belong to have a hard width/height budget (see QueueScreen header).
    val npTitleTop: Dp = 40.dp
    val queueHeaderVertical: Dp = 28.dp

    /** Vertical padding of one lyric line; with the 22/26sp text this gives the
     *  ~70dp pitch [AuraSizes.lyricLinePitch] is derived from. */
    val lyricLineVertical: Dp = 10.dp
}

/**
 * TV type scale. The eight core steps are caption/label/bodySmall/body/
 * subtitle/title/headline/display; [titleSmall] and [displaySmall] exist only
 * as shrink-ladder midpoints, and [hero] is the login / pairing display.
 */
object AuraTypography {
    val caption: TextUnit = 12.sp
    val label: TextUnit = 13.sp
    val bodySmall: TextUnit = 14.sp
    val body: TextUnit = 16.sp
    val subtitle: TextUnit = 18.sp
    val titleSmall: TextUnit = 20.sp
    val title: TextUnit = 22.sp
    val headline: TextUnit = 28.sp
    val displaySmall: TextUnit = 34.sp
    val display: TextUnit = 40.sp
    val hero: TextUnit = 64.sp

    /** Reading line height for bio / long-form body copy. */
    val lineHeight: TextUnit = 24.sp

    /** Ladder for a title that must shrink to fit then marquee (largest first). */
    val npTitleFit = listOf(display, displaySmall, headline)
    val chipTitleFit = listOf(titleSmall, subtitle, body)
    val lyricCardFit = listOf(title, titleSmall, subtitle)
}

/** Corner radii. [pill] is the 50% capsule. */
object AuraShapes {
    val xs: Dp = 4.dp
    val card: Dp = 6.dp
    val sm: Dp = 8.dp
    val md: Dp = 10.dp
    val lg: Dp = 14.dp

    /** 50% — a true capsule. */
    val pill = RoundedCornerShape(50)

    fun of(radius: Dp) = RoundedCornerShape(radius)
}

/** Fixed component sizes and layout budgets. */
object AuraSizes {
    // ---- Media ----------------------------------------------------------------
    val artworkLarge: Dp = 320.dp
    val artworkChip: Dp = 80.dp
    val artworkChipLarge: Dp = 96.dp
    val shelfCardWidth: Dp = 180.dp

    // ---- Vertical budgets ------------------------------------------------------
    /** Detail header / now-playing cover block. */
    val detailHeaderHeight: Dp = 320.dp

    /** Scroll padding that keeps the last row clear of the now-playing bar. */
    val bottomBarClearance: Dp = 120.dp

    // ---- Focus / controls -----------------------------------------------------
    val focusBorder: Dp = 2.dp
    val focusBorderLoud: Dp = 3.dp
    val hairline: Dp = 1.dp

    /** Minimum D-pad target. */
    val minTarget: Dp = 48.dp

    /** Compact queue-header action (budget-locked: 7 actions inside 960dp). */
    val compactHeaderActionHeight: Dp = 38.dp
    val compactHeaderActionPadding: Dp = 11.dp

    // ---- Transport ------------------------------------------------------------
    val transportIcon: Dp = 24.dp
    val transportIconLarge: Dp = 40.dp
    val transportPlateExtra: Dp = 40.dp
    val transportButtonPlate: Dp = 64.dp
    val seekBarHeight: Dp = 8.dp
    val wavySeekBarHeight: Dp = 24.dp
    val seekStroke: Dp = 4.dp
    val seekWavelength: Dp = 40.dp
    val seekAmplitude: Dp = 3.dp
    val seekGap: Dp = 4.dp
    val seekHandleHeight: Dp = 20.dp

    // ---- Rows / lists ---------------------------------------------------------
    val trackRowHeight: Dp = 56.dp
    val trackIndexWidth: Dp = 36.dp
    val peekRowHeight: Dp = 46.dp
    val accentBar: Dp = 3.dp
    val rowAccentBarHeight: Dp = 40.dp
    val rowAccentBarWidth: Dp = 4.dp

    // ---- Lyrics ---------------------------------------------------------------
    /** Measured pitch of one lyric line box (26/22sp + 20dp padding). */
    val lyricLinePitch: Dp = 70.dp
    val lyricsTailSpace: Dp = 600.dp

    // ---- Icons / chips --------------------------------------------------------
    val iconSmall: Dp = 20.dp
    val iconBadge: Dp = 18.dp

    // ---- Overlays -------------------------------------------------------------
    val npChipMaxWidth: Dp = 380.dp
    val lyricCardWidth: Dp = 520.dp
    val peekWidth: Dp = 560.dp
    val dialogWidth: Dp = 640.dp
    val bioWidth: Dp = 900.dp
    val contextMenuWidth: Dp = 520.dp

    // ---- Login ---------------------------------------------------------------
    val loginFormWidth: Dp = 480.dp
    val loginPanelWidth: Dp = 680.dp
    val loginHorizontalInset: Dp = 120.dp

    // ---- Shelves -------------------------------------------------------------
    val shelfEdgeSpacer: Dp = 40.dp
}
