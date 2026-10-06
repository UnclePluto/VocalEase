package com.vocaease.patient.feature.training

fun timelineX(noteMs: Long, positionMs: Long, widthPx: Float): Float = widthPx / 4f + (noteMs - positionMs) * widthPx / 8000f
fun pitchRange(notes: List<Float>): Pair<Float, Float> = if (notes.isEmpty()) 36f to 84f else
    (notes.min() - 3f).coerceAtLeast(0f) to (notes.max() + 3f).coerceAtMost(127f)
fun pitchY(midi: Float, range: Pair<Float, Float>, height: Float): Float = height * (1 - ((midi - range.first) / (range.second - range.first).coerceAtLeast(1f)).coerceIn(0f, 1f))
