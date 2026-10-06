package com.vocaease.patient.feature.training

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vocaease.patient.core.media.PreviewState
import com.vocaease.patient.ui.theme.AppBackground
import com.vocaease.patient.ui.theme.AppOutline
import com.vocaease.patient.ui.theme.AppSurfaceVariant
import com.vocaease.patient.ui.theme.AppWhite
import com.vocaease.patient.ui.theme.BrandForest
import com.vocaease.patient.ui.theme.BrandGreen
import com.vocaease.patient.ui.theme.BrandGreenDark
import com.vocaease.patient.ui.theme.MinimumTouchTargetSize
import com.vocaease.patient.ui.theme.TextPrimary
import com.vocaease.patient.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun PreparationScreen(
    state: PreparationUiState,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    onPreviewToggle: () -> Unit = {},
    onRetryPreview: () -> Unit = {},
    modifier: Modifier = Modifier,
    onModeChange: (com.vocaease.patient.core.media.SongPlaybackMode) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackground)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PreparationHeader(onBack)
        val song = state.song
        if (song == null) {
            Spacer(Modifier.weight(1f))
            if (state.isLoading) {
                CircularProgressIndicator(color = BrandGreen)
            } else {
                Text(state.errorMessage ?: "正在加载演唱准备…", color = TextSecondary)
                if (state.errorMessage != null) TextButton(onClick = onRetry) { Text("重试") }
            }
            Spacer(Modifier.weight(1f))
            return@Column
        }

        state.errorMessage?.let { message ->
            Text(
                message,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SongIdentity(song, state.previewState, !state.isCreatingSession, onPreviewToggle, onRetryPreview)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                com.vocaease.patient.core.media.SongPlaybackMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.previewMode == mode,
                        onClick = { onModeChange(mode) },
                        enabled = !state.isCreatingSession && state.previewState != PreviewState.Buffering,
                        label = { Text(mode.label) },
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.heightIn(min = 48.dp).testTag("preview-mode-${mode.wire}"),
                    )
                }
            }
            LyricsUnavailable()
            Text(
                "开始前请确认",
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            PreparationTip(
                symbol = "◎",
                title = "露出嘴部、下颌和颈部",
                description = "将手机放稳，让鼻子以下到颈部出现在引导框中。",
            )
            Spacer(Modifier.height(12.dp))
            PreparationTip(
                symbol = "♬",
                title = "建议连接带麦耳机",
                description = "推荐使用带 Mic 的有线或蓝牙耳机，收音更清晰。",
            )
            Spacer(Modifier.height(12.dp))
            DeviceStatus(state, onRequestPermissions, onOpenSettings)
            state.preflight.warning?.let { warning ->
                Text(
                    warning,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    color = BrandGreenDark,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(16.dp))
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onStart,
            enabled = state.preflight.canStart && !state.isCreatingSession,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("start-singing"),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = BrandGreen,
                contentColor = BrandForest,
            ),
        ) {
            Text(
                when {
                    state.countdownSecond != null -> "${state.countdownSecond}"
                    state.isCreatingSession -> "正在创建会话…"
                    else -> "开始演唱"
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun PreparationHeader(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().height(92.dp)) {
        TextButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(MinimumTouchTargetSize)
                .semantics {
                    contentDescription = "返回"
                    role = Role.Button
                },
            shape = CircleShape,
            colors = ButtonDefaults.textButtonColors(containerColor = AppSurfaceVariant),
        ) {
            Text("‹", color = TextPrimary, fontSize = 28.sp, lineHeight = 28.sp)
        }
        Text(
            "演唱准备",
            modifier = Modifier.align(Alignment.Center),
            color = TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SongIdentity(
    song: PreparationSong,
    previewState: PreviewState,
    controlsEnabled: Boolean,
    onPreviewToggle: () -> Unit,
    onRetryPreview: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().height(88.dp),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(88.dp)
                .shadow(12.dp, RoundedCornerShape(24.dp))
                .background(androidx.compose.ui.graphics.Color(0xFFB95C91), RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("♫", color = AppWhite, fontSize = 36.sp)
        }
        val (label, action) = when (previewState) {
            PreviewState.Buffered -> "试听" to onPreviewToggle
            PreviewState.Playing -> "暂停" to onPreviewToggle
            is PreviewState.Error -> "重新试听" to onRetryPreview
            else -> "缓冲中" to {}
        }
        TextButton(
            onClick = action,
            enabled = controlsEnabled && (previewState is PreviewState.Buffered || previewState is PreviewState.Playing ||
                previewState is PreviewState.Error),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .height(MinimumTouchTargetSize)
                .semantics {
                    contentDescription = when (previewState) {
                        PreviewState.Playing -> "暂停试听"
                        is PreviewState.Error -> "重新试听"
                        else -> "试听歌曲"
                    }
                    role = Role.Button
                },
        ) {
            Text(label, color = BrandGreenDark, fontWeight = FontWeight.Bold)
        }
    }
    Text(
        song.title,
        modifier = Modifier.padding(top = 12.dp),
        color = TextPrimary,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        "${song.artist}  ·  ${formatDuration(song.durationSeconds)}",
        modifier = Modifier.padding(top = 1.dp, bottom = 12.dp),
        color = TextSecondary,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun LyricsUnavailable() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp)
            .background(AppSurfaceVariant, RoundedCornerShape(22.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "歌词暂未提供",
            color = TextSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun PreparationTip(symbol: String, title: String, description: String) {
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = 78.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = AppWhite),
        border = BorderStroke(1.dp, AppOutline),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(46.dp).background(
                    androidx.compose.ui.graphics.Color(0xFFDDF7EA),
                    RoundedCornerShape(14.dp),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(symbol, color = BrandGreen, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    description,
                    modifier = Modifier.padding(top = 2.dp),
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.5.sp,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun DeviceStatus(
    state: PreparationUiState,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val permissionBlocked = state.preflight.blockers.any {
        it == PreflightBlocker.CAMERA_PERMISSION || it == PreflightBlocker.AUDIO_PERMISSION
    }
    val status = when {
        state.preflight.openSettingsRequired -> "相机或麦克风权限已关闭"
        permissionBlocked -> "需要相机与麦克风权限"
        PreflightBlocker.FRONT_CAMERA in state.preflight.blockers -> "未检测到前置摄像头"
        PreflightBlocker.STORAGE in state.preflight.blockers -> "存储空间不足"
        PreflightBlocker.OFFLINE in state.preflight.blockers -> "请连接网络后创建会话"
        state.previewState is PreviewState.Error -> state.previewState.message
        state.previewState !is PreviewState.Buffered && state.previewState !is PreviewState.Playing -> "歌曲正在缓冲"
        else -> "摄像头与麦克风已就绪"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .background(androidx.compose.ui.graphics.Color(0xFFEFF8F3), RoundedCornerShape(17.dp))
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (state.preflight.canStart) "✓" else "!", color = BrandGreen, fontSize = 20.sp)
        Text(
            status,
            modifier = Modifier.padding(start = 12.dp).weight(1f),
            color = BrandGreenDark,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when {
            state.preflight.openSettingsRequired -> TextButton(onClick = onOpenSettings) { Text("前往系统设置") }
            permissionBlocked -> TextButton(onClick = onRequestPermissions) { Text("授权") }
        }
    }
}

private fun formatDuration(seconds: Int): String = String.format(
    Locale.CHINA,
    "%d:%02d",
    seconds.coerceAtLeast(0) / 60,
    seconds.coerceAtLeast(0) % 60,
)
