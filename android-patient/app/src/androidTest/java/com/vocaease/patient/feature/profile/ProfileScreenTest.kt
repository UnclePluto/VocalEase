package com.vocaease.patient.feature.profile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
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
                    ),
                    onHistoryClick = {},
                    onTreatmentPlanClick = {},
                    onPendingUploadsClick = {},
                    onSettingsClick = {},
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
}
