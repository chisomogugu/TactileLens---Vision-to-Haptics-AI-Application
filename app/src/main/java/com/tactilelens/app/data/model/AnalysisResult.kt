package com.tactilelens.app.data.model

/**
 * The typed payload that flows from ML through the app to the renderers.
 *
 * Fields:
 *  - [axes]               ML's four perceptual floats.
 *  - [material]           ML's suggested categorical mapping. Null = open vocabulary
 *                         (renderers fall through to the axis-driven procedural path).
 *  - [confidence]         Top-class confidence in [0, 1]. UI may show; renderers ignore.
 *  - [label]              Raw DTD class label for display, e.g. "fibrous".
 *  - [backendLabel]       Which delegate executed inference: "NPU", "GPU", "CPU", or "MOCK".
 *                         Drives the persistent latency pill on the Results screen.
 *  - [inferenceLatencyMs] Classifier wall-clock time in milliseconds
 *                         (encoder + linear head). Segmenter time is reported
 *                         separately on `SegmentationResult.inferenceMs` so
 *                         the two stages don't double-count on the UI.
 *  - [primitiveWeights]   8-element weighted-primitive vector from `map_primitives()`,
 *                         summing to 1.0. Populated by `LiteRTAnalysisClient`. Used by
 *                         the haptic renderer ONLY for the null-material (open-vocab)
 *                         gesture-start signature per locked decision Q7-B. Null when
 *                         not available.
 */
data class AnalysisResult(
    val axes: TextureAxes,
    val material: Material?,
    val confidence: Float,
    val label: String,
    val backendLabel: String = "MOCK",
    val inferenceLatencyMs: Long = 0L,
    val primitiveWeights: FloatArray? = null,
)
