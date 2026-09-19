package io.github.sudantha.youtubelite.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the baseline profile merged into release builds by the `androidx.baselineprofile`
 * plugin. Requires a connected device:
 * `./gradlew :baselineprofile:connectedDebugAndroidTest :baselineprofile:generateBaselineProfiles`
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = "io.github.sudantha.youtubelite.debug",
        maxIterations = 8,
        stableIterations = 2,
        includeInStartupProfile = true
    ) {
        // this = MacrobenchmarkScope
        pressHome()
        startActivityAndWait()

        // Exercise the lazy-list recycling path; offline this is the error/empty card,
        // which is still a valid scroll target.
        uiAutomator {
            val display = displaySize
            swipe(display.x / 2, display.y * 4 / 5, display.x / 2, display.y / 4, 1500)
            waitForIdle()
            swipe(display.x / 2, display.y / 4, display.x / 2, display.y * 4 / 5, 1500)
            waitForIdle()

            // Account/settings dialog path (best effort: the label may be absent on small layouts).
            try {
                findAndClick(By.desc("Account and settings").or(By.text("Sign in").or(By.text("Done"))))
                waitForIdle()
                pressBack()
            } catch (_: Exception) {
                // Profile generation continues; the dialog path is a bonus.
            }
        }
    }
}
