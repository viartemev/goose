package com.goose.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val DarkColorScheme =
    darkColorScheme(
        primary = GooseAccent,
        onPrimary = GooseTextPrimary,
        secondary = GooseStrainBlue,
        onSecondary = GooseTextPrimary,
        background = GooseDeviceBackground,
        onBackground = GooseTextPrimary,
        surface = GooseSurfaceBackground,
        onSurface = GooseTextPrimary,
        surfaceVariant = GooseSurfaceElevated,
        onSurfaceVariant = GooseTextSecondary,
        outline = GooseSeparator,
        error = GooseRecoveryRed,
    )

// Light mirrors dark — Goose is a dark-first app
private val LightColorScheme = DarkColorScheme

@androidx.compose.runtime.Immutable
data class GooseMetricColors(
    val recoveryHigh: Color = GooseRecoveryGreen,
    val recoveryMid: Color = GooseRecoveryYellow,
    val recoveryLow: Color = GooseRecoveryRed,
    val strain: Color = GooseStrainBlue,
    val sleep: Color = GooseSleepPurple,
    val stress: Color = GooseStressOrange,
    val energy: Color = GooseEnergyTeal,
    val cardio: Color = GooseCardioRed,
    val unavailable: Color = GooseTextTertiary,
) {
    fun recoveryColor(score: Int) =
        when {
            score >= 67 -> recoveryHigh
            score >= 34 -> recoveryMid
            else -> recoveryLow
        }
}

val LocalGooseMetricColors = staticCompositionLocalOf { GooseMetricColors() }

@Suppress("FunctionNaming")
@Composable
fun GooseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    CompositionLocalProvider(LocalGooseMetricColors provides GooseMetricColors()) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = GooseTypography,
            content = content,
        )
    }
}

val metricColors: GooseMetricColors
    @Composable get() = LocalGooseMetricColors.current
