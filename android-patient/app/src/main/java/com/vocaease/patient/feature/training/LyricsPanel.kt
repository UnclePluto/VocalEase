package com.vocaease.patient.feature.training

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vocaease.patient.ui.theme.AppSurfaceVariant
import com.vocaease.patient.ui.theme.TextPrimary
import com.vocaease.patient.ui.theme.TextSecondary

@Composable
fun PreparationLyrics(state: LyricsState, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(if(state is LyricsState.Ready) 160.dp else 104.dp)
        .background(AppSurfaceVariant, RoundedCornerShape(22.dp)),contentAlignment=Alignment.Center) {
        if(state is LyricsState.Ready) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp,vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                items(state.lines) { line ->
                    Text(line.text, Modifier.fillMaxWidth().padding(vertical=4.dp),color=TextPrimary,
                        textAlign=TextAlign.Center,fontSize=14.sp,lineHeight=22.sp)
                }
            }
        } else LyricsStatus(state,TextSecondary,onRetry)
    }
}

@Composable
fun RecordingLyrics(state: LyricsState, positionMillis: Long, onRetry: () -> Unit) {
    val color = Color(0xFFF4FFF8)
    if(state is LyricsState.Ready) {
        val index = state.activeIndex(positionMillis)
        val text = if(index < 0) "前奏" else state.lines[index].text.ifBlank { "间奏" }
        Text(text,color=color,fontSize=20.sp,lineHeight=24.sp,fontWeight=FontWeight.Bold,
            textAlign=TextAlign.Center,maxLines=2,overflow=TextOverflow.Ellipsis)
    } else LyricsStatus(state,color,onRetry)
}

@Composable
private fun LyricsStatus(state: LyricsState, color: Color, onRetry: () -> Unit) {
    Row(verticalAlignment=Alignment.CenterVertically) {
        Text(when(state) {
            LyricsState.Loading -> "歌词加载中…"
            LyricsState.Failed -> "歌词加载失败"
            else -> "歌词暂未提供"
        },color=color,fontSize=14.sp)
        if(state == LyricsState.Failed) TextButton(onClick=onRetry) { Text("重试歌词") }
    }
}
