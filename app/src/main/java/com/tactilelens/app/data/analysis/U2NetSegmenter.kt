package com.tactilelens.app.data.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.util.Log
import java.io.Closeable

/**
 * U2Net foreground segmenter. Loads `u2net_small.tflite` once via
 * [LiteRTSession] (NPU when available) and exposes `segment(bitmap)`
 * which returns three derived images:
 *
 *  1. Saliency map: the model's raw single-channel probability output,
 *     visualized as grayscale at the model's working resolution.
 *  2. Mask + bbox: the binary foreground mask plus the bounding box
 *     of the foreground region.
 *  3. Cropped: the input photo cropped to the bbox with non-foreground
 *     pixels alpha-multiplied by the mask, sized to the bbox region.
 *
 * The encoder pipeline downstream consumes the cropped bitmap. The
 * scanner reveal animation uses the saliency + cropped overlays in
 * place of the mock thermal/depth assets.
 */
class U2NetSegmenter(context: Context) : Closeable {

    private val session = LiteRTSession(context, ASSET_NAME)

    /** "NPU" or "CPU" — propagated from the underlying [LiteRTSession]. */
    val backendLabel: String get() = session.backendLabel

    data class SegmentationResult(
        /** Raw saliency, model resolution (e.g. 320x320), grayscale. */
        val saliency: Bitmap,
        /**
         * Binary foreground mask AT ORIGINAL RESOLUTION with the bounding
         * box overlaid as a thin colored rectangle. Used as the middle
         * stage of the scanner reveal so the bbox is actually visible.
         */
        val maskWithBbox: Bitmap,
        /**
         * Full-original-size photo with the saliency mask applied as alpha.
         * Same dimensions as the input bitmap so it composites naturally
         * into a UI canvas designed for the original aspect ratio. Phase 4's
         * encoder pipeline will further crop this to [bbox] before resizing
         * to 224x224 for EfficientNet.
         */
        val cropped: Bitmap,
        /** The bounding box of the foreground in original-image coordinates. */
        val bbox: Rect,
        /** Wall-clock millis spent in `Interpreter.run()` for this segmentation. */
        val inferenceMs: Long,
    )

    fun segment(input: Bitmap): SegmentationResult {
        // ImageDecoder on Android 9+ defaults to hardware bitmaps which
        // live on the GPU and reject getPixels(). Convert to a software
        // ARGB_8888 once at the entry so every downstream pixel access
        // (encoder input, saliency upscale, mask paint) just works.
        val softInput = if (input.config == Bitmap.Config.HARDWARE) {
            input.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            input
        }

        // Resize photo to model input. Output shape from session is
        // either NHWC [1,H,W,1] or NHWC [1,H,W,3] depending on conversion.
        val inH = session.inputShape[1]
        val inW = session.inputShape[2]
        val resized = Bitmap.createScaledBitmap(softInput, inW, inH, true)

        val inputTensor = bitmapToFloatNhwc(resized, inW, inH)
        val outChannels = if (session.outputShape.size >= 4) session.outputShape[3] else 1
        val outH = session.outputShape[1]
        val outW = session.outputShape[2]
        val outputTensor = Array(1) { Array(outH) { Array(outW) { FloatArray(outChannels) } } }

        session.run(inputTensor, outputTensor)
        val ms = session.lastInferenceMs()

        // Reduce multi-channel output (rare for U2Net, but defensive) to a
        // single saliency channel by taking max across channels.
        val saliencyArray = FloatArray(outH * outW)
        for (y in 0 until outH) for (x in 0 until outW) {
            var v = outputTensor[0][y][x][0]
            for (c in 1 until outChannels) {
                val cv = outputTensor[0][y][x][c]
                if (cv > v) v = cv
            }
            saliencyArray[y * outW + x] = v.coerceIn(0f, 1f)
        }

        val saliencyBmp = saliencyToBitmap(saliencyArray, outW, outH)
        val bboxAtModel = boundingBoxOfMask(saliencyArray, outW, outH, threshold = 0.5f)
        val bboxAtOriginal = scaleRect(bboxAtModel, outW, outH, softInput.width, softInput.height)
        val maskWithBboxBmp = maskWithBboxAtOriginalSize(
            saliency = saliencyArray,
            modelW = outW,
            modelH = outH,
            originalW = softInput.width,
            originalH = softInput.height,
            bbox = bboxAtOriginal,
        )
        val croppedBmp = cropMaskedForeground(softInput, saliencyArray, outW, outH, bboxAtOriginal)

        Log.i(
            TAG,
            "segment: ${softInput.width}x${softInput.height} -> bbox=$bboxAtOriginal inference=${ms}ms ($backendLabel)",
        )

        return SegmentationResult(
            saliency = saliencyBmp,
            maskWithBbox = maskWithBboxBmp,
            cropped = croppedBmp,
            bbox = bboxAtOriginal,
            inferenceMs = ms,
        )
    }

