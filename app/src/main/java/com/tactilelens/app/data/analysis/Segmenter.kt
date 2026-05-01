package com.tactilelens.app.data.analysis

import android.graphics.Bitmap
import android.graphics.Rect
import java.io.Closeable

/**
 * Foreground segmentation contract. One implementation today
 * ([U2NetSegmenter] — class-agnostic salient-object detection on NPU,
 * ~8 ms). Kept as an interface so a future SAM-class swap (MobileSAM,
 * EdgeTAM) doesn't have to touch any callers.
 */
interface Segmenter : Closeable {
    /** "NPU", "CPU", or "NPU/CPU" depending on per-stage delegate binding. */
    val backendLabel: String

    /**
     * Run segmentation on [input]. If [tapX] / [tapY] are valid (>= 0,
     * in original-image pixel coordinates), the segmenter focuses on the
     * object containing that point. Otherwise it falls back to a sensible
     * default (center-of-frame for SAM, global salience for U2Net).
     */
    fun segment(input: Bitmap, tapX: Int = -1, tapY: Int = -1): SegmentationResult
}

/**
 * Output of [Segmenter.segment]. Three derived images plus the bbox in
 * original-image coordinates. The fields exist as separate bitmaps because
 * the scanner reveal animation uses each stage independently.
 */
data class SegmentationResult(
    /** Raw saliency mask, ~320×320, white-on-black. Reveal stage 1. */
    val saliency: Bitmap,
    /** Full-res mask + cyan bbox stroke. Reveal stage 2. */
    val maskWithBbox: Bitmap,
    /** Original photo with non-foreground pixels filled with DeepSpace. Reveal stage 3 + Results display. */
    val cropped: Bitmap,
    /** Foreground bbox in original-image pixel coords. Drives the encoder crop. */
    val bbox: Rect,
    /** Wall-clock millis spent in segmentation inference (encoder + decoder for SAM). */
    val inferenceMs: Long,
)
