package com.vocaease.patient.feature.catalog

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocaease.patient.ui.theme.VocaEaseTheme
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun 首页展示治疗进度且不展示任何提醒或待上传横幅() {
        composeRule.setContent {
            VocaEaseTheme {
                CatalogScreen(
                    state = catalogState(),
                    onSearch = {},
                    onPatientRetry = {},
                    onRetry = {},
                    onLoadMore = {},
                    onSongClick = {},
                )
            }
        }

        composeRule.onNodeWithTag("treatment-progress")
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(33.33f, 0f..100f, 0),
                ),
            )
        composeRule.onNodeWithText("8 / 24 次").assertIsDisplayed()
        composeRule.onNodeWithText("33%").assertIsDisplayed()
        composeRule.onNodeWithText("治疗第 3 周 · 继续保持").assertIsDisplayed()
        composeRule.onNodeWithTag("song-card-10000000-0000-0000-0000-000000000001")
            .assertIsDisplayed()

        composeRule.onNodeWithText("今日推荐").assertDoesNotExist()
        composeRule.onNodeWithText("待上传", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("上传失败", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("提醒", substring = true).assertDoesNotExist()
    }

    @Test
    fun 无治疗计划显示联系医生且歌曲不可点击() {
        var clickCount = 0
        composeRule.setContent {
            VocaEaseTheme {
                CatalogScreen(
                    state = catalogState().copy(
                        hasActiveTreatmentPlan = false,
                        treatmentProgress = null,
                        canStartTraining = false,
                    ),
                    onSearch = {},
                    onPatientRetry = {},
                    onRetry = {},
                    onLoadMore = {},
                    onSongClick = { clickCount += 1 },
                )
            }
        }

        composeRule.onNodeWithText("暂无进行中的治疗计划，请联系医生").assertIsDisplayed()
        composeRule.onNodeWithTag("song-card-10000000-0000-0000-0000-000000000001")
            .assertIsNotEnabled()
            .performClick()
        composeRule.runOnIdle { assertEquals(0, clickCount) }
    }

    @Test
    fun 首帧加载和请求错误绝不显示联系医生且错误可重试() {
        var retries = 0
        composeRule.setContent {
            VocaEaseTheme {
                CatalogScreen(
                    state = catalogState().copy(
                        patientStatus = PatientUiStatus.ERROR,
                        treatmentProgress = null,
                        patientErrorMessage = "患者信息加载失败，请重试",
                        canStartTraining = false,
                    ),
                    onSearch = {},
                    onPatientRetry = { retries += 1 },
                    onRetry = {},
                    onLoadMore = {},
                    onSongClick = {},
                )
            }
        }

        composeRule.onNodeWithText("暂无进行中的治疗计划，请联系医生").assertDoesNotExist()
        composeRule.onNodeWithText("患者信息加载失败，请重试").assertIsDisplayed()
        composeRule.onNodeWithText("重试")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun 搜索按钮提供中文按钮语义和最小触控目标() {
        composeRule.setContent {
            VocaEaseTheme {
                CatalogScreen(
                    state = catalogState(),
                    onSearch = {}, onPatientRetry = {}, onRetry = {}, onLoadMore = {}, onSongClick = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("搜索")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    private fun catalogState() = CatalogUiState(
        patientName = "Voca",
        hasActiveTreatmentPlan = true,
        treatmentProgress = TreatmentProgressUi(8, 24, 33.33f, 3),
        lifetimeCompletedSongs = 28,
        lifetimeDurationSeconds = 8_640,
        songs = listOf(
            CatalogSongUi(
                id = UUID.fromString("10000000-0000-0000-0000-000000000001"),
                title = "小幸运",
                artist = "田馥甄",
                durationSeconds = 265,
            ),
        ),
        totalSongCount = 32,
        canLoadMore = true,
        canStartTraining = true,
        patientStatus = PatientUiStatus.CONTENT,
    )
}
