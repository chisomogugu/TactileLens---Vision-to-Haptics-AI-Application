package com.tactilelens.app.data.analysis

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * Kotlin port of `linear_head.onnx` — the 30 KB head that turns the
 * EfficientNet encoder's 1280-dim feature vector into the four perceptual
 * texture axes. The ONNX graph is exactly:
 *
 *   x = (features - mean) / std   // mean,std are 1280-vectors
 *   y = W @ x + b                 // W is [4,1280], b is [4]
 *   out = sigmoid(y)              // 4-vector in [0, 1]
 *
 * Output order is `[rough, hard, friction, density]` (verified against
 * `colab_convert_encoder.ipynb`) — the same field names live on
 * [TextureAxes].
 *
 * Weights live in `assets/linear_head_weights.bin` as packed little-endian
 * float32: `mean(1280) | std(1280) | W(4 x 1280, row-major) | b(4)` =
 * 7684 floats = 30 736 bytes. Loaded once at construction.
 *
 * Diverges intentionally from the raw ONNX in one place: `std` is clamped
 * to [STD_FLOOR] (0.01) before division. See the init block for why.
 */
class LinearHead(context: Context) {

    private val mean: FloatArray
    private val std: FloatArray
    private val weight: FloatArray   // 4 * 1280 row-major
    private val bias: FloatArray     // 4

    init {
        val bytes = context.assets.open(ASSET_NAME).use { it.readBytes() }
        require(bytes.size == EXPECTED_BYTES) {
            "linear_head_weights.bin: expected $EXPECTED_BYTES bytes, got ${bytes.size}"
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        mean = FloatArray(FEATURE_DIM).also(buf::get)
        // Std comes straight from the ONNX initializer, but ~2% of entries
        // are degenerate (< 1e-4) — features that were nearly constant in
        // the team's training set. Dividing real-world inference features
        // by such tiny values amplifies any deviation by 1e6+, which then
        // dominates the linear projection and saturates every output to
        // the (0, 0, 0, 1) corner. The ML pipeline reads as "everything is
        // glass" without this clamp. Floor of 0.01 was tuned against four
        // captured frames in this session: it leaves the well-conditioned
        // 98% of features untouched but stops the degenerate ones from
        // blowing up. Long-term fix is on the ML team (clamp std at
        // training time, or use BatchNorm), but this keeps the demo
        // functional today.
        val rawStd = FloatArray(FEATURE_DIM).also(buf::get)
        std = FloatArray(FEATURE_DIM) { i -> maxOf(rawStd[i], STD_FLOOR) }
        weight = FloatArray(OUTPUT_DIM * FEATURE_DIM).also(buf::get)
        bias = FloatArray(OUTPUT_DIM).also(buf::get)
    }

    /**
     * Project 1280 encoder features to 4 axes in [0, 1]. Returns a fresh
     * array `[rough, hard, friction, density]`.
     */
    fun project(features: FloatArray): FloatArray {
        require(features.size == FEATURE_DIM) {
            "features must be length $FEATURE_DIM, got ${features.size}"
        }
        val out = FloatArray(OUTPUT_DIM)
        for (o in 0 until OUTPUT_DIM) {
            val rowBase = o * FEATURE_DIM
            var acc = bias[o]
            for (i in 0 until FEATURE_DIM) {
                val normalized = (features[i] - mean[i]) / std[i]
                acc += weight[rowBase + i] * normalized
            }
            out[o] = sigmoid(acc)
        }
        return out
    }

    private fun sigmoid(x: Float): Float = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()

    private companion object {
        private const val ASSET_NAME = "linear_head_weights.bin"
        private const val FEATURE_DIM = 1280
        private const val OUTPUT_DIM = 4
        private const val EXPECTED_BYTES =
            (FEATURE_DIM + FEATURE_DIM + OUTPUT_DIM * FEATURE_DIM + OUTPUT_DIM) * 4
        private const val STD_FLOOR = 0.01f
    }
}
