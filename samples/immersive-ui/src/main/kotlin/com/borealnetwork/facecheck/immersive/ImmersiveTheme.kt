package com.borealnetwork.facecheck.immersive

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ImmersiveColors = darkColorScheme(
    primary = Color(0xFF4ADE80),
    onPrimary = Color(0xFF04291A),
    primaryContainer = Color(0xFF14532D),
    onPrimaryContainer = Color(0xFFBBF7D0),
    secondary = Color(0xFF60A5FA),
    onSecondary = Color(0xFF06223F),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE6EDF7),
    surface = Color(0xFF111C31),
    onSurface = Color(0xFFE6EDF7),
    surfaceVariant = Color(0xFF1B2841),
    onSurfaceVariant = Color(0xFFAFC0DA),
    error = Color(0xFFF87171),
    onError = Color(0xFF3B0A0A),
    errorContainer = Color(0xFF5B1414),
    onErrorContainer = Color(0xFFFECACA),
    outline = Color(0xFF3B4C6B),
)

@Composable
fun ImmersiveTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ImmersiveColors, content = content)
}
