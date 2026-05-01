package com.tactilelens.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Dark color scheme wired to the instrument-grade palette. All chroma routed
 * through [Pulse]; surfaces stay neutral. This is intentionally restrictive —
 * we don't want a Material You dynamic color blooming pastel surfaces over our
 * carbon canvas.
 */
private val InstrumentDarkScheme = darkColorScheme(
    primary = Pulse,
    onPrimary = Carbon,
    secondary = Bone,
    onSecondary = Carbon,
    tertiary = Bone,
    onTertiary = Carbon,
    background = Carbon,
    onBackground = Bone,
    surface = Slate,
    onSurface = Bone,
    surfaceVariant = Iron,
    onSurfaceVariant = Mercury,
    outline = Iron,
    outlineVariant = Graphite,
    error = Pulse,
    onError = Carbon,
)

/**
 * Top-level theme. Wraps Material 3 with the instrument palette + IBM Plex
 * type scale. Every screen consumes via `MaterialTheme.colorScheme` /
 * `MaterialTheme.typography`.
 */
@Composable
fun TactileLensTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = InstrumentDarkScheme,
        typography = Typography,
        content = content,
    )
}

@Deprecated(
    "Renamed to TactileLensTheme to match the app brand. Keep call sites compiling for one phase.",
    ReplaceWith("TactileLensTheme(content)"),
)
@Composable
fun TestProjectTheme(content: @Composable () -> Unit) = TactileLensTheme(content = content)
