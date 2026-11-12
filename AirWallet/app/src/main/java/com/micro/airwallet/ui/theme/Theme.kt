package com.micro.airwallet.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = Accent,
    secondary = Link,
    tertiary = Warm,
    background = Canvas,
    surface = Surface,
    onBackground = Ink,
    onSurface = Ink
)

@Composable
fun AirWalletTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content
    )
}
