package com.micrelay.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF10B981), // Emerald green
    onPrimary = Color(0xFF022C22),
    primaryContainer = Color(0xFF064E3B),
    onPrimaryContainer = Color(0xFFA7F3D0),
    secondary = Color(0xFF3B82F6), // Accent blue
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFF09090B), // Deep obsidian
    onBackground = Color(0xFFF4F4F5),
    surface = Color(0xFF18181B), // Dark zinc card
    onSurface = Color(0xFFF4F4F5),
    surfaceVariant = Color(0xFF27272A),
    onSurfaceVariant = Color(0xFFA1A1AA),
    error = Color(0xFFEF4444),
    onError = Color(0xFFFFFFFF)
)

@Composable
fun MicRelayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography(),
        content = content
    )
}
