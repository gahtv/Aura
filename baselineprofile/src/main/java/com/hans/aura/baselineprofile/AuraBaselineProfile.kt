package com.hans.aura.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

class AuraBaselineProfile {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(
        PACKAGE,
        includeInStartupProfile = true,
        filterPredicate = ::isAuraRule
    ) {
        launchHome()
    }

    @Test
    fun homeAlbumAndNowPlaying() {
        val album = checkNotNull(InstrumentationRegistry.getArguments().getString("profileAlbum")) {
            "Pass profileAlbum with the title of a playable album visible on Home"
        }

        rule.collect(PACKAGE, filterPredicate = ::isAuraRule) {
            launchHome()
            device.await(By.desc(album), "Home album '$album'").click()
            device.await(By.focused(true).hasDescendant(By.text("Play")), "focused album Play button")
            device.pressDPadCenter()
            device.await(By.desc("Pause"), "Now Playing with active playback")
        }
    }

    private fun MacrobenchmarkScope.launchHome() {
        pressHome()
        startActivityAndWait()
        device.await(By.pkg(PACKAGE).text("Home"), "Home (sign in to Jellyfin on the test device first)")
    }

    private fun UiDevice.await(selector: BySelector, screen: String): UiObject2 =
        checkNotNull(wait(Until.findObject(selector), TIMEOUT_MS)) { "Could not find $screen" }

    private fun isAuraRule(rule: String): Boolean =
        rule.trimStart('H', 'S', 'P').startsWith("Lcom/hans/aura/")

    private companion object {
        const val PACKAGE = "com.hans.aura"
        const val TIMEOUT_MS = 15_000L
    }
}
