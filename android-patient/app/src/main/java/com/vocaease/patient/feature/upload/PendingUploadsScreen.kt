package com.vocaease.patient.feature.upload

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.vocaease.patient.ui.theme.AppBackground
import com.vocaease.patient.ui.theme.AppSurfaceVariant
import com.vocaease.patient.ui.theme.AppWhite
import com.vocaease.patient.ui.theme.BrandGreen
import com.vocaease.patient.ui.theme.MinimumTouchTargetSize
import com.vocaease.patient.ui.theme.TextPrimary
import com.vocaease.patient.ui.theme.TextSecondary

data class PendingUploadItem(
    val draftId: String,
    val songTitle: String,
    val statusText: String,
    val progressPercent: Int,
    val canPause: Boolean,
    val canResume: Boolean,
    val canDelete: Boolean,
    val canRetry: Boolean = false,
)

data class PendingUploadsUiState(
    val items: List<PendingUploadItem> = emptyList(),
    val loading: Boolean = false,
    val safeError: String? = null,
)

@Composable
fun PendingUploadsScreen(
    state: PendingUploadsUiState,
    onBack: () -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().background(AppBackground),
        contentPadding = PaddingValues(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.sizeIn(minWidth = MinimumTouchTargetSize, minHeight = MinimumTouchTargetSize)
                        .semantics { role = Role.Button; contentDescription = "返回" },
                ) { Text("‹", fontSize = 28.sp, color = TextPrimary) }
                Text("待上传记录", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            }
        }
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AppSurfaceVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "建议在本地保存期限前完成上传",
                    modifier = Modifier.padding(16.dp),
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
            }
        }
        state.safeError?.let { error ->
            item { Text(error, color = Color(0xFFC33C48), fontSize = 13.sp) }
        }
        if (!state.loading && state.items.isEmpty()) {
            item {
                Text(
                    "暂无待上传记录",
                    modifier = Modifier.fillMaxWidth().padding(vertical = 80.dp),
                    color = TextSecondary,
                    fontSize = 15.sp,
                )
            }
        }
        items(state.items, key = PendingUploadItem::draftId) { item ->
            PendingUploadCard(item, onPause, onResume, onRetry, onDelete)
        }
    }
}

@Composable
private fun PendingUploadCard(
    item: PendingUploadItem,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = AppWhite),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(item.songTitle, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(item.statusText, color = TextSecondary, fontSize = 13.sp)
            if (item.progressPercent in 1..99) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { item.progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = BrandGreen,
                    trackColor = AppSurfaceVariant,
                )
            }
            if (item.canPause || item.canResume || item.canRetry || item.canDelete) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    if (item.canPause) UploadAction("暂停", item, onPause)
                    if (item.canResume) UploadAction("继续", item, onResume)
                    if (item.canRetry) UploadAction("立即重试", item, onRetry)
                    Spacer(Modifier.weight(1f))
                    if (item.canDelete) UploadAction("删除", item, onDelete, destructive = true)
                }
            }
        }
    }
}

@Composable
private fun UploadAction(
    label: String,
    item: PendingUploadItem,
    action: (String) -> Unit,
    destructive: Boolean = false,
) {
    TextButton(
        onClick = { action(item.draftId) },
        modifier = Modifier.sizeIn(minWidth = MinimumTouchTargetSize, minHeight = MinimumTouchTargetSize)
            .semantics { role = Role.Button; contentDescription = "$label ${item.songTitle}" },
    ) {
        Text(label, color = if (destructive) Color(0xFFC33C48) else BrandGreen, fontWeight = FontWeight.Bold)
    }
}
