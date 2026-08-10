package com.easyshare.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF1A6B6C)
private val TealDark = Color(0xFF0F3D3E)
private val Sand = Color(0xFFF3EFE7)
private val Ink = Color(0xFF1C1B1A)
private val Mist = Color(0xFFD7E4E4)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Mist,
    onPrimaryContainer = TealDark,
    secondary = TealDark,
    onSecondary = Color.White,
    background = Sand,
    onBackground = Ink,
    surface = Sand,
    onSurface = Ink,
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7BC4C5),
    onPrimary = TealDark,
    primaryContainer = TealDark,
    onPrimaryContainer = Mist,
    secondary = Mist,
    onSecondary = TealDark,
    background = Color(0xFF121414),
    onBackground = Color(0xFFE8E6E1),
    surface = Color(0xFF121414),
    onSurface = Color(0xFFE8E6E1),
    error = Color(0xFFF2B8B5),
)

@Composable
fun EasyShareTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
