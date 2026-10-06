package com.vocaease.patient.feature.training
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable fun LowerFaceNeckGuide(modifier:Modifier=Modifier) {
    Canvas(modifier.testTag("lower-face-neck-guide")) {
        val w=size.width;val h=size.height
        val path=Path().apply {
            moveTo(w*.28f,h*.16f);lineTo(w*.28f,h*.28f)
            cubicTo(w*.30f,h*.49f,w*.38f,h*.55f,w*.42f,h*.58f)
            lineTo(w*.42f,h*.76f);lineTo(w*.20f,h*.88f)
            moveTo(w*.72f,h*.16f);lineTo(w*.72f,h*.28f)
            cubicTo(w*.70f,h*.49f,w*.62f,h*.55f,w*.58f,h*.58f)
            lineTo(w*.58f,h*.76f);lineTo(w*.80f,h*.88f)
            moveTo(w*.38f,h*.30f);quadraticBezierTo(w*.50f,h*.36f,w*.62f,h*.30f)
        }
        drawPath(path,Color(0xFF46D494),style=Stroke(2.dp.toPx()))
        drawLine(Color(0xAA46D494),androidx.compose.ui.geometry.Offset(w*.36f,h*.16f),androidx.compose.ui.geometry.Offset(w*.64f,h*.16f),1.dp.toPx())
    }
}
