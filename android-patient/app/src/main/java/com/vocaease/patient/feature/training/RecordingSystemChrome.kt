package com.vocaease.patient.feature.training

import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.core.view.WindowCompat

private const val RECORDING_SYSTEM_CHROME = 0xFF06100B.toInt()

@Suppress("DEPRECATION")
@Composable
fun RecordingSystemChrome(window: Window) {
    DisposableEffect(window) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val previousStatusColor = window.statusBarColor
        val previousNavigationColor = window.navigationBarColor
        val previousLightStatus = controller.isAppearanceLightStatusBars
        val previousLightNavigation = controller.isAppearanceLightNavigationBars
        val previousStatusContrast = window.isStatusBarContrastEnforced
        val previousNavigationContrast = window.isNavigationBarContrastEnforced

        window.statusBarColor = RECORDING_SYSTEM_CHROME
        window.navigationBarColor = RECORDING_SYSTEM_CHROME
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false

        onDispose {
            window.statusBarColor = previousStatusColor
            window.navigationBarColor = previousNavigationColor
            window.isStatusBarContrastEnforced = previousStatusContrast
            window.isNavigationBarContrastEnforced = previousNavigationContrast
            controller.isAppearanceLightStatusBars = previousLightStatus
            controller.isAppearanceLightNavigationBars = previousLightNavigation
        }
    }
}
