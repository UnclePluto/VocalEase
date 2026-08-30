package com.vocaease.patient.feature.training

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class RecordingSystemChromeTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Suppress("DEPRECATION")
    @Test
    fun 录制页系统栏使用深色浅图标且销毁后精确恢复() {
        val window = composeRule.activity.window
        val baselineStatus = Color.rgb(244, 246, 245)
        val baselineNavigation = Color.rgb(238, 241, 239)
        composeRule.runOnUiThread {
            window.statusBarColor = baselineStatus
            window.navigationBarColor = baselineNavigation
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
        val showRecording = mutableStateOf(true)
        composeRule.setContent {
            if (showRecording.value) RecordingSystemChrome(window)
        }

        composeRule.runOnIdle {
            assertEquals(RECORDING_CHROME, window.statusBarColor)
            assertEquals(RECORDING_CHROME, window.navigationBarColor)
            WindowCompat.getInsetsController(window, window.decorView).let { controller ->
                assertFalse(controller.isAppearanceLightStatusBars)
                assertFalse(controller.isAppearanceLightNavigationBars)
            }
        }
        val screenshot = waitForSystemBarPixels(RECORDING_CHROME, RECORDING_CHROME)
        assertSystemBarPixels(RECORDING_CHROME, RECORDING_CHROME, screenshot)

        composeRule.runOnUiThread { showRecording.value = false }
        composeRule.runOnIdle {
            assertEquals(baselineStatus, window.statusBarColor)
            assertEquals(baselineNavigation, window.navigationBarColor)
            WindowCompat.getInsetsController(window, window.decorView).let { controller ->
                assertTrue(controller.isAppearanceLightStatusBars)
                assertTrue(controller.isAppearanceLightNavigationBars)
            }
        }
        val restored = waitForSystemBarPixels(baselineStatus, baselineNavigation)
        assertSystemBarPixels(baselineStatus, baselineNavigation, restored)
    }

    private fun waitForSystemBarPixels(expectedStatus: Int, expectedNavigation: Int): android.graphics.Bitmap {
        var matched: android.graphics.Bitmap? = null
        composeRule.waitUntil(timeoutMillis = 3_000) {
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            if (systemBarPixelsMatch(expectedStatus, expectedNavigation, screenshot)) {
                matched = screenshot
                true
            } else {
                screenshot.recycle()
                false
            }
        }
        return requireNotNull(matched)
    }

    private fun assertSystemBarPixels(expectedStatus: Int, expectedNavigation: Int, screenshot: android.graphics.Bitmap) {
        val actualStatus = screenshot.getPixel(screenshot.width / 2, 10)
        val actualNavigation = screenshot.getPixel(screenshot.width / 4, screenshot.height - 10)
        val message = "status expected=${colorString(expectedStatus)}, actual=${colorString(actualStatus)}; " +
            "navigation expected=${colorString(expectedNavigation)}, actual=${colorString(actualNavigation)}"
        assertTrue(message, pixelMatches(expectedStatus, actualStatus))
        assertTrue(message, pixelMatches(expectedNavigation, actualNavigation))
    }

    private fun systemBarPixelsMatch(
        expectedStatus: Int,
        expectedNavigation: Int,
        screenshot: android.graphics.Bitmap,
    ): Boolean = pixelMatches(expectedStatus, screenshot.getPixel(screenshot.width / 2, 10)) &&
        pixelMatches(expectedNavigation, screenshot.getPixel(screenshot.width / 4, screenshot.height - 10))

    private fun pixelMatches(expected: Int, actual: Int): Boolean =
        abs(Color.red(expected) - Color.red(actual)) <= 4 &&
            abs(Color.green(expected) - Color.green(actual)) <= 4 &&
            abs(Color.blue(expected) - Color.blue(actual)) <= 4

    private fun colorString(color: Int): String =
        "#%02X%02X%02X".format(Color.red(color), Color.green(color), Color.blue(color))

    private companion object {
        val RECORDING_CHROME: Int = Color.rgb(6, 16, 11)
    }
}
