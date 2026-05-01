package com.tactilelens.app.data.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.tactilelens.app.data.model.AnalysisResult
import com.tactilelens.app.data.model.TextureAxes
import java.io.Closeable

/**
 * Real on-device analysis client. Runs the full pipeline:
 *
 *   photo URI
 *     → [U2NetSegmenter]  (foreground bbox)
 *     → crop to bbox + resize to 224x224
 *     → EfficientNet-Lite0 encoder (1280-d features, NPU when bound)
 *     → [LinearHead] (Kotlin port of `linear_head.onnx`: norm + Gemm + sigmoid)
 *     → [TextureAxes] mapped per locked decision Q1-D
 *     → [MaterialCentroids.classify] for canonical-or-null material
 *     → [PrimitiveMapper.map] for the 8-vector haptic gesture-start signature
 *     → [AnalysisResult] with real backendLabel + total inference ms
 *
 * The [U2NetSegmenter] is shared with the UI (passed in via [AppContainer]).
 * If the caller already segmented the photo for the scanner reveal, they can
 * pass that [SegmentationResult] into [analyze] to skip a second
 * NPU run. Otherwise this client segments internally.
 *
 * Encoder TFLite is `assets/efficientnet_lite0.tflite` — AI Hub-compiled fp32
 * with NCHW input `[1, 3, 224, 224]` (AI Hub preserves the original ONNX
 * layout); output is `[1, 1280]`. The NHWC variant produced by `onnx2tf`
 * also works — input layout is auto-detected from the actual tensor shape.
 * Preprocessing matches the EfficientNet-Lite training convention:
 * `(pixel/255 - 0.5) / 0.5` per channel (per `colab_convert_encoder.ipynb`).
 */
