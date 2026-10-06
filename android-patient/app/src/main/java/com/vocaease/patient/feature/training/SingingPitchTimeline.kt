package com.vocaease.patient.feature.training
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
    val notes=(state.referencePitch as? ReferencePitchState.Ready)?.notes ?: emptyList()
    val viewport = remember { PatientPitchViewport() }
    val songRange = remember(notes) { if (notes.isEmpty()) null else referencePitchRange(notes) }
    val range = songRange ?: viewport.update(state.pitchHistory)

    Column(modifier.testTag("singing-pitch-timeline")) {
        Text("演唱音高",color=Color(0xFF89A094),fontSize=12.sp)
        Canvas(Modifier.fillMaxWidth().weight(1f).semantics {
            contentDescription=state.patientPitch.frequencyHz?.let { "患者实时音高：${it.toInt()} Hz" } ?: "患者实时音高：静音或不稳定"
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
                    drawLine(if(end<axis) Color(0xFF36CB89) else Color(0xFF587265),Offset(x.coerceAtLeast(0f),y),Offset(end.coerceAtMost(size.width),y),8.dp.toPx(),androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
            drawLine(Color(0xFFCEE9DC),Offset(axis,0f),Offset(axis,size.height),1.dp.toPx())
            var previous:Offset?=null
            state.pitchHistory.forEach { sample ->
                val time=songTimeAt(sample.recordingMs,state.playbackAnchors)
                val hz=sample.frequencyHz
                if(hz==null || time==null) previous=null
                else {
                    val point=Offset(timelineX(time,state.playbackPositionMillis,size.width),noteY(pitchToMidi(hz)))
                    if(point.x in 0f..axis) { previous?.let { drawLine(Color.White,it,point,2.dp.toPx()) };previous=point } else previous=null
                }
            }
            state.patientPitch.frequencyHz?.let { hz ->
                val y=noteY(pitchToMidi(hz))
                drawCircle(Color.White.copy(alpha=.18f),10.dp.toPx(),Offset(axis,y));drawCircle(Color.White,4.dp.toPx(),Offset(axis,y))
            }
        }
        if(notes.isEmpty()) Text(when (state.referencePitch) {
            is ReferencePitchState.Failed -> "参考音高加载失败；仍显示您的声音"
            ReferencePitchState.Unaligned -> "伴奏起点未校准；仍显示您的声音"
            else -> "暂无参考音高；仍显示您的声音"
        },color=Color(0xFF89A094),fontSize=11.sp)
    }
}
