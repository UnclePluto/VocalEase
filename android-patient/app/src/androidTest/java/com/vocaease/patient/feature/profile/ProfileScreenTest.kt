package com.vocaease.patient.feature.profile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Assert.assertEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocaease.patient.ui.theme.VocaEaseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun 我的展示服务端终身汇总和所有闭环入口() {
        composeRule.setContent {
            VocaEaseTheme {
                ProfileScreen(
                    state = ProfileUiState(
                        patientName = "Voca",
                        lifetimeCompletedSongs = 28,
                        lifetimeDurationSeconds = 8_640,
                        pendingUploadCount = 2,
                        hasActiveTreatmentPlan = true,
                        patientStatus = ProfilePatientStatus.CONTENT,
                    ),
                    onHistoryClick = {},
                    onTreatmentPlanClick = {},
                    onPendingUploadsClick = {},
                    onSettingsClick = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithText("早上好，Voca").assertIsDisplayed()
        composeRule.onNodeWithText("28").assertIsDisplayed()
        composeRule.onNodeWithText("2.4").assertIsDisplayed()
        composeRule.onNodeWithText("演唱历史").assertIsDisplayed()
        composeRule.onNodeWithText("治疗计划").assertIsDisplayed()
        composeRule.onNodeWithText("待上传记录").assertIsDisplayed()
        composeRule.onNodeWithText("2 条待处理").assertIsDisplayed()
        composeRule.onNodeWithText("设置").assertIsDisplayed()
    }

    @Test
    fun 我的加载失败不显示伪造统计且可重试() {
        var retries = 0
        composeRule.setContent {
            VocaEaseTheme {
                ProfileScreen(
                    state = ProfileUiState(
                        patientStatus = ProfilePatientStatus.ERROR,
                        errorMessage = "患者信息加载失败，请重试",
                    ),
                    onHistoryClick = {},
                    onTreatmentPlanClick = {},
                    onPendingUploadsClick = {},
                    onSettingsClick = {},
                    onRetry = { retries += 1 },
                )
            }
        }

        composeRule.onNodeWithText("早上好，患者").assertDoesNotExist()
        composeRule.onNodeWithText("患者信息加载失败，请重试").assertIsDisplayed()
        composeRule.onNodeWithText("重试").performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun 顶部设置控件具备按钮语义并打开设置() {
        var opened = 0
        composeRule.setContent {
            VocaEaseTheme {
                ProfileScreen(
                    state = ProfileUiState(patientName = "Voca", patientStatus = ProfilePatientStatus.CONTENT),
                    onHistoryClick = {},
                    onTreatmentPlanClick = {},
                    onPendingUploadsClick = {},
                    onSettingsClick = { opened += 1 },
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("设置")
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        composeRule.runOnIdle { assertEquals(1, opened) }
    }
}
