package com.vocaease.patient.feature.catalog

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vocaease.patient.ui.theme.BrandForest
import com.vocaease.patient.ui.theme.BrandGreen

@Composable
fun TreatmentProgressCard(
    progress: TreatmentProgressUi?,
    modifier: Modifier = Modifier,
) {
    val percent = progress?.percent?.coerceIn(0f, 100f) ?: 0f
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(158.dp)
            .clip(RoundedCornerShape(24.dp))
            .testTag("treatment-progress")
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(percent, 0f..100f, 0)
            },
        colors = CardDefaults.cardColors(containerColor = BrandForest),
        shape = RoundedCornerShape(24.dp),
    ) {
        if (progress == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "本周期治疗进度",
                    color = BrandGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "暂无进行中的治疗计划，请联系医生",
                    color = Color(0xFFF2FBF6),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 27.sp,
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 18.dp, top = 18.dp, end = 16.dp, bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    Text(
                        "本周期治疗进度",
                        color = BrandGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${progress.completedCount} / ${progress.targetCount} 次",
                        color = Color(0xFFF2FBF6),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 31.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "治疗第 ${progress.currentWeek} 周 · 继续保持",
                        color = Color(0xFFA8C4B6),
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.width(10.dp))
                ProgressRing(percent)
            }
        }
    }
}

@Composable
private fun ProgressRing(percent: Float) {
    Box(
        modifier = Modifier.size(106.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
            drawArc(
                color = Color(0xFF1C5A43),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = stroke,
            )
            drawArc(
                color = BrandGreen,
                startAngle = -90f,
                sweepAngle = 360f * (percent / 100f),
                useCenter = false,
                style = stroke,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${percent.toInt()}%",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            Text("已完成", color = Color(0xFFBEEAD4), fontSize = 11.sp)
        }
    }
}
