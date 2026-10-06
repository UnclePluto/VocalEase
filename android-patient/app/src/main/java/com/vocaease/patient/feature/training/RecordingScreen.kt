package com.vocaease.patient.feature.training

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vocaease.patient.ui.theme.BrandGreen
import com.vocaease.patient.ui.theme.AppError

data class RecordingUiState(
    val songTitle: String = "",
    val totalDurationMillis: Long = 0,
    val playbackPositionMillis: Long = 0,
    val recordingDurationMillis: Long = 0,
    val recordingState: RecordingState = RecordingState.Countdown(3),
    val activeMode: com.vocaease.patient.core.media.SongPlaybackMode = com.vocaease.patient.core.media.SongPlaybackMode.ACCOMPANIMENT,
    val switchingMode: Boolean = false,
    val referencePitch: ReferencePitchState = ReferencePitchState.Unavailable,
    val patientPitch: com.vocaease.patient.core.media.PitchSample = com.vocaease.patient.core.media.PitchSample(0,null,0f),
    val pitchHistory: List<com.vocaease.patient.core.media.PitchSample> = emptyList(),
    val playbackAnchors: List<com.vocaease.patient.core.media.PlaybackAnchor> = emptyList(),
    val faceStatus: String = "请让嘴部、下颌和颈部位于引导区域内",
    val keepScreenOn: Boolean = false,
    val navigateReviewDraftId: String? = null,
    val errorMessage: String? = null,
)

@Composable
fun RecordingScreen(
    state: RecordingUiState,
    preview: @Composable () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onModeChange: (com.vocaease.patient.core.media.SongPlaybackMode) -> Unit = {},
) {
    val statusLabel = when (val recording = state.recordingState) {
        is RecordingState.Countdown -> "准备录制 ${recording.remainingSeconds}"
        RecordingState.Starting -> "正在启动录制"
        is RecordingState.Recording -> "●  REC  ${formatTime(state.recordingDurationMillis)}"
        RecordingState.Finalizing -> "正在保存录制…"
        is RecordingState.Reviewable -> "录制已保存"
        is RecordingState.Interrupted -> "录制已中断"
    }
    val dark = Color(0xFF06100B)
    val panel = Color(0xFF0D1B14)
    val muted = Color(0xFF89A094)
    Column(
        modifier = Modifier.fillMaxSize().background(dark).testTag("recording-root").padding(horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(48.dp).semantics {
                    contentDescription = "关闭并取消录制"
                    role = Role.Button
                },
            ) {
                Text("×", color = Color(0xFFF2FBF6), fontSize = 26.sp)
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.songTitle, color = Color(0xFFF2FBF6), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${formatTime(state.playbackPositionMillis)} / ${formatTime(state.totalDurationMillis)}",
                    color = muted,
                    fontSize = 10.sp,
                )
            }
            Spacer(Modifier.size(48.dp))
        }
        SingingPitchTimeline(state,Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(22.dp)).background(panel).padding(14.dp))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center) {
            com.vocaease.patient.core.media.SongPlaybackMode.entries.forEach { mode ->
                androidx.compose.material3.TextButton(onClick={onModeChange(mode)},enabled=state.recordingState is RecordingState.Recording && !state.switchingMode,modifier=Modifier.height(48.dp).testTag("recording-mode-${mode.wire}")) {
                    Text(if(state.activeMode==mode) "✓ ${mode.label}" else mode.label,color=if(state.activeMode==mode) BrandGreen else muted)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("recording-lyrics"),
            contentAlignment = Alignment.Center,
        ) {
            Text("歌词暂未提供", color = Color(0xFFF4FFF8), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF1C3328)).testTag("front-camera-preview"),
        ) {
            preview()
            Box(
                modifier = Modifier.align(Alignment.TopStart).padding(14.dp).clip(CircleShape)
                    .background(Color(0xCC07100C)).padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(statusLabel, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            LowerFaceNeckGuide(Modifier.fillMaxSize().padding(bottom=65.dp))
            Text(
                state.errorMessage ?: state.faceStatus,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp).clip(CircleShape)
                    .background(Color(0xCC07100C)).padding(horizontal = 16.dp, vertical = 8.dp),
                color = if (state.errorMessage != null) AppError else Color(0xFFD7E9DF),
                fontSize = 12.sp,
            )
            IconButton(
                onClick = onStop,
                enabled = state.recordingState is RecordingState.Recording,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).size(58.dp)
                    .background(Color(0xFFF6FAF7), CircleShape).semantics { contentDescription = "结束录制" },
            ) {
                Box(Modifier.size(20.dp).background(AppError, RoundedCornerShape(4.dp)))
            }
        }
    }
}

private fun formatTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1_000
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
