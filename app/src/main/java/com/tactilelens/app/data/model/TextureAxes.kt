package com.tactilelens.app.data.model

/**
 * Four perceptual floats in [0, 1] that describe a surface.
 *
 * Emitted by the ML model alongside an optional categorical material guess.
 * Drive the renderers' procedural path when [AnalysisResult.material] is null,
 * and modulate per-material recipes when it is non-null.
 */
data class TextureAxes(
    val roughness: Float,
    val density: Float,
    val friction: Float,
    val hardness: Float,
) {
    companion object {
        val Neutral = TextureAxes(0.5f, 0.5f, 0.5f, 0.5f)
    }
}