    override fun close() = session.close()

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun bitmapToFloatNhwc(bm: Bitmap, w: Int, h: Int): Array<Array<Array<FloatArray>>> {
        // U2Net training uses ImageNet-style normalization: pixel/255 -> [0,1].
        // Per the U2NetModel/tactilelens.ipynb conversion, no further
        // mean/std subtraction is applied at this conversion step.
        val out = Array(1) { Array(h) { Array(w) { FloatArray(3) } } }
        val pixels = IntArray(w * h)
        bm.getPixels(pixels, 0, w, 0, 0, w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val p = pixels[y * w + x]
            out[0][y][x][0] = ((p shr 16) and 0xff) / 255f
            out[0][y][x][1] = ((p shr 8) and 0xff) / 255f
            out[0][y][x][2] = (p and 0xff) / 255f
        }
        return out
    }

    private fun saliencyToBitmap(saliency: FloatArray, w: Int, h: Int): Bitmap {
        val pixels = IntArray(w * h)
        for (i in saliency.indices) {
            val v = (saliency[i] * 255f).toInt().coerceIn(0, 255)
            pixels[i] = Color.argb(255, v, v, v)
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun maskWithBboxAtOriginalSize(
        saliency: FloatArray,
        modelW: Int,
        modelH: Int,
        originalW: Int,
        originalH: Int,
        bbox: Rect,
    ): Bitmap {
        // Build the binary mask at model resolution, scale to original
        // photo dimensions so it composites with the same aspect as
        // saliency / cropped, then draw the bbox rectangle on top.
        val pixels = IntArray(modelW * modelH)
        for (i in saliency.indices) {
            pixels[i] = if (saliency[i] >= 0.5f) Color.WHITE else Color.BLACK
        }
        val small = Bitmap.createBitmap(modelW, modelH, Bitmap.Config.ARGB_8888)
        small.setPixels(pixels, 0, modelW, 0, 0, modelW, modelH)
        val scaled = Bitmap.createScaledBitmap(small, originalW, originalH, true)
        small.recycle()

        val output = Bitmap.createBitmap(originalW, originalH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawBitmap(scaled, 0f, 0f, null)
        scaled.recycle()

        // Cyan bbox stroke; thickness scales with image size so it stays
        // visible regardless of source resolution.
        val strokePx = (minOf(originalW, originalH) * 0.005f).coerceAtLeast(2f)
        val paint = Paint().apply {
            color = Color.argb(255, 60, 220, 255)
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            isAntiAlias = true
        }
        canvas.drawRect(
            bbox.left.toFloat(),
            bbox.top.toFloat(),
            bbox.right.toFloat(),
            bbox.bottom.toFloat(),
            paint,
        )
        return output
    }

    private fun boundingBoxOfMask(saliency: FloatArray, w: Int, h: Int, threshold: Float): Rect {
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (saliency[row + x] >= threshold) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }
        return if (maxX < 0) Rect(0, 0, w, h) else Rect(minX, minY, maxX + 1, maxY + 1)
    }

    private fun scaleRect(r: Rect, srcW: Int, srcH: Int, dstW: Int, dstH: Int): Rect {
        val sx = dstW.toFloat() / srcW
        val sy = dstH.toFloat() / srcH
        return Rect(
            (r.left * sx).toInt().coerceIn(0, dstW),
            (r.top * sy).toInt().coerceIn(0, dstH),
            (r.right * sx).toInt().coerceIn(0, dstW),
            (r.bottom * sy).toInt().coerceIn(0, dstH),
        )
    }

    private fun cropMaskedForeground(
        original: Bitmap,
        saliency: FloatArray,
        modelW: Int,
        modelH: Int,
        @Suppress("UNUSED_PARAMETER") bbox: Rect,
    ): Bitmap {
        // Same dimensions as the input photo so it composites naturally
        // into a UI canvas at the original aspect ratio. Background is
        // filled with the app's dark backdrop (DeepSpace) so the
        // background-removal effect is visually obvious — without this
        // the masked areas are transparent and the result looks too
        // similar to the original photo.
        val saliencyHiRes = Bitmap.createScaledBitmap(
            saliencyToBitmap(saliency, modelW, modelH),
            original.width,
            original.height,
            true,
        )

        val output = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(BACKGROUND_FILL)
        // Build the foreground in a temp layer so the saliency alpha
        // multiply is contained — otherwise DST_IN would also clip the
        // background fill we just drew.
        val fg = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        val fgCanvas = Canvas(fg)
        fgCanvas.drawBitmap(original, 0f, 0f, null)
        fgCanvas.drawBitmap(
            saliencyHiRes,
            0f, 0f,
            Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) },
        )
        canvas.drawBitmap(fg, 0f, 0f, null)
        fg.recycle()
        saliencyHiRes.recycle()
        return output
    }

    private companion object {
        private const val TAG = "TactileLensU2Net"
        private const val ASSET_NAME = "u2net_small.tflite"
        // Matches DeepSpace from ui/theme/Color.kt — keeps the cropped
        // bitmap visually consistent with the surrounding scanner card.
        private const val BACKGROUND_FILL: Int = 0xFF07131D.toInt()
    }
}
