package com.vocaease.patient.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun 两标签可切换且详情返回后恢复原主页底栏() {
        composeRule.setContent {
            AuthenticatedApp(
                initialRoute = AppRoute.Catalog,
                catalogContent = { onSongClick ->
                    Button(
                        onClick = { onSongClick("song-1") },
                        modifier = Modifier.testTag("song-card-1"),
                    ) { Text("歌曲卡片") }
                },
                profileContent = { Text("患者主页") },
            )
        }

        composeRule.onNodeWithText("去唱歌").assertExists()
        composeRule.onNodeWithText("我的").assertExists()
        composeRule.onAllNodes(hasText("首页") or hasText("曲库") or hasText("历史"))
            .assertCountEquals(0)

        composeRule.onNodeWithText("我的").performClick()
        composeRule.onNodeWithText("患者主页").assertIsDisplayed()
        composeRule.onNodeWithText("去唱歌").performClick()
        composeRule.onNodeWithTag("song-card-1").assertIsDisplayed().performClick()

        composeRule.onNodeWithText("去唱歌").assertDoesNotExist()
        composeRule.onNodeWithText("我的").assertDoesNotExist()

        composeRule.activityRule.scenario.onActivity {
            it.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.onNodeWithText("去唱歌").assertIsDisplayed()
        composeRule.onNodeWithText("我的").assertIsDisplayed()
    }
}
