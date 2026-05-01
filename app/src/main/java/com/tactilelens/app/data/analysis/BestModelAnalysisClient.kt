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
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Single-stage classifier client. Loads `best_model.tflite` (Madeline,
 * branch `nou2net`) which bakes the encoder, projection head, and any
 * post-processing into one TFLite graph: input `[1, 224, 224, 3]` ->
 * output `[1, 4]` axes (rough, hard, friction, density).
 *
 * Wins over [LiteRTAnalysisClient]:
 *  - One NPU dispatch instead of two (encoder + Kotlin LinearHead).
 *  - Reportedly more accurate axes, per the teammate who trained it.
 *  - No std-clamp / centroid-retune dance — the head was retrained
 *    end-to-end with the encoder so the output range is whatever the
 *    training labels produced.
 *
 * Same patch crop logic as [LiteRTAnalysisClient]: a 30%-of-frame square
 * centered on the segmenter bbox, so a rocks pile classifies as the pile
 * rather than one rock and uniform textures get a clean swatch.
 *
 * Input normalization matches EfficientNet-Lite training convention:
 * `(pixel/255 - 0.5) / 0.5` per channel -> `[-1, 1]`. Same as the
 * [NormalizeOp(127.5f, 127.5f)] the helper class on the source branch
 * applied to the encoder, so the new end-to-end model should expect
 * identical input prep.
 */
class BestModelAnalysisClient(
    private val context: Context,
    private val segmenter: Segmenter,
) : AnalysisClient, Closeable {

    private val model: LiteRTSession = LiteRTSession(context, MODEL_ASSET)
    private val isNchw: Boolean = model.inputShape.size == 4 && model.inputShape[1] == 3
    private val inputSize: Int = if (isNchw) model.inputShape[2] else model.inputShape[1]

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
        val resized = if (cropped.width == inputSize && cropped.height == inputSize) cropped
                      else Bitmap.createScaledBitmap(cropped, inputSize, inputSize, true)

        val inputBuffer = if (isNchw) bitmapToNchwBuffer(resized) else bitmapToNhwcBuffer(resized)
        val outputBuffer = ByteBuffer
            .allocateDirect(1 * AXIS_DIM * 4)
            .order(ByteOrder.nativeOrder())

        val startNs = System.nanoTime()
        model.run(inputBuffer, outputBuffer)
        val classifierMs = (System.nanoTime() - startNs) / 1_000_000L

        val rawAxes = FloatArray(AXIS_DIM)
        outputBuffer.rewind()
        outputBuffer.asFloatBuffer().get(rawAxes)

        // Output assumed `[rough, hard, friction, density]` in `[0, 1]`,
        // matching the LinearHead output order. If the new model emits a
        // different range or order this is the place to remap.
        val axes = TextureAxes(
            roughness = rawAxes[0].coerceIn(0f, 1f),
            hardness = rawAxes[1].coerceIn(0f, 1f),
            friction = rawAxes[2].coerceIn(0f, 1f),
            density = rawAxes[3].coerceIn(0f, 1f),
        )

        val (material, distance) = MaterialCentroids.classify(axes)
        val confidence = (1f - (distance / MaterialCentroids.CLASSIFICATION_THRESHOLD))
            .coerceIn(0f, 1f)

        val totalMs = segmentationMs + classifierMs
        val backendLabel = combinedBackendLabel(segmenter.backendLabel, model.backendLabel)
        val label = material?.display?.lowercase() ?: "open-vocabulary"

        if (resized !== cropped) resized.recycle()
        cropped.recycle()

        Log.i(
            TAG,
            "analyze[best_model]: patch=$patchBbox segmentation=${segmentationMs}ms " +
                "classifier=${classifierMs}ms total=${totalMs}ms " +
                "raw=(${"%.3f".format(rawAxes[0])}, ${"%.3f".format(rawAxes[1])}, " +
                "${"%.3f".format(rawAxes[2])}, ${"%.3f".format(rawAxes[3])}) " +
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
        runCatching { model.close() }
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ -> decoder.isMutableRequired = false }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
    }.getOrNull()

    private fun clampBbox(b: android.graphics.Rect, w: Int, h: Int): android.graphics.Rect {
        val left = b.left.coerceIn(0, w - 1)
        val top = b.top.coerceIn(0, h - 1)
        val right = b.right.coerceIn(left + 1, w)
        val bottom = b.bottom.coerceIn(top + 1, h)
        return android.graphics.Rect(left, top, right, bottom)
    }

    private fun bitmapToNhwcBuffer(bm: Bitmap): ByteBuffer {
        // (pixel/255 - 0.5) / 0.5 -> [-1, 1] per haptics_model `modeloutput.md`.
        val w = bm.width
        val h = bm.height
        val pixels = IntArray(w * h)
        bm.getPixels(pixels, 0, w, 0, 0, w, h)
        val buf = ByteBuffer.allocateDirect(1 * h * w * 3 * 4).order(ByteOrder.nativeOrder())
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

    private fun bitmapToNchwBuffer(bm: Bitmap): ByteBuffer {
        // Same [-1, 1] normalization, channel-first layout.
        val w = bm.width
        val h = bm.height
        val pixels = IntArray(w * h)
        bm.getPixels(pixels, 0, w, 0, 0, w, h)
        val buf = ByteBuffer.allocateDirect(1 * 3 * h * w * 4).order(ByteOrder.nativeOrder())
        val fb = buf.asFloatBuffer()
        for (i in 0 until w * h) fb.put((((pixels[i] shr 16) and 0xff) / 255f - 0.5f) / 0.5f)
        for (i in 0 until w * h) fb.put((((pixels[i] shr 8) and 0xff) / 255f - 0.5f) / 0.5f)
        for (i in 0 until w * h) fb.put(((pixels[i] and 0xff) / 255f - 0.5f) / 0.5f)
        buf.rewind()
        return buf
    }

    private fun combinedBackendLabel(seg: String, model: String): String =
        if (seg == model) seg else "$seg/$model"

    private fun failureFallback(reason: String): AnalysisResult {
        Log.w(TAG, "analyze[best_model] fallback: $reason")
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
        private const val TAG = "TactileLensBest"
        private const val MODEL_ASSET = "best_model.tflite"
        private const val AXIS_DIM = 4
        private const val TEXTURE_PATCH_FRAC = 0.30f
    }
}
