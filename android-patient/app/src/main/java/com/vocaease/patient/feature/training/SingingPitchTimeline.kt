package com.vocaease.patient.feature.training
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vocaease.patient.core.media.*

fun songTimeAt(recordingMs:Long,anchors:List<PlaybackAnchor>):Long? {
    val anchor=anchors.lastOrNull { it.recordingMs<=recordingMs } ?: return null
    return anchor.songMs + if(anchor.playing) recordingMs-anchor.recordingMs else 0
}

@Composable
fun SingingPitchTimeline(state:RecordingUiState,modifier:Modifier=Modifier) {
    val rawNotes = (state.referencePitch as? ReferencePitchState.Ready)?.notes ?: emptyList()
    val notes = remember(rawNotes) { buildKaraokeGuide(rawNotes) }
    val feedback = karaokeFeedback(notes, state.pitchHistory, state.playbackAnchors, state.patientPitch,
        state.recordingDurationMillis, state.playbackPositionMillis,
        state.recordingState is RecordingState.Recording && !state.switchingMode && state.playbackAnchors.lastOrNull()?.playing == true)
    val glow by animateFloatAsState(if (feedback.isMatching) 1f else 0f, tween(120), label = "pitch-hit-glow")
    val patientHz = state.patientPitch.frequencyHz?.takeIf { it.isFinite() && it > 0 && state.patientPitch.confidence >= .85f }
    val viewport = remember { PatientPitchViewport() }
    val songRange = remember(notes) { if (notes.isEmpty()) null else referencePitchRange(notes) }
    val range = songRange ?: viewport.update(state.pitchHistory)

    Column(modifier.testTag("singing-pitch-timeline")) {
        Text("演唱音高",color=Color(0xFF89A094),fontSize=12.sp)
        Canvas(Modifier.fillMaxWidth().weight(1f).testTag("singing-pitch-canvas").semantics {
            contentDescription = patientHz?.let { "患者实时音高：${it.toInt()} Hz" } ?: "患者实时音高：静音或不稳定"
            stateDescription = if (feedback.isMatching) "唱准，音符点亮" else "未命中，参考音符保持灰色"
        }) {
            val axis=size.width/4f
            val padding=10.dp.toPx().coerceAtMost(size.height/4f)
            fun noteY(midi:Float)=padding+pitchY(midi,range,size.height-padding*2)
            for(midi in range.first.toInt()..range.second.toInt() step 3) {
                val y=noteY(midi.toFloat())
                drawLine(Color(0xFF203B2D),Offset(0f,y),Offset(size.width,y),1f)
            }
            notes.forEach { note ->
                val x=timelineX(note.startMs,state.playbackPositionMillis,size.width);val end=timelineX(note.endMs,state.playbackPositionMillis,size.width)
                if(end>=0 && x<=size.width) {
                    val y=noteY(note.midiNote)
                    drawLine(Color(0xFF66716C), Offset(x.coerceAtLeast(0f), y), Offset(end.coerceAtMost(size.width), y),
                        8.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                    feedback.matchedSpans.forEach { span ->
                        val hitStart = maxOf(note.startMs, span.startMs)
                        val hitEnd = minOf(note.endMs, span.endMs)
                        if (hitEnd > hitStart) {
                            val from = timelineX(hitStart, state.playbackPositionMillis, size.width).coerceIn(0f, size.width)
                            val to = timelineX(hitEnd, state.playbackPositionMillis, size.width).coerceIn(0f, size.width)
                            if (to > from) drawLine(Color(0xFF36CB89), Offset(from, y), Offset(to, y),
                                8.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                        }
                    }
                }
            }
            drawLine(Color(0xFFCEE9DC),Offset(axis,0f),Offset(axis,size.height),1.dp.toPx())
            var previous:Offset?=null
            state.pitchHistory.forEach { sample ->
                val time=songTimeAt(sample.recordingMs,state.playbackAnchors)
                val hz=sample.frequencyHz
                if(hz==null || !hz.isFinite() || hz <= 0 || sample.confidence < .85f || time==null) previous=null
                else {
                    val point=Offset(timelineX(time,state.playbackPositionMillis,size.width),noteY(pitchToMidi(hz)))
                    if(point.x in 0f..axis) {
                        val matched = feedback.matchedSpans.any { time >= it.startMs && time <= it.endMs }
                        previous?.let { drawLine(if (matched) Color(0xFF36CB89) else Color(0xFF909A95), it, point, 2.dp.toPx()) }
                        previous=point
                    } else previous=null
                }
            }
            patientHz?.let { hz ->
                val center = Offset(axis, noteY(pitchToMidi(hz)))
                // 未命中时只保留静态白点；入场光晕只由稳定的真实唱准状态触发。
                if (feedback.isMatching) {
                    val radius = (10 + 10 * glow).dp.toPx()
                    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = .85f),
                        Color(0xFF36CB89).copy(alpha = .45f), Color.Transparent), center, radius), radius, center)
                }
                drawCircle(Color.White, 4.dp.toPx(), center)
            }
        }
        if(notes.isEmpty()) Text(when (state.referencePitch) {
            is ReferencePitchState.Failed -> "参考音高加载失败；仍显示您的声音"
            ReferencePitchState.Unaligned -> "伴奏起点未校准；仍显示您的声音"
            else -> "暂无参考音高；仍显示您的声音"
        },color=Color(0xFF89A094),fontSize=11.sp)
    }
}
