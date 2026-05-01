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
        /** Binary foreground mask at model resolution (0xFF / 0x00). */
        val mask: Bitmap,
        /**
         * Foreground extracted from the original photo, cropped to the
         * mask's bounding box. Sized to the bbox's dimensions in the
         * original image's coordinate space.
         */
        val cropped: Bitmap,
        /** The bounding box of the foreground in original-image coordinates. */
        val bbox: Rect,
        /** Wall-clock millis spent in `Interpreter.run()` for this segmentation. */
        val inferenceMs: Long,
    )

    fun segment(input: Bitmap): SegmentationResult {
        // Resize photo to model input. Output shape from session is
        // either NHWC [1,H,W,1] or NHWC [1,H,W,3] depending on conversion.
        val inH = session.inputShape[1]
        val inW = session.inputShape[2]
        val resized = Bitmap.createScaledBitmap(input, inW, inH, true)

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
        val maskBmp = saliencyToMaskBitmap(saliencyArray, outW, outH, threshold = 0.5f)
        val bboxAtModel = boundingBoxOfMask(saliencyArray, outW, outH, threshold = 0.5f)
        val bboxAtOriginal = scaleRect(bboxAtModel, outW, outH, input.width, input.height)
        val croppedBmp = cropMaskedForeground(input, saliencyArray, outW, outH, bboxAtOriginal)

        Log.i(
            TAG,
            "segment: ${input.width}x${input.height} -> bbox=$bboxAtOriginal inference=${ms}ms ($backendLabel)",
        )

        return SegmentationResult(
            saliency = saliencyBmp,
            mask = maskBmp,
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

    private fun saliencyToMaskBitmap(saliency: FloatArray, w: Int, h: Int, threshold: Float): Bitmap {
        val pixels = IntArray(w * h)
        for (i in saliency.indices) {
            pixels[i] = if (saliency[i] >= threshold) Color.WHITE else Color.BLACK
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
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
        bbox: Rect,
    ): Bitmap {
        val cropW = (bbox.width()).coerceAtLeast(1)
        val cropH = (bbox.height()).coerceAtLeast(1)

        // 1) Upscale the saliency map to the original image's resolution
        //    (cheap nearest-neighbor via Bitmap.createScaledBitmap).
        val saliencyHiRes = Bitmap.createScaledBitmap(
            saliencyToBitmap(saliency, modelW, modelH),
            original.width,
            original.height,
            true,
        )

        // 2) Crop both the original photo and the upscaled saliency to bbox.
        val croppedPhoto = Bitmap.createBitmap(original, bbox.left, bbox.top, cropW, cropH)
        val croppedSaliency = Bitmap.createBitmap(saliencyHiRes, bbox.left, bbox.top, cropW, cropH)

        // 3) Multiply: dst.alpha = src.alpha * sal, dst.rgb = original.rgb.
        val output = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawBitmap(croppedPhoto, 0f, 0f, null)
        val paint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawBitmap(croppedSaliency, 0f, 0f, paint)

        // Free the intermediate big bitmaps.
        saliencyHiRes.recycle()
        croppedPhoto.recycle()
        croppedSaliency.recycle()

        return output
    }

    private companion object {
        private const val TAG = "TactileLensU2Net"
        private const val ASSET_NAME = "u2net_small.tflite"
    }
}
