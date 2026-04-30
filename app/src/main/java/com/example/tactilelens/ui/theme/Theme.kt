package com.example.tactilelens.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = VividBlue,
    secondary = VividBlue,
    tertiary = MistWhite,
    background = DeepSpace,
    surface = PanelInk,
    onPrimary = DeepSpace,
    onSecondary = DeepSpace,
    onTertiary = DeepSpace,
    onBackground = MistWhite,
    onSurface = MistWhite
)

@Composable
fun TestProjectTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
