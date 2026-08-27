package com.vocaease.patient.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vocaease.patient.ui.theme.AppBackground
import com.vocaease.patient.ui.theme.AppSurfaceVariant
import com.vocaease.patient.ui.theme.AppWhite
import com.vocaease.patient.ui.theme.BrandForest
import com.vocaease.patient.ui.theme.BrandGreen
import com.vocaease.patient.ui.theme.MinimumTouchTargetSize
import com.vocaease.patient.ui.theme.TextPrimary
import com.vocaease.patient.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun ProfileScreen(
    state: ProfileUiState,
    onHistoryClick: () -> Unit,
    onTreatmentPlanClick: () -> Unit,
    onPendingUploadsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasPatientContent = state.patientStatus == ProfilePatientStatus.CONTENT ||
        (state.patientStatus == ProfilePatientStatus.ERROR && state.patientName.isNotBlank())
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackground),
        contentPadding = PaddingValues(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("我的", color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(MinimumTouchTargetSize)
                        .clickable(onClick = onSettingsClick)
                        .semantics {
                            role = Role.Button
                            contentDescription = "设置"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(AppSurfaceVariant, RoundedCornerShape(20.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("⚙", color = TextPrimary, fontSize = 18.sp)
                    }
                }
            }
        }
        if (!hasPatientContent) item {
            Box(
                modifier = Modifier.fillMaxWidth().height(220.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (state.patientStatus == ProfilePatientStatus.ERROR) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.errorMessage ?: "患者信息加载失败，请重试", color = TextSecondary, fontSize = 14.sp)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onRetry, modifier = Modifier.height(MinimumTouchTargetSize)) {
                            Text("重试")
                        }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = BrandGreen)
                        Spacer(Modifier.height(12.dp))
                        Text("正在加载患者信息", color = TextSecondary, fontSize = 14.sp)
                    }
                }
            }
        }
        if (hasPatientContent) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .background(Color(0xFFF5B865), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    SmileIcon()
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        "早上好，${state.patientName.ifBlank { "患者" }}",
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("继续唱出今天的好心情", color = TextSecondary, fontSize = 11.sp)
                }
            }
        }
        if (hasPatientContent && state.errorMessage != null) item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(state.errorMessage, color = TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Button(onClick = onRetry, modifier = Modifier.height(MinimumTouchTargetSize)) {
                    Text("重试")
                }
            }
        }
        if (hasPatientContent) item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                StatisticCard(
                    label = "演唱总歌曲数",
                    value = state.lifetimeCompletedSongs.toString(),
                    unit = "首歌曲",
                    icon = "♪",
                    background = BrandForest,
                    foreground = Color(0xFFF0FFF7),
                    modifier = Modifier.weight(1f),
                )
                StatisticCard(
                    label = "演唱总时长",
                    value = durationValue(state.lifetimeDurationSeconds),
                    unit = durationUnit(state.lifetimeDurationSeconds),
                    icon = "◷",
                    background = Color(0xFFF2C965),
                    foreground = Color(0xFF332508),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (hasPatientContent) item {
            Text(
                "演唱与治疗",
                modifier = Modifier.padding(top = 10.dp),
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (hasPatientContent) item { ProfileEntry("演唱历史", "查看全部记录", onHistoryClick) }
        if (hasPatientContent) item {
            ProfileEntry(
                "治疗计划",
                if (state.hasActiveTreatmentPlan) "查看当前治疗目标" else "暂无计划，请联系医生",
                onTreatmentPlanClick,
            )
        }
        if (hasPatientContent) item {
            ProfileEntry(
                "待上传记录",
                if (state.pendingUploadCount > 0) "${state.pendingUploadCount} 条待处理" else "暂无待处理记录",
                onPendingUploadsClick,
            )
        }
        if (hasPatientContent) item { ProfileEntry("设置", "密码与账户", onSettingsClick) }
    }
}

@Composable
private fun SmileIcon() {
    val color = Color(0xFF6C3B22)
    Canvas(Modifier.size(26.dp)) {
        val line = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(color = color, style = line)
        drawCircle(color = color, radius = 1.4.dp.toPx(), center = center.copy(x = size.width * 0.35f, y = size.height * 0.40f))
        drawCircle(color = color, radius = 1.4.dp.toPx(), center = center.copy(x = size.width * 0.65f, y = size.height * 0.40f))
        drawArc(
            color = color,
            startAngle = 20f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.27f, size.height * 0.34f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.46f, size.height * 0.42f),
            style = line,
        )
    }
}

@Composable
private fun StatisticCard(
    label: String,
    value: String,
    unit: String,
    icon: String,
    background: Color,
    foreground: Color,
    modifier: Modifier,
) {
    Card(
        modifier = modifier.height(116.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = background),
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Column {
                Text(label, color = foreground, fontSize = 11.sp)
                Text(value, color = foreground, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(unit, color = foreground, fontSize = 11.sp)
            }
            Text(
                icon,
                modifier = Modifier.align(Alignment.CenterEnd),
                color = if (background == BrandForest) BrandGreen else Color(0xFF6C4C0C),
                fontSize = 28.sp,
            )
        }
    }
}

@Composable
private fun ProfileEntry(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = AppWhite),
        border = BorderStroke(1.dp, Color(0xFFDEE8E2)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = TextSecondary, fontSize = 11.sp)
            }
            Text("›", color = TextSecondary, fontSize = 22.sp)
        }
    }
}

private fun durationValue(seconds: Int): String = if (seconds >= 3_600) {
    String.format(Locale.CHINA, "%.1f", seconds / 3_600.0)
} else {
    (seconds / 60).toString()
}

private fun durationUnit(seconds: Int): String = if (seconds >= 3_600) "小时" else "分钟"
