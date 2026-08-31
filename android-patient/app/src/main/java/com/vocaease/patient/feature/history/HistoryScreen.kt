package com.vocaease.patient.feature.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import com.vocaease.patient.ui.theme.AppBackground
import com.vocaease.patient.ui.theme.AppWhite
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.vocaease.patient.ui.theme.BrandGreen
import com.vocaease.patient.ui.theme.TextPrimary
import com.vocaease.patient.ui.theme.TextSecondary

@Composable
fun HistoryScreen(
    state: HistoryState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().background(AppBackground).testTag("history-root"),
        contentPadding = PaddingValues(start = 20.dp, top = 14.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RoundControl("‹", "返回", onBack)
                Spacer(Modifier.weight(1f))
                Text("演唱历史", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(48.dp))
            }
        }
        if (state.items.isEmpty() && !state.loading) item {
            Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                Text("暂无演唱记录", color = TextSecondary, fontSize = 14.sp)
            }
        }
        items(state.items, key = { it.sessionId }) { item ->
            HistoryCard(item, onItemClick)
        }
        state.errorMessage?.let { message ->
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(message, color = TextSecondary, fontSize = 12.sp)
                    if (state.canRetry) Button(onClick = onRetry, modifier = Modifier.height(48.dp)) { Text("重试") }
                }
            }
        }
        if (state.hasMore) item {
            Button(onClick = onLoadMore, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("加载更多") }
        }
        if (state.loading) item {
            Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BrandGreen, modifier = Modifier.size(28.dp))
            }
        }
    }
}

@Composable
internal fun HistoryCard(item: HistoryItem, onItemClick: (String) -> Unit) {
    Card(
        modifier = Modifier
            .widthIn(max = 350.dp)
            .fillMaxWidth()
            .height(74.dp)
            .testTag("history-${item.sessionId}")
            .clickable(role = Role.Button) { onItemClick(item.sessionId) },
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = AppWhite),
        border = BorderStroke(1.dp, Color(0xFFDEE8E2)),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(50.dp).background(historyColor(item.sessionId), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) { Text("♪", color = Color.White, fontSize = 20.sp) }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.songTitle, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(historyMeta(item), color = TextSecondary, fontSize = 10.sp)
            }
            val scoreShape = RoundedCornerShape(23.dp)
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(statusBackground(item.status), scoreShape)
                    .then(if (item.score != null) Modifier.border(3.dp, BrandGreen, scoreShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(item.score ?: statusLabel(item.status), color = statusColor(item.status), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun RoundControl(label: String, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { role = Role.Button; contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(40.dp).background(Color(0xFFEAF4EE), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
            Text(label, color = TextPrimary, fontSize = 24.sp)
        }
    }
}

internal fun historyMeta(
    item: HistoryItem,
    nowEpochMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val duration = item.durationSeconds?.takeIf { it >= 0 }?.let { "%d:%02d".format(it / 60, it % 60) }
    val updated = runCatching { Instant.ofEpochMilli(item.updatedAtEpochMillis).atZone(zoneId) }.getOrNull()
    val now = runCatching { Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId) }.getOrNull()
    val date = when {
        updated == null || now == null -> null
        updated.toLocalDate() == now.toLocalDate() -> "今天 ${updated.format(DateTimeFormatter.ofPattern("HH:mm"))}"
        else -> updated.format(DateTimeFormatter.ofPattern("M 月 d 日"))
    }
    return listOfNotNull(date, duration).joinToString(" · ")
}

internal fun statusLabel(status: HistoryStatus): String = when (status) {
    HistoryStatus.WAITING_NETWORK -> "等待网络"
    HistoryStatus.QUEUED -> "待上传"
    HistoryStatus.UPLOADING -> "上传中"
    HistoryStatus.WAITING_CALLBACK -> "等待回调"
    HistoryStatus.CONFIRMING -> "确认中"
    HistoryStatus.SUBMITTING -> "提交中"
    HistoryStatus.ANALYZING, HistoryStatus.PROCESSING, HistoryStatus.UPLOADED -> "分析中"
    HistoryStatus.COMPLETED -> "完成"
    HistoryStatus.UPLOAD_FAILED -> "上传失败"
    HistoryStatus.FAILED -> "分析失败"
    HistoryStatus.CANCELLED -> "已取消"
    HistoryStatus.UNKNOWN -> "状态同步中"
}

private fun historyColor(key: String): Color = listOf(
    Color(0xFFC65B86), Color(0xFF3D8EB5), Color(0xFFE67B43), Color(0xFF4CA87C),
)[(key.hashCode() and Int.MAX_VALUE) % 4]

private fun statusBackground(status: HistoryStatus) = if (status in setOf(HistoryStatus.UPLOAD_FAILED, HistoryStatus.FAILED)) Color(0xFFFFECE8) else Color(0xFFE7F8EF)
private fun statusColor(status: HistoryStatus) = if (status in setOf(HistoryStatus.UPLOAD_FAILED, HistoryStatus.FAILED)) Color(0xFFB64735) else BrandGreen
