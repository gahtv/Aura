package com.hans.aura.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class AuraDynamicThemeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun pendingSeedRetainsPreviousSchemeAndFailureRestoresStatic() {
        val red = 0xFFDD2222.toInt()
        val blue = 0xFF2244DD.toInt()
        val url = mutableStateOf<String?>("cached-red")
        val pending = CompletableDeferred<Int?>()
        val failed = CompletableDeferred<Int?>()
        val empty = CompletableDeferred<Int?>()
        var primary = Color.Unspecified
        ArtworkSeedCache.put("cached-red", red)
        try {
            compose.setContent {
                val seed = rememberArtworkSeed(url.value) {
                    when (it) {
                        "pending-blue" -> pending.await()
                        "failed" -> failed.await()
                        "empty" -> empty.await()
                        else -> error("Cached seed should not load")
                    }
                }
                val colors = seed?.let(::dynamicColorScheme) ?: AuraComposeColorScheme
                SideEffect { primary = colors.primary }
            }
            compose.runOnIdle {
                assertEquals(dynamicColorScheme(red).primary, primary)
                url.value = "pending-blue"
            }
            compose.runOnIdle {
                assertEquals(dynamicColorScheme(red).primary, primary)
                pending.complete(blue)
            }
            compose.runOnIdle {
                assertEquals(dynamicColorScheme(blue).primary, primary)
                url.value = "failed"
            }
            compose.runOnIdle {
                assertEquals(dynamicColorScheme(blue).primary, primary)
                failed.completeExceptionally(IllegalStateException("Decode failed"))
            }
            compose.runOnIdle {
                assertEquals(Accent, primary)
                url.value = "cached-red"
            }
            compose.runOnIdle {
                assertEquals(dynamicColorScheme(red).primary, primary)
                url.value = "empty"
            }
            compose.runOnIdle {
                assertEquals(dynamicColorScheme(red).primary, primary)
                empty.complete(null)
            }
            compose.runOnIdle {
                assertEquals(Accent, primary)
                url.value = "cached-red"
            }
            compose.runOnIdle {
                assertEquals(dynamicColorScheme(red).primary, primary)
                url.value = null
            }
            compose.runOnIdle { assertEquals(Accent, primary) }
        } finally {
            ArtworkSeedCache.clear()
        }
    }

    @Test
    fun dynamicScopeAnimatesBothThemesWithoutChangingItsStaticSibling() {
        val url = mutableStateOf<String?>("cover-red")
        val red = 0xFFDD2222.toInt()
        val blue = 0xFF2244DD.toInt()
        ArtworkSeedCache.put("cover-red", red)
        ArtworkSeedCache.put("cover-blue", blue)
        var outside = Color.Unspecified
        var inside = Color.Unspecified
        var standard = Color.Unspecified
        var selection = Color.Unspecified
        var badge = Color.Unspecified
        var badgeInk = Color.Unspecified
        try {
            compose.setContent {
                AuraTheme {
                    val static = MaterialTheme.colorScheme.primary
                    SideEffect { outside = static }
                    AuraDynamicTheme(url.value) {
                        val colors = MaterialTheme.colorScheme
                        val tv = colors.primary
                        val phone = androidx.compose.material3.MaterialTheme.colorScheme.primary
                        SideEffect {
                            inside = tv
                            standard = phone
                            selection = colors.secondaryContainer
                            badge = colors.tertiaryContainer
                            badgeInk = colors.onTertiaryContainer
                        }
                    }
                }
            }
            compose.runOnIdle {
                assertEquals(Accent, outside)
                assertEquals(dynamicColorScheme(red).primary, inside)
                assertEquals(dynamicColorScheme(red).secondaryContainer, selection)
                assertEquals(dynamicColorScheme(red).tertiaryContainer, badge)
                assertEquals(dynamicColorScheme(red).onTertiaryContainer, badgeInk)
                assertEquals(inside, standard)
                url.value = "cover-blue"
            }
            compose.mainClock.advanceTimeBy(600)
            compose.runOnIdle {
                assertEquals(Accent, outside)
                assertEquals(dynamicColorScheme(blue).primary, inside)
                assertEquals(dynamicColorScheme(blue).secondaryContainer, selection)
                assertEquals(dynamicColorScheme(blue).tertiaryContainer, badge)
                assertEquals(dynamicColorScheme(blue).onTertiaryContainer, badgeInk)
                assertEquals(inside, standard)
                url.value = null
            }
            compose.mainClock.advanceTimeBy(600)
            compose.runOnIdle {
                assertEquals(Accent, outside)
                assertEquals(Accent, inside)
                assertEquals(AuraColorScheme.secondaryContainer, selection)
                assertEquals(AuraColors.BadgeScrim, badge)
                assertEquals(Accent, badgeInk)
                assertEquals(inside, standard)
            }
        } finally {
            ArtworkSeedCache.clear()
        }
    }

    @Test
    fun nullAndFailedArtworkUseTheStaticRoles() {
        val url = mutableStateOf<String?>(null)
        var primary = Color.Unspecified
        var selection = Color.Unspecified
        var badge = Color.Unspecified
        var badgeInk = Color.Unspecified
        compose.setContent {
            AuraTheme {
                AuraDynamicTheme(url.value) {
                    val colors = MaterialTheme.colorScheme
                    SideEffect {
                        primary = colors.primary
                        selection = colors.secondaryContainer
                        badge = colors.tertiaryContainer
                        badgeInk = colors.onTertiaryContainer
                    }
                }
            }
        }
        compose.runOnIdle {
            assertEquals(Accent, primary)
            assertEquals(AuraColorScheme.secondaryContainer, selection)
            assertEquals(AuraColors.BadgeScrim, badge)
            assertEquals(Accent, badgeInk)
            url.value = "file:///nonexistent-aura-artwork.jpg"
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(Accent, primary)
            assertEquals(AuraColorScheme.secondaryContainer, selection)
            assertEquals(AuraColors.BadgeScrim, badge)
            assertEquals(Accent, badgeInk)
        }
    }

    @Test
    fun staticSharedRolesRetainLegacyColors() {
        assertEquals(Accent, AuraColorScheme.primary)
        assertEquals(AuraColors.OnAccent, AuraColorScheme.onPrimary)
        assertEquals(Accent, AuraColorScheme.tertiary)
        assertEquals(AuraColors.BadgeScrim, AuraColorScheme.tertiaryContainer)
        assertEquals(Accent, AuraColorScheme.onTertiaryContainer)
        assertEquals(Accent.copy(alpha = 0.16f), AuraColorScheme.secondaryContainer)
        assertEquals(Accent, AuraColorScheme.onSecondaryContainer)
        assertEquals(TextPrimary, AuraColorScheme.onSurface)
        assertEquals(TextSecondary, AuraColorScheme.onSurfaceVariant)
        assertTrue(AuraColorScheme.primary.alpha == 1f)
    }

    @Test
    fun staticBadgeAndSelectionInkRemainReadableOverStaticSurfaces() {
        for (surface in listOf(Canvas, Surface, SurfaceHigh)) {
            val colors = AuraColorScheme
            for ((fill, ink) in listOf(
                colors.secondaryContainer to colors.onSecondaryContainer,
                colors.tertiaryContainer to colors.onTertiaryContainer
            )) {
                val background = fill.compositeOver(surface)
                val light = maxOf(background.luminance(), ink.luminance())
                val dark = minOf(background.luminance(), ink.luminance())
                assertTrue((light + 0.05f) / (dark + 0.05f) >= 4.5f)
            }
        }
    }
}
