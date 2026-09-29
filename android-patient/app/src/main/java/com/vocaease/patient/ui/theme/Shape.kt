package com.vocaease.patient.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val Radius8 = 8.dp
val Radius12 = 12.dp
val Radius16 = 16.dp
val Radius20 = 20.dp
val Radius28 = 28.dp
val MinimumTouchTargetSize = 48.dp

val VocaEaseShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius8),
    small = RoundedCornerShape(Radius8),
    medium = RoundedCornerShape(Radius12),
    large = RoundedCornerShape(Radius16),
    extraLarge = RoundedCornerShape(Radius28),
)
