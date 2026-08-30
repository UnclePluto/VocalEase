package com.vocaease.patient.feature.training

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vocaease.patient.ui.theme.VocaEaseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun 演唱页遵循PEN深色结构但只展示播放进度与歌词空态() {
        composeRule.setContent {
            VocaEaseTheme {
                RecordingScreen(
                    state = RecordingUiState(
                        songTitle = "小幸运",
                        totalDurationMillis = 265_000,
                        playbackPositionMillis = 88_000,
                        recordingDurationMillis = 88_000,
                        recordingState = RecordingState.Recording(1L, 0L),
                        faceStatus = "面部完整 · 光线良好",
                    ),
                    preview = {},
                    onStop = {},
                    onClose = {},
                )
            }
        }

        listOf("小幸运", "播放进度", "01:28 / 04:25", "歌词暂未提供", "面部完整 · 光线良好")
            .forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }
        composeRule.onNodeWithText("REC  01:28", substring = true).assertExists()
        listOf("音准", "C4", "A4", "92%", "实时分析", "模拟分析", "非临床结论")
            .forEach { composeRule.onNodeWithText(it, substring = true).assertDoesNotExist() }
        composeRule.onNodeWithTag("front-camera-preview")
            .assertIsDisplayed()
            .assertWidthIsEqualTo(362.dp)
            .assertHeightIsEqualTo(300.dp)
            .assertTopPositionInRootIsEqualTo(492.dp)
        composeRule.onNodeWithTag("recording-progress")
            .assertWidthIsEqualTo(362.dp)
            .assertHeightIsEqualTo(194.dp)
            .assertTopPositionInRootIsEqualTo(64.dp)
        composeRule.onNodeWithTag("recording-lyrics")
            .assertWidthIsEqualTo(362.dp)
            .assertHeightIsEqualTo(212.dp)
            .assertTopPositionInRootIsEqualTo(272.dp)
        composeRule.onNodeWithContentDescription("结束录制")
            .assertWidthIsAtLeast(58.dp).assertHeightIsAtLeast(58.dp)
        composeRule.onNodeWithContentDescription("关闭并取消录制")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    @Test
    fun 基准截图为390乘844且页面无裁剪() {
        composeRule.setContent {
            VocaEaseTheme {
                RecordingScreen(RecordingUiState(songTitle = "小幸运"), {}, {}, {})
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("recording-root")
            .assertWidthIsEqualTo(390.dp)
            .assertHeightIsEqualTo(792.dp)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertEquals(390, image.width)
        assertEquals(844, image.height)
    }
}
