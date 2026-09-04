package com.vocaease.patient.feature.profile

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
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocaease.patient.ui.theme.VocaEaseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun 设置页展示三密码字段显隐和48dp操作() {
        var visibilityToggles = 0
        composeRule.setContent {
            VocaEaseTheme {
                SettingsScreen(
                    state = SettingsUiState(),
                    onBack = {},
                    onOldPasswordChange = {},
                    onNewPasswordChange = {},
                    onConfirmationChange = {},
                    onToggleOldPassword = { visibilityToggles += 1 },
                    onToggleNewPassword = { visibilityToggles += 1 },
                    onToggleConfirmation = { visibilityToggles += 1 },
                    onSubmitPassword = {},
                    onRequestLogout = {},
                    onConfirmLogout = {},
                    onChooseRetain = {},
                    onChooseDelete = {},
                    onDismissLogout = {},
                )
            }
        }

        composeRule.onNodeWithText("设置").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-old-password").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-new-password").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-confirm-password").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("显示原密码").performClick()
        composeRule.onNodeWithContentDescription("显示新密码").performClick()
        composeRule.onNodeWithContentDescription("显示确认密码").performClick()
        composeRule.runOnIdle { assertEquals(3, visibilityToggles) }
        composeRule.onNodeWithTag("settings-change-submit").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("settings-logout").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun loading防双击并显示错误() {
        composeRule.setContent {
            VocaEaseTheme {
                SettingsScreen(
                    state = SettingsUiState(loading = true, errorMessage = "密码修改未完成，请重试"),
                    onBack = {}, onOldPasswordChange = {}, onNewPasswordChange = {}, onConfirmationChange = {},
                    onToggleOldPassword = {}, onToggleNewPassword = {}, onToggleConfirmation = {},
                    onSubmitPassword = {}, onRequestLogout = {}, onConfirmLogout = {},
                    onChooseRetain = {}, onChooseDelete = {}, onDismissLogout = {},
                )
            }
        }

        composeRule.onNodeWithTag("settings-change-submit").assertIsNotEnabled()
        composeRule.onNodeWithTag("settings-logout").assertIsNotEnabled()
        composeRule.onNodeWithText("密码修改未完成，请重试").assertIsDisplayed()
    }

    @Test
    fun 退出对话框覆盖无草稿确认和有草稿保留删除取消() {
        val state = mutableStateOf(SettingsUiState(showLogoutConfirmation = true))
        composeRule.setContent {
            VocaEaseTheme {
                SettingsScreen(
                    state = state.value,
                    onBack = {}, onOldPasswordChange = {}, onNewPasswordChange = {}, onConfirmationChange = {},
                    onToggleOldPassword = {}, onToggleNewPassword = {}, onToggleConfirmation = {},
                    onSubmitPassword = {}, onRequestLogout = {}, onConfirmLogout = {},
                    onChooseRetain = {}, onChooseDelete = {}, onDismissLogout = {},
                )
            }
        }
        composeRule.onNodeWithText("确认退出").assertIsDisplayed()
        composeRule.onNodeWithText("取消").assertIsEnabled()

        composeRule.runOnIdle { state.value = SettingsUiState(pendingDraftDecisionCount = 2) }
        composeRule.onNodeWithText("保留草稿并退出").assertIsDisplayed()
        composeRule.onNodeWithText("删除草稿并退出").assertIsDisplayed()
        composeRule.onNodeWithText("取消").assertIsDisplayed()
    }
}
