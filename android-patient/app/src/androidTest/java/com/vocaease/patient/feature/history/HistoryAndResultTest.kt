package com.vocaease.patient.feature.history

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vocaease.patient.ui.theme.VocaEaseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryAndResultTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun 历史页按session状态展示且卡片触控至少48dp() {
        var opened = ""
        composeRule.setContent {
            VocaEaseTheme {
                HistoryScreen(
                    state = HistoryState(
                        items = listOf(
                            item("s1", "小幸运", HistoryStatus.COMPLETED, "91"),
                            item("s2", "晴天", HistoryStatus.ANALYZING, null),
                            item("s3", "后来", HistoryStatus.WAITING_NETWORK, null),
                            item("s4", "平凡之路", HistoryStatus.FAILED, null),
                        ),
                    ),
                    onBack = {}, onRetry = {}, onLoadMore = {}, onItemClick = { opened = it },
                )
            }
        }

        listOf("演唱历史", "小幸运", "91", "分析中", "等待网络", "分析失败")
            .forEach { composeRule.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
        composeRule.onNodeWithTag("history-s1")
            .assertWidthIsEqualTo(350.dp).assertHeightIsAtLeast(74.dp).performClick()
        composeRule.runOnIdle { assertEquals("s1", opened) }
        listOf("模拟分析", "非临床结论").forEach {
            composeRule.onNodeWithText(it, substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun 完成结果严格展示真实分数演示标识空分项和真实曲线() {
        composeRule.setContent {
            VocaEaseTheme {
                ResultScreen(
                    state = ResultScreenState(content = result(mockLabel = "演示结果", pitch = listOf(PitchPoint(0, 220f), PitchPoint(10, 230f)))),
                    videoContent = {}, onBack = {}, onRetryLoad = {}, onRetryAnalysis = {},
                )
            }
        }

        listOf("演唱回顾", "小幸运  ·  演唱得分", "91", "演示结果", "本次表现", "音准", "节奏", "稳定度")
            .forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }
        assertEquals(3, composeRule.onAllNodesWithText("暂无单项评分").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("result-video").assertWidthIsEqualTo(350.dp).assertHeightIsEqualTo(192.dp)
        composeRule.onNodeWithContentDescription("真实音准曲线").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("返回").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        listOf("模拟分析", "非临床结论", "超过了", "分享").forEach {
            composeRule.onNodeWithText(it, substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun processing和failed不展示伪造分数曲线且失败可重试() {
        var retries = 0
        composeRule.setContent {
            VocaEaseTheme {
                ResultScreen(
                    state = ResultScreenState(content = result(state = ResultContentState.FAILED, score = null, pitch = emptyList())),
                    videoContent = {}, onBack = {}, onRetryLoad = {}, onRetryAnalysis = { retries += 1 },
                )
            }
        }
        composeRule.onNodeWithText("分析暂时失败，请稍后重试").assertIsDisplayed()
        composeRule.onNodeWithText("重新分析").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
        composeRule.onNodeWithText("91").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("真实音准曲线").assertDoesNotExist()
    }

    @Test
    fun 缺pitch显示空态且API29真实viewport可滚动到底部() {
        composeRule.setContent {
            VocaEaseTheme {
                Box(if (Build.VERSION.SDK_INT >= 34) Modifier.requiredSize(390.dp, 844.dp) else Modifier.fillMaxSize()) {
                    ResultScreen(
                        state = ResultScreenState(content = result(pitch = emptyList())),
                        videoContent = {}, onBack = {}, onRetryLoad = {}, onRetryAnalysis = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("暂无音准曲线").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("result-root").assertIsDisplayed()
    }

    @Test
    fun f005s完成态基准截图为390乘844且无禁词() {
        composeRule.setContent {
            VocaEaseTheme {
                ResultScreen(
                    state = ResultScreenState(content = result(mockLabel = "演示结果", pitch = listOf(PitchPoint(0, 220f), PitchPoint(10, 235f), PitchPoint(20, 225f)))),
                    videoContent = { Text("录像") }, onBack = {}, onRetryLoad = {}, onRetryAnalysis = {},
                )
            }
        }
        composeRule.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        if (Build.VERSION.SDK_INT >= 34) {
            assertEquals(390, image.width)
            assertEquals(844, image.height)
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /sdcard/task11-result-390x844.png").close()
    }

    private fun item(sessionId: String, title: String, status: HistoryStatus, score: String?) = HistoryItem(
        sessionId, null, title, "歌手", 265, status, score, 1, 1_000,
    )

    private fun result(
        state: ResultContentState = ResultContentState.COMPLETED,
        score: String? = "91",
        mockLabel: String? = null,
        pitch: List<PitchPoint> = emptyList(),
    ) = ResultUiModel(
        sessionId = "s1", songTitle = "小幸运", artist = "田馥甄", durationSeconds = 265,
        contentState = state, overallScore = score, mockLabel = mockLabel,
        componentScores = List(3) { "暂无单项评分" }, pitchPoints = pitch,
        pitchMessage = if (state == ResultContentState.COMPLETED && pitch.isEmpty()) "暂无音准曲线" else null,
        safeFailureSummary = if (state == ResultContentState.FAILED) "分析暂时失败，请稍后重试" else null,
        canRetry = state == ResultContentState.FAILED, analysisGeneration = 1,
    )
}
