package com.vocaease.patient.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocaease.patient.AppContainer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun 从曲库进入详情时仅在主页显示双标签底栏() {
        composeRule.setContent {
            VocaEaseApp(
                container = AppContainer.unavailable(),
                initialRoute = AppRoute.Catalog,
            )
        }

        composeRule.onNodeWithText("去唱歌").assertExists()
        composeRule.onNodeWithText("我的").assertExists()
        composeRule.onAllNodes(hasText("首页") or hasText("曲库") or hasText("历史"))
            .assertCountEquals(0)

        composeRule.onNodeWithTag("song-card-1").performClick()

        composeRule.onNodeWithText("去唱歌").assertDoesNotExist()
        composeRule.onNodeWithText("我的").assertDoesNotExist()
    }
}
