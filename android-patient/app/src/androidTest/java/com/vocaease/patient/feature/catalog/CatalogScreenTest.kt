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
    )
}
