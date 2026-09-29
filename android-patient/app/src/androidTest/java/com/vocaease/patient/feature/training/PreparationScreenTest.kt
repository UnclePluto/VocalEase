package com.vocaease.patient.feature.training

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vocaease.patient.core.media.PreviewState
import com.vocaease.patient.ui.theme.VocaEaseTheme
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreparationScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun 准备页匹配PEN信息结构且歌词只显示产品空态() {
        composeRule.setContent {
            VocaEaseTheme {
                PreparationScreen(
                    state = state(),
                    onBack = {},
                    onStart = {},
                    onRequestPermissions = {},
                    onOpenSettings = {},
                    onRetry = {},
                )
            }
        }

        listOf(
            "演唱准备", "小幸运", "田馥甄  ·  4:25", "歌词暂未提供", "开始前请确认",
            "完整露出面部", "将手机放稳，确保面部完整出现在画面中。",
            "建议连接带麦耳机", "推荐使用带 Mic 的有线或蓝牙耳机，收音更清晰。",
            "摄像头与麦克风已就绪", "开始演唱",
        ).forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }
        composeRule.onNodeWithText("我听见雨滴落在青青草地").assertDoesNotExist()
        composeRule.onNodeWithText("非临床结论").assertDoesNotExist()
        composeRule.onNodeWithTag("start-singing").assertIsEnabled().assertHeightIsAtLeast(56.dp)
        composeRule.onNodeWithContentDescription("返回").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun 永久拒绝权限显示系统设置入口且阻止开始() {
        var openSettings = 0
        composeRule.setContent {
            VocaEaseTheme {
                PreparationScreen(
                    state = state().copy(
                        preflight = PreflightResult(
                            blockers = setOf(PreflightBlocker.CAMERA_PERMISSION),
                            warning = null,
                            openSettingsRequired = true,
                        ),
                    ),
                    onBack = {},
                    onStart = {},
                    onRequestPermissions = {},
                    onOpenSettings = { openSettings += 1 },
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithTag("start-singing").assertIsNotEnabled()
        composeRule.onNodeWithText("前往系统设置").performClick()
        composeRule.runOnIdle { assertEquals(1, openSettings) }
    }

    @Test
    fun 未连接耳机显示非阻断warning并保持开始可用() {
        composeRule.setContent {
            VocaEaseTheme {
                PreparationScreen(
                    state = state().copy(
                        preflight = PreflightResult(
                            blockers = emptySet(),
                            warning = "未检测到耳机，使用扬声器可能产生串音",
                            openSettingsRequired = false,
                        ),
                    ),
                    onBack = {}, onStart = {}, onRequestPermissions = {}, onOpenSettings = {}, onRetry = {},
                )
            }
        }

        composeRule.onNodeWithText("未检测到耳机，使用扬声器可能产生串音").assertIsDisplayed()
        composeRule.onNodeWithTag("start-singing").assertIsEnabled()
    }

    @Test
    fun 试听按钮和错误重试都是可操作状态而不是缓冲文案() {
        var previewToggle = 0
        var previewRetry = 0
        composeRule.setContent {
            VocaEaseTheme {
                PreparationScreen(
                    state = state().copy(
                        previewState = PreviewState.Error("试听加载失败，请重试"),
                        preflight = PreflightResult(setOf(PreflightBlocker.PREVIEW_BUFFER), null, false),
                    ),
                    onBack = {},
                    onStart = {},
                    onRequestPermissions = {},
                    onOpenSettings = {},
                    onRetry = {},
                    onPreviewToggle = { previewToggle += 1 },
                    onRetryPreview = { previewRetry += 1 },
                )
            }
        }

        composeRule.onNodeWithText("试听加载失败，请重试").assertIsDisplayed()
        composeRule.onNodeWithText("歌曲正在缓冲").assertDoesNotExist()
        composeRule.onNodeWithText("重新试听").performClick()
        composeRule.runOnIdle {
            assertEquals(0, previewToggle)
            assertEquals(1, previewRetry)
        }
    }

    @Test
    fun 基准截图尺寸为390乘844且页面无裁剪() {
        composeRule.setContent {
            VocaEaseTheme {
                PreparationScreen(
                    state = state(), onBack = {}, onStart = {}, onRequestPermissions = {}, onOpenSettings = {}, onRetry = {},
                )
            }
        }

        composeRule.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertEquals(390, image.width)
        assertEquals(844, image.height)
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /sdcard/preparation-390x844.png")
            .use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
    }

    private fun state() = PreparationUiState(
        song = PreparationSong(
            UUID.fromString("10000000-0000-4000-8000-000000000001"),
            "小幸运",
            "田馥甄",
            265,
        ),
        previewState = PreviewState.Buffered,
        preflight = PreflightResult(emptySet(), null, false),
    )
}
