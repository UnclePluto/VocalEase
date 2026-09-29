package com.vocaease.patient.feature.training

import android.graphics.Bitmap
import android.graphics.Color
import android.view.Window
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs

internal data class SystemBarMatchReport(
    val statusMatches: Int,
    val navigationMatches: Int,
    val requiredMatches: Int,
    val expectedStatus: Int,
    val expectedNavigation: Int,
) {
    val matches: Boolean
        get() = statusMatches >= requiredMatches && navigationMatches >= requiredMatches

    val description: String
        get() = "系统栏背景多点采样不匹配：status=$statusMatches/$SAMPLE_COUNT " +
            "expected=${colorString(expectedStatus)}; navigation=$navigationMatches/$SAMPLE_COUNT " +
            "expected=${colorString(expectedNavigation)}; required=$requiredMatches"
}

internal fun systemBarMatchReport(
    window: Window,
    screenshot: Bitmap,
    expectedStatus: Int,
    expectedNavigation: Int,
): SystemBarMatchReport {
    val insets = ViewCompat.getRootWindowInsets(window.decorView)
    val statusHeight = insets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
    val navigationHeight = insets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
    val statusY = (statusHeight / 2).coerceIn(1, screenshot.height - 2)
    val navigationY = (screenshot.height - navigationHeight / 2 - 1).coerceIn(1, screenshot.height - 2)
    val sampleXs = (1..SAMPLE_COUNT).map { screenshot.width * it / (SAMPLE_COUNT + 1) }

    return SystemBarMatchReport(
        statusMatches = sampleXs.count { pixelMatches(expectedStatus, screenshot.getPixel(it, statusY)) },
        navigationMatches = sampleXs.count {
            pixelMatches(expectedNavigation, screenshot.getPixel(it, navigationY))
        },
        requiredMatches = REQUIRED_MATCHES,
        expectedStatus = expectedStatus,
        expectedNavigation = expectedNavigation,
    )
}

private fun pixelMatches(expected: Int, actual: Int): Boolean =
    abs(Color.red(expected) - Color.red(actual)) <= COLOR_TOLERANCE &&
        abs(Color.green(expected) - Color.green(actual)) <= COLOR_TOLERANCE &&
        abs(Color.blue(expected) - Color.blue(actual)) <= COLOR_TOLERANCE

private fun colorString(color: Int): String =
    "#%02X%02X%02X".format(Color.red(color), Color.green(color), Color.blue(color))

private const val SAMPLE_COUNT = 9
private const val REQUIRED_MATCHES = 6
private const val COLOR_TOLERANCE = 4
