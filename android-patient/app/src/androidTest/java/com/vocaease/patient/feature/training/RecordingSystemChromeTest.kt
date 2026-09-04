package com.vocaease.patient.feature.training

import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.dp
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
            Box(
                Modifier
                    .fillMaxSize()
                    .background(ComposeColor(if (showRecording.value) RECORDING_CHROME else baselineStatus)),
            ) {
                if (showRecording.value) RecordingSystemChrome(window)
                if (!showRecording.value) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(48.dp)
                            .background(ComposeColor(baselineNavigation)),
                    )
                }
            }
        }

        composeRule.runOnIdle {
            if (Build.VERSION.SDK_INT < 35) {
                assertEquals(RECORDING_CHROME, window.statusBarColor)
                assertEquals(RECORDING_CHROME, window.navigationBarColor)
            }
            WindowCompat.getInsetsController(window, window.decorView).let { controller ->
                assertFalse(controller.isAppearanceLightStatusBars)
                assertFalse(controller.isAppearanceLightNavigationBars)
            }
        }
        val screenshot = waitForSystemBarPixels(RECORDING_CHROME, RECORDING_CHROME)
        assertSystemBarPixels(RECORDING_CHROME, RECORDING_CHROME, screenshot)

        composeRule.runOnUiThread { showRecording.value = false }
        composeRule.runOnIdle {
            if (Build.VERSION.SDK_INT < 35) {
                assertEquals(baselineStatus, window.statusBarColor)
                assertEquals(baselineNavigation, window.navigationBarColor)
            }
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
            if (systemBarMatchReport(
                    composeRule.activity.window,
                    screenshot,
                    expectedStatus,
                    expectedNavigation,
                ).matches
            ) {
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
        val report = systemBarMatchReport(
            composeRule.activity.window,
            screenshot,
            expectedStatus,
            expectedNavigation,
        )
        assertTrue(report.description, report.matches)
    }

    private companion object {
        val RECORDING_CHROME: Int = Color.rgb(6, 16, 11)
    }
}
