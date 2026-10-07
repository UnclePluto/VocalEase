package com.vocaease.patient.feature.training
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
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
}
