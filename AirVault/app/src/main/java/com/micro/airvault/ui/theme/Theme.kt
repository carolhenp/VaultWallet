package com.micro.airvault.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val VaultColors = darkColorScheme(
    primary = Accent,
    onPrimary = Ink,
    secondary = Warm,
    background = Ink,
    onBackground = Paper,
    surface = Surface,
    onSurface = Paper,
    surfaceVariant = Surface,
    onSurfaceVariant = Muted
)

@Composable
fun AirVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VaultColors,
        typography = Typography,
        content = content
    )
}
