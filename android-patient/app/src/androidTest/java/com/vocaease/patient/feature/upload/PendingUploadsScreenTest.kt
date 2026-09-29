package com.vocaease.patient.feature.upload

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.fillMaxSize
import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocaease.patient.ui.theme.VocaEaseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingUploadsScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `390乘844展示完整状态和七天提示且操作至少48dp`() {
        composeRule.setContent {
            VocaEaseTheme {
                Box(if (Build.VERSION.SDK_INT >= 34) Modifier.requiredSize(390.dp, 844.dp) else Modifier.fillMaxSize()) {
                    PendingUploadsScreen(
                        state = PendingUploadsUiState(
                            items = listOf(
                                PendingUploadItem("d1", "茉莉花", "正在上传 37%", 37, canPause = true, canResume = false, canDelete = true),
                                PendingUploadItem("d2", "送别", "等待服务器确认", 50, canPause = true, canResume = false, canDelete = true),
                                PendingUploadItem("d3", "康定情歌", "分析中", 100, canPause = false, canResume = false, canDelete = false),
                            ),
                        ),
                        onBack = {}, onPause = {}, onResume = {}, onRetry = {}, onDelete = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("待上传记录").assertIsDisplayed()
        composeRule.onNodeWithText("建议在录制后7天内完成上传；上传失败任务会保留，可继续重试").assertIsDisplayed()
        composeRule.onNodeWithText("正在上传 37%").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("等待服务器确认").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("分析中").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("暂停 茉莉花")
            .performScrollTo().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("删除 茉莉花")
            .performScrollTo().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("分析结果").assertDoesNotExist()
        composeRule.onNodeWithText("评分").assertDoesNotExist()
        composeRule.onRoot().assertIsDisplayed()
    }

    @Test
    fun 空队列显示安全空态() {
        composeRule.setContent {
            VocaEaseTheme {
                PendingUploadsScreen(PendingUploadsUiState(), {}, {}, {}, {}, {})
            }
        }
        composeRule.onNodeWithText("暂无待上传记录").assertIsDisplayed()
    }
}
