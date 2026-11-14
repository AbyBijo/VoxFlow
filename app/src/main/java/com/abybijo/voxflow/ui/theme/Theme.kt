package com.abybijo.voxflow.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight

private val LightColors = lightColorScheme(
    background = LightBackground,
    surface = LightSurface,
    onBackground = LightOnBackground,
    onSurface = LightOnBackground,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    error = LightError,
)

private val DarkColors = darkColorScheme(
    background = DarkBackground,
    surface = DarkSurface,
    onBackground = DarkOnBackground,
    onSurface = DarkOnBackground,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    error = DarkError,
)

private val VoxFlowTypography = Typography(
    headlineLarge = Typography().headlineLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.SemiBold),
)

/**
 * VoxFlow theme: system light/dark, system font, neutral palette.
 * Motion follows the house standard — instant press feedback, no blue
 * overlay on any interactive element (AGENTS.md universal rule).
 */
@Composable
fun VoxFlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = VoxFlowTypography,
        content = content,
    )
}
