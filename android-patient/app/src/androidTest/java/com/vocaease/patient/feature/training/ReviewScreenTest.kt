package com.vocaease.patient.feature.training

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.unit.dp
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vocaease.patient.core.database.DraftState
import com.vocaease.patient.ui.theme.VocaEaseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun 本地回看遵循PEN浅色卡片且只显示本地媒体事实() {
        composeRule.setContent {
            VocaEaseTheme {
                ReviewScreen(
                    state = ReviewUiState(
                        loading = false,
                        songTitle = "小幸运",
                        durationMillis = 265_000,
                        durationText = "04:25",
                        validationMessage = "音视频检查通过",
                        draftState = DraftState.REVIEW_READY,
                        canPlayback = true,
                        canConfirm = true,
                    ),
                    videoContent = {},
                    onBack = {}, onPlayPause = {}, onSeek = {}, onSwitchMedia = {},
                    onRerecord = {}, onDelete = {}, onConfirm = {},
                )
            }
        }

        listOf("本地回看", "小幸运", "时长 04:25", "音视频检查通过", "视频", "仅听音频", "重新录制", "确认提交")
            .forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }
        listOf("总分", "音准", "节奏", "稳定度", "排名", "分析曲线", "模拟分析", "非临床结论")
            .forEach { composeRule.onNodeWithText(it, substring = true).assertDoesNotExist() }
        composeRule.onNodeWithTag("review-player-card")
            .assertWidthIsEqualTo(350.dp).assertHeightIsEqualTo(192.dp)
        composeRule.onNodeWithContentDescription("返回")
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("播放录制")
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        listOf("切换到视频", "切换到仅听音频").forEach { description ->
            composeRule.onNodeWithContentDescription(description)
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun 中断草稿只能重新录制或删除且390乘844无裁剪() {
        composeRule.setContent {
            VocaEaseTheme {
                Box(androidx.compose.ui.Modifier.requiredSize(390.dp, 792.dp)) {
                    ReviewScreen(
                        state = ReviewUiState(
                            loading = false,
                            songTitle = "小幸运",
                            durationText = "00:08",
                            draftState = DraftState.INTERRUPTED,
                            validationMessage = "音视频检查通过",
                            canPlayback = true,
                            canConfirm = false,
                        ),
                        videoContent = {},
                        onBack = {}, onPlayPause = {}, onSeek = {}, onSwitchMedia = {},
                        onRerecord = {}, onDelete = {}, onConfirm = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("录制已中断").assertIsDisplayed()
        composeRule.onNodeWithText("重新录制").assertIsDisplayed()
        composeRule.onNodeWithText("删除草稿").assertIsDisplayed()
        composeRule.onNodeWithText("确认提交").assertDoesNotExist()
        composeRule.onNodeWithTag("review-root").assertWidthIsEqualTo(390.dp).assertHeightIsEqualTo(792.dp)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        if (Build.VERSION.SDK_INT >= 34) {
            assertEquals(390, image.width)
            assertEquals(844, image.height)
        }
    }
}
