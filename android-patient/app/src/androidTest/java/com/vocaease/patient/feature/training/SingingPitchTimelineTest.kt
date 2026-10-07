package com.vocaease.patient.feature.training
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import com.vocaease.patient.core.media.PlaybackAnchor
import com.vocaease.patient.core.media.SongPlaybackMode
import org.junit.Assert.*
import com.vocaease.patient.core.media.PitchSample
import com.vocaease.patient.core.network.dto.ReferenceNoteDto
import org.junit.Rule
import org.junit.Test

class SingingPitchTimelineTest {
    @get:Rule val composeRule=createAndroidComposeRule<ComponentActivity>()
    @Test fun timelineShowsReferenceBlocksAndPatientDot() {
        val state=mutableStateOf(RecordingUiState(referencePitch=ReferencePitchState.Ready("bound",listOf(ReferenceNoteDto(0,1000,57f,1f))),patientPitch=PitchSample(500,220f,1f)))
        composeRule.setContent { SingingPitchTimeline(state.value,Modifier.width(362.dp).height(170.dp)) }
        composeRule.onNodeWithContentDescription("患者实时音高：220 Hz").assertIsDisplayed()
        composeRule.runOnIdle { state.value=state.value.copy(patientPitch=PitchSample(600,null,0f)) }
        composeRule.onNodeWithContentDescription("患者实时音高：静音或不稳定").assertIsDisplayed()
    }
    private fun countGreenPixels(): Int {
        val pixels = composeRule.onNodeWithTag("singing-pitch-canvas").captureToImage().toPixelMap()
        var count = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            if (color.green > .5f && color.green > color.red * 1.4f && color.green > color.blue * 1.2f) count++
        }
        return count
    }
    private fun saveCanvas(name: String) {
        val bitmap = composeRule.onNodeWithTag("singing-pitch-canvas").captureToImage().asAndroidBitmap()
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        File(requireNotNull(directory), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun passedReferenceWithoutCorrectVoiceStaysGray() {
        val state = RecordingUiState(referencePitch = ReferencePitchState.Ready("bound", listOf(ReferenceNoteDto(0, 1200, 60f, 1f))),
            playbackPositionMillis = 1500, recordingDurationMillis = 1500,
            recordingState = RecordingState.Recording(0, 0), patientPitch = PitchSample(1500, 329.63f, 1f),
            pitchHistory = listOf(PitchSample(1400, 329.63f, 1f), PitchSample(1450, 329.63f, 1f), PitchSample(1500, 329.63f, 1f)),
            playbackAnchors = listOf(PlaybackAnchor(0, 0, SongPlaybackMode.ACCOMPANIMENT, true, 0)))
        composeRule.setContent { SingingPitchTimeline(state, Modifier.width(362.dp).height(170.dp)) }
        composeRule.onNodeWithTag("singing-pitch-canvas").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "未命中，参考音符保持灰色"))
        assertEquals("经过参考线不能自动变绿", 0, countGreenPixels())
    }
    @Test fun correctVoiceLightsNoteAndGlowStopsImmediatelyWhenPitchIsWrong() {
        val history = listOf(PitchSample(1500, 261.63f, 1f), PitchSample(1550, 261.63f, 1f), PitchSample(1600, 261.63f, 1f))
        val state = mutableStateOf(RecordingUiState(referencePitch = ReferencePitchState.Ready("bound", listOf(ReferenceNoteDto(1000, 4000, 60.2f, 1f))),
            playbackPositionMillis = 1600, recordingDurationMillis = 1600,
            recordingState = RecordingState.Recording(0, 0), patientPitch = history.last(), pitchHistory = history,
            playbackAnchors = listOf(PlaybackAnchor(0, 0, SongPlaybackMode.ACCOMPANIMENT, true, 0))))
        composeRule.setContent { SingingPitchTimeline(state.value, Modifier.width(362.dp).height(170.dp).background(Color(0xFF08190F))) }
        composeRule.onNodeWithTag("singing-pitch-canvas").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "唱准，音符点亮"))
        assertTrue("真实命中应有绿色绘制", countGreenPixels() > 0)
        saveCanvas("vocaease-karaoke-hit.png")
        composeRule.runOnIdle {
            val wrong = PitchSample(1700, 329.63f, 1f)
            state.value = state.value.copy(playbackPositionMillis = 1700, recordingDurationMillis = 1700,
                patientPitch = wrong, pitchHistory = history + wrong)
        }
        composeRule.onNodeWithTag("singing-pitch-canvas").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "未命中，参考音符保持灰色"))
        saveCanvas("vocaease-karaoke-miss.png")
    }
}
