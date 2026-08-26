package com.vocaease.patient.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val VocaEaseLightColors = lightColorScheme(
    primary = BrandGreen,
    onPrimary = AppWhite,
    primaryContainer = AppSurfaceVariant,
    onPrimaryContainer = BrandForest,
    secondary = BrandGreenDark,
    onSecondary = AppWhite,
    secondaryContainer = AppSurfaceVariant,
    onSecondaryContainer = BrandForest,
    background = AppBackground,
    onBackground = TextPrimary,
    surface = AppWhite,
    onSurface = TextPrimary,
    surfaceVariant = AppSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = AppOutline,
    error = AppError,
    onError = AppWhite,
)

@Composable
fun VocaEaseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VocaEaseLightColors,
        typography = VocaEaseTypography,
        shapes = VocaEaseShapes,
        content = content,
    )
}
