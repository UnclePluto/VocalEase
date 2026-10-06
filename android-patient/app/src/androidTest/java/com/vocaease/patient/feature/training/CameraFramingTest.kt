package com.vocaease.patient.feature.training
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

/** 实际视场标定须用目标相机；本测试覆盖小屏引导和结束控件可达性。 */
class CameraFramingTest {
    @get:Rule val composeRule=createAndroidComposeRule<ComponentActivity>()
    @Test fun smallScreenKeepsStopAndLowerFaceGuideReachable() {
        composeRule.setContent { Box(Modifier.requiredSize(320.dp,568.dp)) { RecordingScreen(RecordingUiState(recordingState=RecordingState.Recording(1,0)),{}, {}, {}) } }
        composeRule.onNodeWithTag("lower-face-neck-guide").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("结束录制").assertIsDisplayed()
        composeRule.onNodeWithText("请让嘴部、下颌和颈部位于引导区域内").assertIsDisplayed()
    }
}
