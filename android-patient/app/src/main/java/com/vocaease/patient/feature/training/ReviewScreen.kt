package com.vocaease.patient.feature.training

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vocaease.patient.core.database.DraftState
import com.vocaease.patient.ui.theme.AppBackground
import com.vocaease.patient.ui.theme.AppError
import com.vocaease.patient.ui.theme.AppWhite
import com.vocaease.patient.ui.theme.BrandGreen
import com.vocaease.patient.ui.theme.TextSecondary

@Composable
fun ReviewScreen(
    state: ReviewUiState,
    videoContent: @Composable () -> Unit,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSwitchMedia: (ReviewMediaKind) -> Unit,
    onRerecord: () -> Unit,
    onDelete: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().background(AppBackground).testTag("review-root")
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(48.dp).semantics {
                    contentDescription = "返回"
                    role = Role.Button
                },
            ) { Text("‹", color = Color(0xFF163C2C), fontSize = 34.sp) }
            Text(
                "本地回看",
                modifier = Modifier.weight(1f),
                color = Color(0xFF173D2D),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.size(48.dp))
        }

        Box(
            modifier = Modifier.width(350.dp).height(192.dp).testTag("review-player-card")
                .clip(RoundedCornerShape(22.dp)).background(Color(0xFF173D2D)),
            contentAlignment = Alignment.Center,
        ) {
            if (state.selectedMedia == ReviewMediaKind.VIDEO && state.canPlayback) videoContent()
            if (state.selectedMedia == ReviewMediaKind.AUDIO || !state.canPlayback) {
                Text(
                    if (state.canPlayback) "正在播放独立音频" else "录制文件暂不可播放",
                    color = Color(0xFFE8F5EE),
                    fontSize = 14.sp,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Column(
            modifier = Modifier.width(350.dp).clip(RoundedCornerShape(18.dp)).background(AppWhite)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(state.songTitle.ifBlank { "本地录制" }, color = Color(0xFF173D2D), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("时长 ${state.durationText}", color = TextSecondary, fontSize = 12.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (state.draftState == DraftState.INTERRUPTED) "录制已中断" else state.validationMessage,
                color = if (state.canPlayback) BrandGreen else AppError,
                fontSize = 12.sp,
            )
            if (state.draftState == DraftState.INTERRUPTED && state.canPlayback) {
                Text(state.validationMessage, color = TextSecondary, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.width(350.dp).height(48.dp).clip(RoundedCornerShape(16.dp)).background(AppWhite),
        ) {
            MediaChoice("视频", ReviewMediaKind.VIDEO, state, onSwitchMedia, Modifier.weight(1f))
            MediaChoice("仅听音频", ReviewMediaKind.AUDIO, state, onSwitchMedia, Modifier.weight(1f))
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.width(350.dp).height(58.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onPlayPause,
                enabled = state.canPlayback && !state.busy,
                modifier = Modifier.size(48.dp).background(BrandGreen, CircleShape).semantics {
                    contentDescription = if (state.isPlaying) "暂停录制" else "播放录制"
                    role = Role.Button
                },
            ) { Text(if (state.isPlaying) "Ⅱ" else "▶", color = Color.White, fontSize = 18.sp) }
            Slider(
                value = state.playbackPositionMillis.toFloat().coerceIn(0f, state.durationMillis.coerceAtLeast(1).toFloat()),
                onValueChange = { onSeek(it.toLong()) },
                valueRange = 0f..state.durationMillis.coerceAtLeast(1).toFloat(),
                enabled = state.canPlayback && !state.busy,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            )
            Text(localTime(state.playbackPositionMillis), color = TextSecondary, fontSize = 11.sp)
        }

        state.errorMessage?.let {
            Text(it, color = AppError, fontSize = 12.sp, modifier = Modifier.width(350.dp).padding(vertical = 4.dp))
        }
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.width(350.dp).padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onRerecord,
                enabled = !state.busy,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text("重新录制") }
            if (state.draftState == DraftState.INTERRUPTED) {
                OutlinedButton(
                    onClick = onDelete,
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text("删除草稿", color = AppError) }
            } else if (state.canConfirm) {
                Button(
                    onClick = onConfirm,
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandGreen),
                ) { Text("确认提交") }
            }
        }
    }
}

@Composable
private fun MediaChoice(
    label: String,
    kind: ReviewMediaKind,
    state: ReviewUiState,
    onSwitchMedia: (ReviewMediaKind) -> Unit,
    modifier: Modifier,
) {
    val selected = state.selectedMedia == kind
    Box(
        modifier = modifier.fillMaxSize().padding(4.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(0xFFDDF3E7) else Color.Transparent)
            .clickable(enabled = state.canPlayback && !state.busy, role = Role.Tab) { onSwitchMedia(kind) }
            .semantics { contentDescription = "切换到$label" },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) BrandGreen else TextSecondary, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

private fun localTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
