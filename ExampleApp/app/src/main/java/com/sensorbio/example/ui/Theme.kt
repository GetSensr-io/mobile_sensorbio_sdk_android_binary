package com.sensorbio.example.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// SensorBio-branded palette (the default Material purple read poorly on white).
private val Blue = Color(0xFF1565C0)
private val BlueDark = Color(0xFF5B95E5)

private val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    secondary = Color(0xFF00897B),
    background = Color(0xFFF7F8FA),
    surface = Color.White,
    onSurface = Color(0xFF101418),
    onSurfaceVariant = Color(0xFF455058),
)

private val DarkColors = darkColorScheme(
    primary = BlueDark,
    onPrimary = Color(0xFF00264D),
    secondary = Color(0xFF4DB6AC),
    background = Color(0xFF101418),
    surface = Color(0xFF181C20),
)

@Composable
fun SensorBioExampleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
