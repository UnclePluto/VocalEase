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
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithContentDescription
import com.vocaease.patient.feature.profile.ProfileScreen
import com.vocaease.patient.feature.profile.ProfileUiState
import com.vocaease.patient.feature.profile.ProfilePatientStatus
import com.vocaease.patient.feature.history.HistoryItem
import com.vocaease.patient.feature.history.HistoryStatus
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Build
import android.util.DisplayMetrics
import org.junit.Assert.assertEquals
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
        val tabRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        composeRule.onNodeWithText("去唱歌").assert(tabRole).assertIsSelected()
        composeRule.onNodeWithText("我的").assert(tabRole).assertIsNotSelected()
        composeRule.onAllNodes(hasText("首页") or hasText("曲库") or hasText("历史"))
            .assertCountEquals(0)

        composeRule.onNodeWithText("我的").performClick()
        composeRule.onNodeWithText("我的").assert(tabRole).assertIsSelected()
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

    @Test
    fun 我的顶部设置按钮打开Settings路由() {
        composeRule.setContent {
            AuthenticatedApp(
                initialRoute = AppRoute.Profile,
                profileContent = { navigation ->
                    ProfileScreen(
                        state = ProfileUiState(patientName = "Voca", patientStatus = ProfilePatientStatus.CONTENT),
                        onHistoryClick = navigation.openHistory,
                        onTreatmentPlanClick = navigation.openTreatmentPlan,
                        onPendingUploadsClick = navigation.openPendingUploads,
                        onSettingsClick = navigation.openSettings,
                        onRetry = {},
                    )
                },
            )
        }

        composeRule.onNodeWithContentDescription("设置").performClick()
        composeRule.onNodeWithText("设置").assertIsDisplayed()
        composeRule.onNodeWithText("去唱歌").assertDoesNotExist()
    }

    @Test
    fun 我的历史行进入真实Result参数且返回释放到历史页() {
        composeRule.setContent {
            AuthenticatedApp(
                initialRoute = AppRoute.Profile,
                profileContent = { navigation ->
                    Button(onClick = navigation.openHistory) { Text("打开演唱历史") }
                },
                historyContent = { _, onResult ->
                    Button(onClick = { onResult("session-closed-loop") }) { Text("小幸运历史行") }
                },
                resultContent = { sessionId, onBack ->
                    Text("结果会话:$sessionId")
                    Button(onClick = onBack) { Text("返回历史") }
                },
            )
        }

        composeRule.onNodeWithText("打开演唱历史").performClick()
        composeRule.onNodeWithText("去唱歌").assertDoesNotExist()
        composeRule.onNodeWithText("小幸运历史行").performClick()
        composeRule.onNodeWithText("结果会话:session-closed-loop").assertIsDisplayed()
        composeRule.onNodeWithText("返回历史").performClick()
        composeRule.onNodeWithText("小幸运历史行").assertIsDisplayed()
        composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertCountEquals(0)
    }

    @Test
    fun f004p我的页历史卡与双标签基准截图为390乘844() {
        val history = listOf(
            HistoryItem("s1", null, "小幸运", "田馥甄", 265, HistoryStatus.COMPLETED, "91", 1, 4),
            HistoryItem("s2", null, "晴天", "周杰伦", 269, HistoryStatus.COMPLETED, "85", 1, 3),
            HistoryItem("s3", null, "后来", "刘若英", 341, HistoryStatus.FAILED, null, 1, 2),
            HistoryItem("s4", null, "平凡之路", "朴树", 302, HistoryStatus.ANALYZING, null, 1, 1),
        )
        composeRule.setContent {
            AuthenticatedApp(
                initialRoute = AppRoute.Profile,
                profileContent = { navigation ->
                    ProfileScreen(
                        state = ProfileUiState("Voca", 28, 8_640, patientStatus = ProfilePatientStatus.CONTENT),
                        onHistoryClick = navigation.openHistory,
                        onTreatmentPlanClick = navigation.openTreatmentPlan,
                        onPendingUploadsClick = navigation.openPendingUploads,
                        onSettingsClick = navigation.openSettings,
                        onRetry = {}, historyItems = history, onHistoryItemClick = navigation.openResult,
                    )
                },
            )
        }
        composeRule.onNodeWithText("小幸运").assertIsDisplayed()
        composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertCountEquals(2)
        val displayMetrics = DisplayMetrics().also(composeRule.activity.windowManager.defaultDisplay::getRealMetrics)
        if (Build.VERSION.SDK_INT >= 34) {
            assertEquals(390, displayMetrics.widthPixels)
            assertEquals(844, displayMetrics.heightPixels)
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /sdcard/task11-profile-390x844.png").close()
    }
}
