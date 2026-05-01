package com.tactilelens.app.data.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.io.Closeable

/**
 * Foreground bounding-box detector backed by ML Kit Object Detection.
 * Replaces the previous U2Net TFLite segmenter; ~50 ms vs ~380 ms.
 *
 * Public API is identical to the old implementation so [LiteRTAnalysisClient],
 * AppContainer, and MainActivity need no changes.
 *
 *  - [SegmentationResult.bbox]         — largest detected object, or full frame
 *  - [SegmentationResult.saliency]     — synthetic 320×320 white-on-black mask
 *  - [SegmentationResult.maskWithBbox] — full-res mask + cyan bbox stroke
 *  - [SegmentationResult.cropped]      — original photo with background blacked out
 */
class U2NetSegmenter(
    @Suppress("UNUSED_PARAMETER") context: Context,
) : Closeable {

    private val detector: ObjectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableMultipleObjects()
            .build()
    )

    /** Always "MLKit" — consumed by [LiteRTAnalysisClient] for the backend badge. */
    val backendLabel: String get() = "MLKit"

    data class SegmentationResult(
        /** Synthetic 320×320 grayscale mask: white inside bbox, black outside. */
        val saliency: Bitmap,
        /**
         * Full-resolution binary mask with the bounding box drawn as a cyan
         * rectangle — used for the scanner reveal animation middle stage.
         */
        val maskWithBbox: Bitmap,
        /**
         * Full-resolution photo with DeepSpace background fill outside the bbox.
         * Same dimensions as the input bitmap so it composites naturally in the UI.
         */
        val cropped: Bitmap,
        /** Bounding box of the primary object in original-image coordinates. */
        val bbox: Rect,
        /** Wall-clock millis spent in ML Kit inference for this frame. */
        val inferenceMs: Long,
    )

    fun segment(input: Bitmap): SegmentationResult {
        val softInput = if (input.config == Bitmap.Config.HARDWARE) {
            input.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            input
        }

        val startMs = System.currentTimeMillis()
        val mlImage = InputImage.fromBitmap(softInput, 0)
        val objects = Tasks.await(detector.process(mlImage))
        val inferenceMs = System.currentTimeMillis() - startMs

        // Use the largest detected object; fall back to the full frame so the
        // encoder always receives a valid (non-degenerate) crop region.
        val raw: Rect = objects
            .maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
            ?.boundingBox
            ?: Rect(0, 0, softInput.width, softInput.height)

        val bbox = Rect(
            raw.left.coerceIn(0, softInput.width - 1),
            raw.top.coerceIn(0, softInput.height - 1),
            raw.right.coerceIn(1, softInput.width),
            raw.bottom.coerceIn(1, softInput.height),
        )

        Log.i(
            TAG,
            "segment: ${softInput.width}x${softInput.height} -> bbox=$bbox " +
                "objects=${objects.size} inference=${inferenceMs}ms (MLKit)",
        )

        return SegmentationResult(
            saliency = buildSaliencyBitmap(bbox, softInput.width, softInput.height),
            maskWithBbox = buildMaskWithBbox(bbox, softInput.width, softInput.height),
            cropped = buildCroppedBitmap(softInput, bbox),
            bbox = bbox,
            inferenceMs = inferenceMs,
        )
    }

    override fun close() {
        runCatching { detector.close() }
    }

    // ------------------------------------------------------------------
    // Synthetic bitmap builders
    // ------------------------------------------------------------------

    /** 320×320 white-on-black mask scaled from the original bbox. */
    private fun buildSaliencyBitmap(bbox: Rect, origW: Int, origH: Int): Bitmap {
        val outW = 320; val outH = 320
        val scaleX = outW.toFloat() / origW
        val scaleY = outH.toFloat() / origH
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        canvas.drawRect(
            bbox.left * scaleX, bbox.top * scaleY,
            bbox.right * scaleX, bbox.bottom * scaleY,
            Paint().apply { color = Color.WHITE; style = Paint.Style.FILL },
        )
        return bmp
    }

    /** Full-resolution binary mask with a cyan bbox stroke on top. */
    private fun buildMaskWithBbox(bbox: Rect, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        canvas.drawRect(
            bbox.left.toFloat(), bbox.top.toFloat(),
            bbox.right.toFloat(), bbox.bottom.toFloat(),
            Paint().apply { color = Color.WHITE; style = Paint.Style.FILL },
        )
        val strokePx = (minOf(w, h) * 0.005f).coerceAtLeast(2f)
        canvas.drawRect(
            bbox.left.toFloat(), bbox.top.toFloat(),
            bbox.right.toFloat(), bbox.bottom.toFloat(),
            Paint().apply {
                color = Color.argb(255, 60, 220, 255)
                style = Paint.Style.STROKE
                strokeWidth = strokePx
                isAntiAlias = true
            },
        )
        return bmp
    }

    /**
     * Full-resolution photo with DeepSpace fill outside the bbox.
     * Keeps the same visual style as the old mask-composited cropped bitmap.
     */
    private fun buildCroppedBitmap(original: Bitmap, bbox: Rect): Bitmap {
        val output = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(BACKGROUND_FILL)
        canvas.drawBitmap(original, bbox, bbox, null)
        return output
    }

    private companion object {
        private const val TAG = "TactileLensU2Net"
        private const val BACKGROUND_FILL: Int = 0xFF07131D.toInt()
    }
}
