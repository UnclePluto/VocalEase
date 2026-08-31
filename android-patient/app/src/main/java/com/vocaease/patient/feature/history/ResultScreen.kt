package com.vocaease.patient.feature.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vocaease.patient.ui.theme.AppBackground
import com.vocaease.patient.ui.theme.AppWhite
import com.vocaease.patient.ui.theme.BrandGreen
import com.vocaease.patient.ui.theme.TextPrimary
import com.vocaease.patient.ui.theme.TextSecondary

data class ResultScreenState(
    val loading: Boolean = false,
    val content: ResultUiModel? = null,
    val errorMessage: String? = null,
    val retrying: Boolean = false,
)

@Composable
fun ResultScreen(
    state: ResultScreenState,
    videoContent: @Composable () -> Unit,
    onBack: () -> Unit,
    onRetryLoad: () -> Unit,
    onRetryAnalysis: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().background(AppBackground).testTag("result-root"),
        contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Box(Modifier.fillMaxWidth().height(48.dp)) {
                RoundControl("‹", "返回", onBack)
                Text(
                    "演唱回顾",
                    modifier = Modifier.align(Alignment.Center),
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (state.loading && state.content == null) item {
            Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BrandGreen)
            }
        }
        if (state.content == null && state.errorMessage != null) item {
            Column(Modifier.fillMaxWidth().height(300.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(state.errorMessage, color = TextSecondary, fontSize = 14.sp)
                Button(onClick = onRetryLoad, modifier = Modifier.height(48.dp)) { Text("重试") }
            }
        }
        state.content?.let { result ->
            when (result.contentState) {
                ResultContentState.COMPLETED -> completedContent(result, videoContent)
                ResultContentState.FAILED -> failedContent(result, state.retrying, onRetryAnalysis)
                ResultContentState.CANCELLED -> item { StatusCard("本次分析已取消") }
                ResultContentState.PROCESSING, ResultContentState.RESULT_SYNCING -> item {
                    StatusCard(if (result.contentState == ResultContentState.RESULT_SYNCING) "结果同步中" else "分析中，请稍候")
                }
                ResultContentState.UNKNOWN -> item { StatusCard("状态同步中") }
            }
        }
        if (state.content != null && state.errorMessage != null) item {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(state.errorMessage, color = TextSecondary, fontSize = 14.sp)
                Button(onClick = onRetryLoad, modifier = Modifier.height(48.dp)) { Text("立即重试") }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.completedContent(
    result: ResultUiModel,
    videoContent: @Composable () -> Unit,
) {
    item {
        Column(Modifier.widthIn(max = 350.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${result.songTitle}  ·  演唱得分", color = BrandGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            result.overallScore?.let { Text(it, color = BrandGreen, fontSize = 58.sp, fontWeight = FontWeight.Bold, lineHeight = 68.sp) }
            result.mockLabel?.let {
                Text(it, color = TextSecondary, fontSize = 11.sp, modifier = Modifier.background(Color(0xFFEAF4EE), RoundedCornerShape(10.dp)).padding(horizontal = 9.dp, vertical = 3.dp))
            }
        }
    }
    item {
        Box(
            modifier = Modifier.widthIn(max = 350.dp).fillMaxWidth().height(192.dp).background(Color(0xFF2A4437), RoundedCornerShape(22.dp)).testTag("result-video"),
            contentAlignment = Alignment.Center,
        ) { videoContent() }
    }
    item {
        Card(
            modifier = Modifier.widthIn(max = 350.dp).fillMaxWidth().height(176.dp), shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = AppWhite), border = BorderStroke(1.dp, Color(0xFFDEE8E2)),
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("本次表现", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                listOf("音准", "节奏", "稳定度").forEachIndexed { index, label ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(result.componentScores.getOrElse(index) { "暂无单项评分" }, color = TextPrimary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
    item {
        Card(
            modifier = Modifier.widthIn(max = 350.dp).fillMaxWidth().height(174.dp), shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = AppWhite), border = BorderStroke(1.dp, Color(0xFFDEE8E2)),
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("音准回顾", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                if (result.pitchPoints.isNotEmpty()) {
                    PitchChart(result.pitchPoints, Modifier.fillMaxWidth().height(100.dp))
                } else {
                    Box(
                        Modifier.fillMaxWidth().height(100.dp).background(Color(0xFFEAF4EE), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text(result.pitchMessage ?: "暂无音准曲线", color = TextSecondary, fontSize = 12.sp) }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.failedContent(
    result: ResultUiModel,
    retrying: Boolean,
    onRetryAnalysis: () -> Unit,
) {
    item {
        Column(
            Modifier.fillMaxWidth().height(300.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(result.safeFailureSummary ?: "分析暂时失败，请稍后重试", color = TextSecondary, fontSize = 14.sp)
            Spacer(Modifier.size(12.dp))
            if (result.canRetry) Button(onClick = onRetryAnalysis, enabled = !retrying, modifier = Modifier.height(48.dp)) {
                Text(if (retrying) "正在重试" else "重新分析")
            }
        }
    }
}

@Composable
private fun StatusCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth().height(220.dp), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = AppWhite), border = BorderStroke(1.dp, Color(0xFFDEE8E2)),
    ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(message, color = TextSecondary, fontSize = 14.sp) } }
}