class LiteRTAnalysisClient(
    private val context: Context,
    private val segmenter: Segmenter,
) : AnalysisClient, Closeable {

    private val encoder: LiteRTSession = LiteRTSession(context, ENCODER_ASSET)
    private val head: LinearHead = LinearHead(context)

    /**
     * Encoder TFLite layout is determined by how the source ONNX was
     * compiled. AI Hub Workbench preserves the original NCHW
     * `[1, 3, 224, 224]`; onnx2tf transposes to NHWC `[1, 224, 224, 3]`.
     * Detected from the actual input tensor shape so swapping in either
     * variant Just Works.
     */
    private val isNchw: Boolean = encoder.inputShape.size == 4 && encoder.inputShape[1] == 3
    private val encoderInputSize: Int =
        if (isNchw) encoder.inputShape[2] else encoder.inputShape[1]

    override suspend fun analyze(
        uri: Uri,
        precomputed: SegmentationResult?,
    ): AnalysisResult {
        val sourceBitmap = loadBitmapFromUri(uri)
            ?: return failureFallback("could not decode bitmap")
        val photo = if (sourceBitmap.config == Bitmap.Config.HARDWARE) {
            sourceBitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            sourceBitmap
        }

        val segmentation = precomputed ?: segmenter.segment(photo)
        val segmentationMs = segmentation.inferenceMs

        // SAM bbox = the OBJECT under the tap (e.g. one rock from a pile).
        // For texture analysis we want a representative TEXTURE PATCH, not
        // a precise object cutout. So: take a fixed-size square centered
        // on the SAM bbox center. Captures the surrounding context — for
        // a rock pile, that's the pile not just one rock; for sand or any
        // uniform surface, just a clean swatch.
        // The visual SAM mask still drives the Results-screen photo and
        // the reveal animation; only the encoder crop is decoupled here.
        val patchSize = (minOf(photo.width, photo.height) * TEXTURE_PATCH_FRAC).toInt()
        val centerX = (segmentation.bbox.left + segmentation.bbox.right) / 2
        val centerY = (segmentation.bbox.top + segmentation.bbox.bottom) / 2
        val patchBbox = clampBbox(
            android.graphics.Rect(
                centerX - patchSize / 2,
                centerY - patchSize / 2,
                centerX + patchSize / 2,
                centerY + patchSize / 2,
            ),
            photo.width,
            photo.height,
        )
        val cropped = Bitmap.createBitmap(
            photo,
            patchBbox.left,
            patchBbox.top,
            patchBbox.width(),
            patchBbox.height(),
        )
        val resized = if (cropped.width == encoderInputSize &&
            cropped.height == encoderInputSize
        ) {
            cropped
        } else {
            Bitmap.createScaledBitmap(cropped, encoderInputSize, encoderInputSize, true)
        }

        // Direct ByteBuffer for both input and output. Nested Java arrays
        // cost tens of ms in JNI marshal alone (the bridge walks each
        // nested array). A single contiguous direct buffer ships in <5 ms.
        val inputBuffer = if (isNchw) bitmapToNchwBuffer(resized) else bitmapToNhwcBuffer(resized)
        val outputBuffer = java.nio.ByteBuffer
            .allocateDirect(1 * FEATURE_DIM * 4)
            .order(java.nio.ByteOrder.nativeOrder())
        val encoderStartNs = System.nanoTime()
        encoder.run(inputBuffer, outputBuffer)
        val encoderMs = (System.nanoTime() - encoderStartNs) / 1_000_000L
        val features = Array(1) { FloatArray(FEATURE_DIM) }
        outputBuffer.rewind()
        outputBuffer.asFloatBuffer().get(features[0])

        val headStartNs = System.nanoTime()
        val dims = head.project(features[0])
        val headMs = (System.nanoTime() - headStartNs) / 1_000_000L

        // Output order from `linear_head.onnx`: [rough, hard, friction, density].
        val axes = TextureAxes(
            roughness = dims[0],
            hardness = dims[1],
            friction = dims[2],
            density = dims[3],
        )

        val (material, distance) = MaterialCentroids.classify(axes)
        val confidence = (1f - (distance / MaterialCentroids.CLASSIFICATION_THRESHOLD))
            .coerceIn(0f, 1f)

        // Classifier-only latency (encoder + head). The segmenter's time
        // is reported separately on the SEG pill via SegmentationResult.
        // inferenceMs, so summing it here would double-count it on the
        // CLF pill.
        val classifierMs = encoderMs + headMs
        val totalMs = segmentationMs + classifierMs
        val backendLabel = combinedBackendLabel(segmenter.backendLabel, encoder.backendLabel)
        val label = material?.display?.lowercase() ?: "open-vocabulary"

        // Recycle scratch bitmaps. Don't recycle [photo] — when we synthesized
        // it from a HARDWARE bitmap it's ours, but when the caller passed in a
        // software bitmap recycling it would surprise them. Cheap to leak; GC
        // collects on the next allocation pressure.
        if (resized !== cropped) resized.recycle()
        cropped.recycle()

        Log.i(
            TAG,
            "analyze: patch=$patchBbox segmentation=${segmentationMs}ms " +
                "encoder=${encoderMs}ms head=${headMs}ms classifier=${classifierMs}ms " +
                "total=${totalMs}ms " +
                "axes=(r=${"%.2f".format(axes.roughness)}, " +
                "h=${"%.2f".format(axes.hardness)}, " +
                "f=${"%.2f".format(axes.friction)}, " +
                "d=${"%.2f".format(axes.density)}) " +
                "material=${material?.display ?: "null"} conf=${"%.2f".format(confidence)} " +
                "backend=$backendLabel",
        )

        return AnalysisResult(
            axes = axes,
            material = material,
            confidence = confidence,
            label = label,
            backendLabel = backendLabel,
            inferenceLatencyMs = classifierMs,
            primitiveWeights = PrimitiveMapper.map(axes),
        )
    }

    override fun close() {
        runCatching { encoder.close() }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun loadBitmapFromUri(uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.isMutableRequired = false
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
    }.getOrNull()

    private fun clampBbox(b: android.graphics.Rect, w: Int, h: Int): android.graphics.Rect {
        // U2Net occasionally returns a degenerate (0-area) bbox on
        // very-low-saliency photos. Fall back to the full frame so the
        // encoder at least sees something instead of crashing on a 0x0
        // createBitmap call.
        val left = b.left.coerceIn(0, w - 1)
        val top = b.top.coerceIn(0, h - 1)
        val right = b.right.coerceIn(left + 1, w)
        val bottom = b.bottom.coerceIn(top + 1, h)
        return android.graphics.Rect(left, top, right, bottom)
    }

    private fun bitmapToNhwcBuffer(bm: Bitmap): java.nio.ByteBuffer {
        // [1, H, W, 3] float32; pixel/255 then (x - 0.5) / 0.5 -> [-1, 1].
        val w = bm.width
        val h = bm.height
        val pixels = IntArray(w * h)
        bm.getPixels(pixels, 0, w, 0, 0, w, h)
        val buf = java.nio.ByteBuffer
            .allocateDirect(1 * h * w * 3 * 4)
            .order(java.nio.ByteOrder.nativeOrder())
        val fb = buf.asFloatBuffer()
        for (i in 0 until w * h) {
            val p = pixels[i]
            fb.put((((p shr 16) and 0xff) / 255f - 0.5f) / 0.5f)
            fb.put((((p shr 8) and 0xff) / 255f - 0.5f) / 0.5f)
            fb.put(((p and 0xff) / 255f - 0.5f) / 0.5f)
        }
        buf.rewind()
        return buf
    }

    private fun bitmapToNchwBuffer(bm: Bitmap): java.nio.ByteBuffer {
        // [1, 3, H, W] float32; same per-channel normalization as NHWC.
        val w = bm.width
        val h = bm.height
        val pixels = IntArray(w * h)
        bm.getPixels(pixels, 0, w, 0, 0, w, h)
        val buf = java.nio.ByteBuffer
            .allocateDirect(1 * 3 * h * w * 4)
            .order(java.nio.ByteOrder.nativeOrder())
        val fb = buf.asFloatBuffer()
        // Channel-first: write all R, then all G, then all B.
        for (i in 0 until w * h) {
            fb.put((((pixels[i] shr 16) and 0xff) / 255f - 0.5f) / 0.5f)
        }
        for (i in 0 until w * h) {
            fb.put((((pixels[i] shr 8) and 0xff) / 255f - 0.5f) / 0.5f)
        }
        for (i in 0 until w * h) {
            fb.put(((pixels[i] and 0xff) / 255f - 0.5f) / 0.5f)
        }
        buf.rewind()
        return buf
    }

    private fun combinedBackendLabel(seg: String, enc: String): String =
        if (seg == enc) seg else "$seg/$enc"

    private fun failureFallback(reason: String): AnalysisResult {
        Log.w(TAG, "analyze fallback: $reason")
        val axes = TextureAxes.Neutral
        return AnalysisResult(
            axes = axes,
            material = null,
            confidence = 0f,
            label = "unknown",
            backendLabel = "ERR",
            inferenceLatencyMs = 0L,
            primitiveWeights = PrimitiveMapper.map(axes),
        )
    }

    private companion object {
        private const val TAG = "TactileLensClient"
        private const val ENCODER_ASSET = "efficientnet_lite0.tflite"
        private const val FEATURE_DIM = 1280
        /**
         * Encoder crop = this fraction of min(photo.width, photo.height),
         * centered on the SAM bbox center. ~30% works well for texture
         * sampling: large enough to include multiple rocks/grain/threads,
         * small enough to exclude unrelated background. Tune up if the
         * encoder feels under-contextualized, down if it picks up too
         * much off-target content.
         */
        private const val TEXTURE_PATCH_FRAC = 0.30f
    }
}
