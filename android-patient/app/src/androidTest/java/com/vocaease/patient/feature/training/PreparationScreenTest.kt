package com.vocaease.patient.feature.training

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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
    fun 小屏大字体时开始始终可见且权限说明可以滚动到达() {
        var starts = 0
        composeRule.setContent {
            VocaEaseTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.4f)) {
                    Box(Modifier.requiredSize(320.dp, 520.dp)) {
                        PreparationScreen(
                            state = state(), onBack = {}, onStart = { starts++ },
                            onRequestPermissions = {}, onOpenSettings = {}, onRetry = {},
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag("start-singing").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, starts) }
        composeRule.onNodeWithText("摄像头与麦克风已就绪").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("start-singing").assertIsDisplayed()
    }

    @Test
    fun 小屏上切换失败提示始终可见() {
        composeRule.setContent {
            VocaEaseTheme {
                Box(Modifier.requiredSize(320.dp, 520.dp)) {
                    PreparationScreen(state = state().copy(errorMessage = "试听切换失败，已保留原唱，请重试"),
                        onBack = {}, onStart = {}, onRequestPermissions = {}, onOpenSettings = {}, onRetry = {})
                }
            }
        }
        composeRule.onNodeWithText("试听切换失败，已保留原唱，请重试").assertIsDisplayed()
        composeRule.onNodeWithTag("start-singing").assertIsDisplayed()
    }

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
            "露出嘴部、下颌和颈部", "将手机放稳，让鼻子以下到颈部出现在引导框中。",
            "建议连接带麦耳机", "推荐使用带 Mic 的有线或蓝牙耳机，收音更清晰。",
            "摄像头与麦克风已就绪", "开始演唱",
        ).forEach {
            val node = composeRule.onNodeWithText(it)
            if (it != "演唱准备" && it != "开始演唱") node.performScrollTo()
            node.assertIsDisplayed()
        }
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
        composeRule.onNodeWithText("前往系统设置").performScrollTo().performClick()
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

        composeRule.onNodeWithText("未检测到耳机，使用扬声器可能产生串音").performScrollTo().assertIsDisplayed()
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

        composeRule.onNodeWithText("试听加载失败，请重试").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("歌曲正在缓冲").assertDoesNotExist()
        composeRule.onNodeWithText("重新试听").performScrollTo().performClick()
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
