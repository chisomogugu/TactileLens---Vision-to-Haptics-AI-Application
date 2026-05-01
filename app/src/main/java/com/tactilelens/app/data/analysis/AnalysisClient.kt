package com.tactilelens.app.data.analysis

import android.net.Uri
import com.tactilelens.app.data.model.AnalysisResult

/**
 * Vision-to-axes contract surface. The ML team's API lives behind this.
 *
 * Implementations:
 *  - [MockAnalysisClient] cycles canned axes per material (development only).
 *  - [LiteRTAnalysisClient] runs the real on-device pipeline (U2Net +
 *    EfficientNet-Lite0 + linear head) on the Hexagon NPU when available.
 *
 * The optional [precomputed] segmentation lets the UI hand in a result it
 * already produced for the scanner reveal animation, so the analyzer doesn't
 * run U2Net a second time. Callers without an existing segmentation pass null
 * and the implementation handles segmentation internally (or ignores the
 * parameter, in [MockAnalysisClient]'s case).
 */
interface AnalysisClient {
    suspend fun analyze(
        uri: Uri,
        precomputed: U2NetSegmenter.SegmentationResult? = null,
    ): AnalysisResult
}
