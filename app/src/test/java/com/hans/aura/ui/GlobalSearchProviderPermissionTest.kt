package com.hans.aura.ui

import androidx.test.core.app.ApplicationProvider
import com.hans.aura.AuraApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GlobalSearchProviderPermissionTest {
    @Test
    fun exportedSuggestionsRequireGlobalSearchPermission() {
        val app = ApplicationProvider.getApplicationContext<AuraApp>()
        val provider = requireNotNull(app.packageManager.resolveContentProvider("com.hans.aura.search", 0))

        assertTrue(provider.exported)
        assertEquals("android.permission.GLOBAL_SEARCH", provider.readPermission)
    }
}
