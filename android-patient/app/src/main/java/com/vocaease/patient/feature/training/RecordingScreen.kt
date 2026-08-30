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
    val faceStatus: String = "请确保面部完整入框",
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
) {
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
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
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
        Column(
            modifier = Modifier.fillMaxWidth().height(194.dp).testTag("recording-progress")
                .clip(RoundedCornerShape(22.dp)).background(panel).padding(14.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("播放进度", color = muted, fontSize = 11.sp)
                Text(formatTime(state.playbackPositionMillis), color = BrandGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(46.dp))
            Box(
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Color(0xFF1C3026)),
            ) {
                val fraction = if (state.totalDurationMillis <= 0) 0f else
                    (state.playbackPositionMillis.toFloat() / state.totalDurationMillis).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(BrandGreen))
            }
            Spacer(Modifier.height(36.dp))
            Text(
                "时间 ${formatTime(state.playbackPositionMillis)}",
                modifier = Modifier.align(Alignment.CenterHorizontally),
                color = Color(0xFFD7E9DF),
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(14.dp))
        Box(
            modifier = Modifier.fillMaxWidth().height(212.dp).testTag("recording-lyrics"),
            contentAlignment = Alignment.Center,
        ) {
            Text("歌词暂未提供", color = Color(0xFFF4FFF8), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF1C3328)).testTag("front-camera-preview"),
        ) {
            preview()
            Box(
                modifier = Modifier.align(Alignment.TopStart).padding(14.dp).clip(CircleShape)
                    .background(Color(0xCC07100C)).padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text("●  REC  ${formatTime(state.recordingDurationMillis)}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Box(
                modifier = Modifier.align(Alignment.Center).size(width = 120.dp, height = 158.dp)
                    .border(1.dp, BrandGreen, RoundedCornerShape(60.dp)),
            )
            Text(
                state.faceStatus,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp).clip(CircleShape)
                    .background(Color(0xCC07100C)).padding(horizontal = 16.dp, vertical = 8.dp),
                color = Color(0xFFD7E9DF),
                fontSize = 10.sp,
            )
            IconButton(
                onClick = onStop,
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
