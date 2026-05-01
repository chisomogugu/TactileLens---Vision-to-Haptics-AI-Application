package com.tactilelens.app.data.model

/**
 * The typed payload that flows from ML through the app to the renderers.
 *
 * Fields:
 *  - [axes]        ML's four perceptual floats.
 *  - [material]    ML's suggested categorical mapping. Null = open vocabulary
 *                  (renderers fall through to the axis-driven procedural path).
 *  - [confidence]  Top-class confidence in [0, 1]. UI may show; renderers ignore.
 *  - [label]       Raw DTD class label for display, e.g. "fibrous".
 */
data class AnalysisResult(
    val axes: TextureAxes,
    val material: Material?,
    val confidence: Float,
    val label: String,
    val maskBitmap: android.graphics.Bitmap? = null,
    val croppedBitmap: android.graphics.Bitmap? = null
)
