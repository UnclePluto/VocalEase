package com.vocaease.patient.feature.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun PitchChart(points: List<PitchPoint>, modifier: Modifier = Modifier) {
    require(points.isNotEmpty())
    Canvas(
        modifier = modifier
            .background(Color(0xFFEAF4EE), RoundedCornerShape(16.dp))
            .semantics { contentDescription = "真实音准曲线" },
    ) {
        val min = points.minOf { it.pitchHz }
        val max = points.maxOf { it.pitchHz }
        val pitchRange = (max - min).takeIf { it > 0f } ?: 1f
        val timeMax = points.last().timeMillis.coerceAtLeast(1)
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = size.width * point.timeMillis.toFloat() / timeMax.toFloat()
            val y = size.height - (size.height * (point.pitchHz - min) / pitchRange)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Color(0xFFFFFFFF), style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round))
        drawPath(path, Color(0xFF28C985), style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
}
