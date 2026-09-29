package com.vocaease.patient.feature.training

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vocaease.patient.ui.AppRoute
import com.vocaease.patient.ui.AuthenticatedApp
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
                Box(Modifier.fillMaxSize().wrapContentSize(Alignment.TopStart, unbounded = true)) {
                    Box(Modifier.requiredSize(390.dp, 792.dp)) {
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
    fun 真实导航壳下390乘844页面关键控件均在可视区() {
        composeRule.setContent {
            VocaEaseTheme {
                AuthenticatedApp(
                    initialRoute = AppRoute.Recording("viewport"),
                    recordingContent = { _, _, _ ->
                        RecordingScreen(RecordingUiState(songTitle = "小幸运"), {}, {}, {})
                    },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("recording-root")
            .assertIsDisplayed()
            .assertWidthIsEqualTo(390.dp)
        composeRule.onNodeWithContentDescription("关闭并取消录制").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("结束录制").assertIsDisplayed()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertEquals(390, image.width)
        assertEquals(844, image.height)
    }
}
