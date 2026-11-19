package com.hans.aura.ui.theme

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Typography
import org.junit.Assert.assertEquals
import org.junit.Test

class AuraTypographyTest {
    private val defaults = Typography()
    private val expressive = AuraTypography.expressive

    @Test
    fun `display large preserves the 64 sp hero size and adds weight`() {
        assertEquals(
            defaults.displayLarge.copy(fontSize = 64.sp, lineHeight = 72.sp, fontWeight = FontWeight.Bold),
            expressive.displayLarge
        )
    }

    @Test
    fun `other display and headline styles only change weight`() {
        assertEquals(defaults.displayMedium.copy(fontWeight = FontWeight.Bold), expressive.displayMedium)
        assertEquals(defaults.displaySmall.copy(fontWeight = FontWeight.SemiBold), expressive.displaySmall)
        assertEquals(defaults.headlineLarge.copy(fontWeight = FontWeight.SemiBold), expressive.headlineLarge)
        assertEquals(defaults.headlineMedium.copy(fontWeight = FontWeight.SemiBold), expressive.headlineMedium)
        assertEquals(defaults.headlineSmall.copy(fontWeight = FontWeight.SemiBold), expressive.headlineSmall)
    }

    @Test
    fun `titles body and labels keep the TV material defaults`() {
        assertEquals(defaults.titleLarge, expressive.titleLarge)
        assertEquals(defaults.titleMedium, expressive.titleMedium)
        assertEquals(defaults.titleSmall, expressive.titleSmall)
        assertEquals(defaults.bodyLarge, expressive.bodyLarge)
        assertEquals(defaults.bodyMedium, expressive.bodyMedium)
        assertEquals(defaults.bodySmall, expressive.bodySmall)
        assertEquals(defaults.labelLarge, expressive.labelLarge)
        assertEquals(defaults.labelMedium, expressive.labelMedium)
        assertEquals(defaults.labelSmall, expressive.labelSmall)
    }
}
