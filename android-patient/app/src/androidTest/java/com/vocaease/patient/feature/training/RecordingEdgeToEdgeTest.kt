package com.vocaease.patient.feature.training

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vocaease.patient.ui.AppRoute
import com.vocaease.patient.ui.AuthenticatedApp
import com.vocaease.patient.ui.theme.VocaEaseTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingEdgeToEdgeTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun 录制路由在透明系统栏下仍保持深色背景与浅色图标() {
        composeRule.setContent {
            VocaEaseTheme {
                AuthenticatedApp(
                    initialRoute = AppRoute.Recording("edge-to-edge"),
                    recordingContent = { _, _, _ ->
                        RecordingSystemChrome(composeRule.activity.window)
                        RecordingScreen(
                            RecordingUiState(songTitle = "小幸运"),
                            preview = {}, onStop = {}, onClose = {},
                        )
                    },
                )
            }
        }

        composeRule.runOnIdle {
            WindowCompat.getInsetsController(
                composeRule.activity.window,
                composeRule.activity.window.decorView,
            ).let { controller ->
                assertFalse(controller.isAppearanceLightStatusBars)
                assertFalse(controller.isAppearanceLightNavigationBars)
            }
        }
        var matched = false
        composeRule.waitUntil(timeoutMillis = 3_000) {
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            matched = systemBarMatchReport(
                composeRule.activity.window,
                screenshot,
                RECORDING_CHROME,
                RECORDING_CHROME,
            ).matches
            screenshot.recycle()
            matched
        }
        assertTrue("录制路由的透明系统栏必须透出深色录制背景", matched)
    }

    private companion object {
        val RECORDING_CHROME: Int = Color.rgb(6, 16, 11)
    }
}
