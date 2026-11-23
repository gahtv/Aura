package com.hans.aura.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.tv.material3.MaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun dynamicScopeAnimatesBothThemesWithoutChangingItsStaticSibling() {
        val url = mutableStateOf<String?>("cover-red")
        val red = 0xFFDD2222.toInt()
        val blue = 0xFF2244DD.toInt()
        ArtworkSeedCache.put("cover-red", red)
        ArtworkSeedCache.put("cover-blue", blue)
        var outside = Color.Unspecified
        var inside = Color.Unspecified
        var standard = Color.Unspecified
        try {
            compose.setContent {
                AuraTheme {
                    val static = MaterialTheme.colorScheme.primary
                    SideEffect { outside = static }
                    AuraDynamicTheme(url.value) {
                        val tv = MaterialTheme.colorScheme.primary
                        val phone = androidx.compose.material3.MaterialTheme.colorScheme.primary
                        SideEffect {
                            inside = tv
                            standard = phone
                        }
                    }
                }
            }
            compose.runOnIdle {
                assertEquals(Accent, outside)
                assertEquals(dynamicColorScheme(red).primary, inside)
                assertEquals(inside, standard)
                url.value = "cover-blue"
            }
            compose.mainClock.advanceTimeBy(600)
            compose.runOnIdle {
                assertEquals(Accent, outside)
                assertEquals(dynamicColorScheme(blue).primary, inside)
                assertEquals(inside, standard)
                url.value = null
            }
            compose.mainClock.advanceTimeBy(600)
            compose.runOnIdle {
                assertEquals(Accent, outside)
                assertEquals(Accent, inside)
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
        var dynamic = true
        compose.setContent {
            AuraTheme {
                AuraDynamicTheme(url.value) {
                    val colors = MaterialTheme.colorScheme
                    val scoped = LocalDynamicTheme.current
                    SideEffect {
                        primary = colors.primary
                        dynamic = scoped
                    }
                }
            }
        }
        compose.runOnIdle {
            assertEquals(Accent, primary)
            assertFalse(dynamic)
            url.value = "file:///nonexistent-aura-artwork.jpg"
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(Accent, primary)
            assertFalse(dynamic)
        }
    }

    @Test
    fun staticSharedRolesRetainLegacyColors() {
        assertEquals(Accent, AuraColorScheme.primary)
        assertEquals(AuraColors.OnAccent, AuraColorScheme.onPrimary)
        assertEquals(Accent, AuraColorScheme.tertiary)
        assertEquals(Accent.copy(alpha = 0.16f), AuraColorScheme.secondaryContainer)
        assertEquals(Accent, AuraColorScheme.onSecondaryContainer)
        assertEquals(TextPrimary, AuraColorScheme.onSurface)
        assertEquals(TextSecondary, AuraColorScheme.onSurfaceVariant)
        assertTrue(AuraColorScheme.primary.alpha == 1f)
    }
}
