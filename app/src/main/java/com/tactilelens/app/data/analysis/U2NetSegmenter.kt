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
class U2NetSegmenter(context: Context) : Segmenter {

    private val session = LiteRTSession(context, ASSET_NAME)

    /** "NPU" or "CPU" — propagated from the underlying [LiteRTSession]. */
    override val backendLabel: String get() = session.backendLabel

    /**
     * Run U2Net on [input], optionally steered by a user tap.
     *
     * If [tapX] / [tapY] are valid (>= 0, in original-image coordinates),
     * the salient region returned is the connected component of the
     * binarized saliency map that contains the tap point — i.e. only the
     * thing the user pointed at, not "everything U2Net thinks is salient."
     * If the tap landed outside any foreground or the resulting region is
     * degenerately small, falls back to a fixed box around the tap so the
     * encoder always gets a sane crop.
     *
     * If the tap is invalid (default), the global salient region is used
     * (legacy single-shutter behavior).
     */
    override fun segment(input: Bitmap, tapX: Int, tapY: Int): SegmentationResult {
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
        val rawSaliency = FloatArray(outH * outW)
        for (y in 0 until outH) for (x in 0 until outW) {
            var v = outputTensor[0][y][x][0]
            for (c in 1 until outChannels) {
                val cv = outputTensor[0][y][x][c]
                if (cv > v) v = cv
            }
            rawSaliency[y * outW + x] = v.coerceIn(0f, 1f)
        }

        // Steer by tap if provided. The returned mask has only the
        // connected region containing the tap point lit up; everything
        // else is zero. Falls back to the original raw saliency when no
        // tap was supplied.
        val tapValid = tapX >= 0 && tapY >= 0
        val steeredSaliency = if (tapValid) {
            steerByTap(rawSaliency, outW, outH, tapX, tapY, softInput.width, softInput.height)
        } else {
            rawSaliency
        }

        // Bbox sanity gates. Three failure modes for U2Net on real photos:
        //  (a) Returns a tiny degenerate bbox (< 5% of frame). Usually
        //      means it misfired (e.g. 67x25 px noise crop).
        //  (b) Returns nearly the entire frame (> 80%). This is the
        //      "uniform texture" failure — when the scene is one big
        //      surface (a wood cabinet, a fabric swatch close-up) U2Net
        //      can't find a salient OBJECT and grabs essentially-arbitrary
        //      regions. Across multiple captures of the same scene the
        //      bbox flips around and the encoder sees different patches,
        //      producing wildly different axes for what's visually the
        //      same thing. (User-visible symptom: 4 captures of a wood
        //      cabinet classifying as Wood/Sand/Wood/Paper.)
        //  (c) Healthy bbox in between — use it as-is.
        // For (a) and (b), fall back to a fixed center crop (or tap-box
        // when supplied) so the encoder gets a stable, deterministic
        // patch to encode.
        val modelArea = outW * outH
        var bboxAtModel = boundingBoxOfMask(steeredSaliency, outW, outH, threshold = 0.5f)
        var effectiveSaliency = steeredSaliency
        val bboxArea = bboxAtModel.width() * bboxAtModel.height()
        val tooSmall = bboxArea < modelArea * MIN_BBOX_AREA_FRACTION
        // The "too large" check is suppressed when the user explicitly
        // tapped — they aimed at this region, honor it even if it's the
        // whole frame.
        val tooLarge = !tapValid && bboxArea > modelArea * MAX_BBOX_AREA_FRACTION
        if (tooSmall || tooLarge) {
            val tapXModel = if (tapValid) {
                (tapX.toFloat() * outW / softInput.width).toInt().coerceIn(0, outW - 1)
            } else outW / 2
            val tapYModel = if (tapValid) {
                (tapY.toFloat() * outH / softInput.height).toInt().coerceIn(0, outH - 1)
            } else outH / 2
            effectiveSaliency = fixedBoxMask(outW, outH, tapXModel, tapYModel)
            bboxAtModel = boundingBoxOfMask(effectiveSaliency, outW, outH, threshold = 0.5f)
            val reason = if (tooSmall) "degenerate" else "uniform-texture (no salient object)"
            Log.w(TAG, "$reason bbox area=$bboxArea/$modelArea — fell back to fixed-box around ($tapXModel, $tapYModel)")
        }

        val saliencyBmp = saliencyToBitmap(effectiveSaliency, outW, outH)
        val bboxAtOriginal = scaleRect(bboxAtModel, outW, outH, softInput.width, softInput.height)
        val maskWithBboxBmp = maskWithBboxAtOriginalSize(
            saliency = effectiveSaliency,
            modelW = outW,
            modelH = outH,
            originalW = softInput.width,
            originalH = softInput.height,
            bbox = bboxAtOriginal,
        )
        val croppedBmp = cropMaskedForeground(softInput, effectiveSaliency, outW, outH, bboxAtOriginal)

        Log.i(
            TAG,
            "segment: ${softInput.width}x${softInput.height} tap=(${if (tapValid) "$tapX,$tapY" else "none"}) " +
                "-> bbox=$bboxAtOriginal inference=${ms}ms ($backendLabel)",
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

    /**
     * Replace [rawSaliency] with a mask containing only the connected
     * salient region that the user tapped. If the tap landed on a pixel
     * below threshold, search a small neighborhood for the nearest
     * foreground pixel so off-by-a-bit taps still work. If nothing nearby,
     * return a fixed box around the tap point so the encoder gets a
     * coherent texture swatch instead of a black mask.
     */
    private fun steerByTap(
        rawSaliency: FloatArray,
        modelW: Int,
        modelH: Int,
        tapX: Int,
        tapY: Int,
        origW: Int,
        origH: Int,
    ): FloatArray {
        val tapXModel = (tapX.toFloat() * modelW / origW).toInt().coerceIn(0, modelW - 1)
        val tapYModel = (tapY.toFloat() * modelH / origH).toInt().coerceIn(0, modelH - 1)
        val foreground = BooleanArray(modelW * modelH) { rawSaliency[it] >= MASK_THRESHOLD }

        // Snap to the nearest foreground pixel within a small radius so a
        // tap close to (but not on) an edge still selects the object.
        var seedX = tapXModel
        var seedY = tapYModel
        if (!foreground[seedY * modelW + seedX]) {
            val radius = (minOf(modelW, modelH) * 0.05f).toInt().coerceAtLeast(3)
            var found = false
            outer@ for (r in 1..radius) {
                for (dy in -r..r) for (dx in -r..r) {
                    val nx = tapXModel + dx
                    val ny = tapYModel + dy
                    if (nx in 0 until modelW && ny in 0 until modelH &&
                        foreground[ny * modelW + nx]) {
                        seedX = nx; seedY = ny; found = true; break@outer
                    }
                }
            }
            if (!found) return fixedBoxMask(modelW, modelH, tapXModel, tapYModel)
        }

        return floodFillMask(foreground, modelW, modelH, seedX, seedY)
    }

    /**
     * 4-neighbor BFS flood-fill from [startX], [startY]. Returns a float
     * mask matching [foreground]'s shape with 1f inside the connected
     * region and 0f outside.
     */
    private fun floodFillMask(
        foreground: BooleanArray,
        w: Int,
        h: Int,
        startX: Int,
        startY: Int,
    ): FloatArray {
        val out = FloatArray(w * h)
        val visited = BooleanArray(w * h)
        val queue = ArrayDeque<Int>()
        val seedIdx = startY * w + startX
        queue.addLast(seedIdx)
        visited[seedIdx] = true
        while (queue.isNotEmpty()) {
            val idx = queue.removeFirst()
            out[idx] = 1f
            val x = idx % w
            val y = idx / w
            val neighbors = intArrayOf(idx + 1, idx - 1, idx + w, idx - w)
            val valid = booleanArrayOf(x + 1 < w, x - 1 >= 0, y + 1 < h, y - 1 >= 0)
            for (i in neighbors.indices) {
                val n = neighbors[i]
                if (!valid[i] || visited[n] || !foreground[n]) continue
                visited[n] = true
                queue.addLast(n)
            }
        }
        return out
    }

    /**
     * Square mask covering [BOX_FRACTION] of the smaller frame dimension,
     * centered at ([cx], [cy]). Used as the texture-swatch fallback when
     * U2Net produces no foreground at the tap point or returns a
     * degenerately small bbox.
     */
    private fun fixedBoxMask(w: Int, h: Int, cx: Int, cy: Int): FloatArray {
        val sz = (minOf(w, h) * BOX_FRACTION).toInt().coerceAtLeast(8)
        val left = (cx - sz / 2).coerceAtLeast(0)
        val right = (cx + sz / 2).coerceAtMost(w)
        val top = (cy - sz / 2).coerceAtLeast(0)
        val bottom = (cy + sz / 2).coerceAtMost(h)
        val out = FloatArray(w * h)
        for (y in top until bottom) {
            val row = y * w
            for (x in left until right) out[row + x] = 1f
        }
        return out
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
        /** Threshold used to binarize raw saliency for connected-component selection. */
        private const val MASK_THRESHOLD = 0.5f
        /** Side length (as a fraction of min(modelW, modelH)) of the fallback texture box. */
        private const val BOX_FRACTION = 0.30f
        /** Below this fraction of the model frame, treat U2Net's bbox as degenerate. */
        private const val MIN_BBOX_AREA_FRACTION = 0.05f
        /** Above this fraction (no tap), treat U2Net's bbox as "no salient object found". */
        private const val MAX_BBOX_AREA_FRACTION = 0.80f
    }
}
