package com.vocaease.patient.feature.training

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vocaease.patient.ui.AppRoute
import com.vocaease.patient.ui.AuthenticatedApp
import com.vocaease.patient.ui.theme.VocaEaseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun 返回弹框先暂停取消继续确认丢弃并且系统返回遵循相同流程() {
        val coordinator = ExitUiCoordinator()
        var discarded = 0
        var navigated = 0
        val model = RecordingViewModel("draft",coordinator,object:RecordingDraftGateway {
            override suspend fun load(draftId:String)=RecordingDraftInfo(draftId,"成都",265000)
            override suspend fun acknowledgeHandoff(draftId:String)=Unit
            override suspend fun markInterrupted(draftId:String,reason:RecordingInterruption,durationMillis:Long)=Unit
            override suspend fun discard(draftId:String){discarded++}
        },{},kotlinx.coroutines.Dispatchers.Unconfined)
        composeRule.setContent {
            val state = model.state.collectAsState().value
            val scope = rememberCoroutineScope()
            val request = { scope.launch { model.requestExit() }; Unit }
            androidx.activity.compose.BackHandler(onBack=request)
            RecordingScreen(state,{}, {},request,
                onConfirmExit={scope.launch { if(model.confirmExit()) navigated++ }},
                onCancelExit={scope.launch { model.cancelExit() }})
        }
        composeRule.onNodeWithContentDescription("返回演唱准备").performClick()
        composeRule.onNodeWithText("确认不保存并返回？").assertIsDisplayed()
        composeRule.onNodeWithText("录制已暂停").assertIsDisplayed()
        assertEquals(1,coordinator.pauses)
        assertEquals(0,discarded)
        assertEquals(0,navigated)
        composeRule.onNodeWithText("取消，继续演唱").performClick()
        composeRule.onNodeWithText("确认不保存并返回？").assertDoesNotExist()
        assertEquals(1,coordinator.resumes)
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithText("确认不保存并返回？").assertIsDisplayed()
        composeRule.onNodeWithText("不保存并返回").performClick()
        composeRule.waitForIdle()
        assertEquals(1,discarded)
        assertEquals(1,navigated)
        assertEquals(1,coordinator.closes)
    }

    @Test
    fun 参考音高加载失败允许单独重试() {
        var retries=0
        composeRule.setContent { RecordingScreen(RecordingUiState(referencePitch=ReferencePitchState.Failed), {}, {}, {}, onRetryReferencePitch={retries++}) }
        composeRule.onNodeWithText("参考音高加载失败；仍显示您的声音").assertIsDisplayed()
        composeRule.onNodeWithText("重试").performClick()
        assertEquals(1,retries)
    }

    @Test
    fun 歌词随播放位置切句且不会显示未提供() {
        val position = androidx.compose.runtime.mutableLongStateOf(500)
        val lyrics = LyricsState.Ready(listOf(
            com.vocaease.patient.core.network.dto.LyricLineDto(1000,"第一句真实歌词"),
            com.vocaease.patient.core.network.dto.LyricLineDto(3000,"第二句真实歌词")))
        composeRule.setContent { RecordingScreen(state=RecordingUiState(lyrics=lyrics,playbackPositionMillis=position.longValue),
            preview={},onStop={},onClose={}) }
        composeRule.onNodeWithText("前奏").assertIsDisplayed()
        composeRule.runOnIdle { position.longValue = 1000 }
        composeRule.onNodeWithText("第一句真实歌词").assertIsDisplayed()
        composeRule.runOnIdle { position.longValue = 3000 }
        composeRule.onNodeWithText("第二句真实歌词").assertIsDisplayed()
        composeRule.onNodeWithText("第一句真实歌词").assertDoesNotExist()
        composeRule.onNodeWithText("歌词暂未提供").assertDoesNotExist()
    }

    @Test
    fun 演唱页展示真实音高入口与两种歌曲模式() {
        composeRule.setContent {
            VocaEaseTheme {
                Box(Modifier.fillMaxSize().wrapContentSize(Alignment.TopStart, unbounded = true)) {
                    Box(Modifier.requiredSize(390.dp, 792.dp)) {
                        RecordingScreen(
                            state = RecordingUiState(
                                songTitle = "小幸运",
                                totalDurationMillis = 265_000,
                                playbackPositionMillis = 88_000,
                                recordingDurationMillis = 88_000,
                                recordingState = RecordingState.Recording(1L, 0L),
                                faceStatus = "面部完整 · 光线良好",
                            ),
                            preview = {},
                            onStop = {},
                            onClose = {},
                        )
                    }
                }
            }
        }

        listOf("小幸运", "演唱音高", "01:28 / 04:25", "歌词暂未提供", "面部完整 · 光线良好")
            .forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }
        composeRule.onNodeWithText("REC  01:28", substring = true).assertExists()
        listOf("音准", "C4", "A4", "92%", "实时分析", "模拟分析", "非临床结论")
            .forEach { composeRule.onNodeWithText(it, substring = true).assertDoesNotExist() }
        composeRule.onNodeWithTag("front-camera-preview").assertIsDisplayed()
        composeRule.onNodeWithTag("singing-pitch-timeline").assertIsDisplayed()
        composeRule.onNodeWithTag("lower-face-neck-guide").assertIsDisplayed()
        composeRule.onNodeWithText("✓ 伴奏").assertIsDisplayed()
        composeRule.onNodeWithText("原唱").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("结束录制")
            .assertWidthIsAtLeast(58.dp).assertHeightIsAtLeast(58.dp)
        composeRule.onNodeWithContentDescription("返回演唱准备")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    @Test
    fun 保存失败显示错误并禁用重复结束录制() {
        composeRule.setContent {
            VocaEaseTheme {
                RecordingScreen(
                    RecordingUiState(
                        recordingState = RecordingState.Interrupted(RecordingInterruption.VALIDATION),
                        errorMessage = "录制文件校验失败，请重新录制",
                    ), {}, {}, {},
                )
            }
        }
        composeRule.onNodeWithText("录制已中断").assertIsDisplayed()
        composeRule.onNodeWithText("录制文件校验失败，请重新录制").assertIsDisplayed()
        composeRule.onNodeWithText("REC", substring = true).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("结束录制").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("返回演唱准备").assertIsDisplayed()
    }

    @Test
    fun 保存中显示状态并禁用重复结束录制() {
        composeRule.setContent {
            VocaEaseTheme {
                RecordingScreen(RecordingUiState(recordingState = RecordingState.Finalizing), {}, {}, {})
            }
        }
        composeRule.onNodeWithText("正在保存录制…").assertIsDisplayed()
        composeRule.onNodeWithText("REC", substring = true).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("结束录制").assertIsNotEnabled()
    }

    @Test
    fun 真实导航壳下390乘844页面关键控件均在可视区() {
        composeRule.setContent {
            VocaEaseTheme {
                AuthenticatedApp(
                    initialRoute = AppRoute.Recording("viewport"),
                    recordingContent = { _, _, _ ->
                        RecordingScreen(RecordingUiState(songTitle = "小幸运"), {}, {}, {})
                    },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("recording-root")
            .assertIsDisplayed()
            .assertWidthIsEqualTo(390.dp)
        composeRule.onNodeWithContentDescription("返回演唱准备").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("结束录制").assertIsDisplayed()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertEquals(390, image.width)
        assertEquals(844, image.height)
    }
}

private class ExitUiCoordinator: com.vocaease.patient.core.media.RecordingCoordinator {
    private val mutable=kotlinx.coroutines.flow.MutableStateFlow<RecordingState>(RecordingState.Recording(1,0))
    override val state=mutable
    var pauses=0;var resumes=0;var closes=0
    override suspend fun pause():Boolean { val recording=mutable.value as? RecordingState.Recording ?: return false; pauses++; mutable.value=RecordingState.Paused(recording,1); return true }
    override suspend fun resume():Boolean { val paused=mutable.value as? RecordingState.Paused ?: return false; resumes++;mutable.value=paused.recording;return true }
    override suspend fun takeOver(draftId:String)=Unit
    override suspend fun onCountdownFinished()=Unit
    override suspend fun stop()=Unit
    override suspend fun onPlaybackEnded()=Unit
    override suspend fun interrupt(reason:RecordingInterruption){mutable.value=RecordingState.Interrupted(reason)}
    override fun close(){closes++}
}
