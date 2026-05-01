package com.tactilelens.app.data.analysis

import com.tactilelens.app.data.model.Material
import com.tactilelens.app.data.model.TextureAxes
import kotlin.math.sqrt

/**
 * 6-material centroid table from the ML team's training set
 * (`modeloutput.md` on `origin/haptics_model`). Field names mirror the
 * model's output vocabulary one-to-one: rough/hard/friction/density.
 *
 * Used by [LiteRTAnalysisClient] in Phase 4 to classify regression
 * outputs into a canonical [Material] when the result is within
 * [CLASSIFICATION_THRESHOLD] Euclidean distance of one centroid.
 */
object MaterialCentroids {

    // ------------------------------------------------------------------
    // Empirically retuned for the model's actual output range.
    //
    // The original centroids (preserved as comments below) came from the
    // ML team's training-set ground truth: a full 0.05–0.96 spread per
    // axis. The shipped LinearHead's `std`-clamp compresses inference
    // outputs to roughly 0.10–0.70 — never the extremes — so most photos
    // landed equidistant from every centroid, classifying as nothing.
    //
    // Two strategies applied here:
    //   (a) For PAPER and WOOD we have on-device measurements — multiple
    //       captures of the same physical material on the demo device.
    //       Centroids set to the mean of observed (r, h, f, d) for each.
    //   (b) For the other four materials we have no on-device data yet,
    //       so we apply a linear remap of the original training centroid
    //       into the model's measured output range:  new = 0.10 + 0.60*old.
    //       Best estimate until we collect on-device samples.
    //
    // When the team's better encoder lands, this whole table reverts to
    // the originals.
    // ------------------------------------------------------------------
    private val centroids: Map<Material, TextureAxes> = mapOf(
        // (a) Empirically measured on-device:
        Material.PAPER  to TextureAxes(roughness = 0.13f, hardness = 0.26f, friction = 0.30f, density = 0.50f),
        // crumpled paper, n=3, sd ≈ 0.05/axis
        Material.WOOD   to TextureAxes(roughness = 0.30f, hardness = 0.45f, friction = 0.35f, density = 0.45f),
        // wood cabinet/countertop, n=7, sd ≈ 0.07/axis

        // (b) Linear-remapped from the team's training centroids until
        // we have on-device samples:
        Material.ROCKS  to TextureAxes(roughness = 0.61f, hardness = 0.66f, friction = 0.52f, density = 0.30f),
        // orig (0.85, 0.94, 0.70, 0.33)
        Material.GLASS  to TextureAxes(roughness = 0.13f, hardness = 0.68f, friction = 0.15f, density = 0.13f),
        // orig (0.05, 0.96, 0.08, 0.05)
        Material.SAND   to TextureAxes(roughness = 0.44f, hardness = 0.24f, friction = 0.48f, density = 0.63f),
        // orig (0.57, 0.23, 0.63, 0.89)
        Material.FABRIC to TextureAxes(roughness = 0.31f, hardness = 0.16f, friction = 0.54f, density = 0.47f),
        // orig (0.35, 0.10, 0.73, 0.61)
    )

    /**
     * Euclidean distance below which axes are classified as canonical.
     *
     * Loosened from 0.30 → 0.50 after on-device testing showed real-world
     * predictions land in a compressed range (~0.1–0.7) post-LinearHead
     * `std`-clamp, while the centroid table still uses the full original
     * 0.05–0.96 spread from the team's training set. With 0.30 most photos
     * landed *just past* the threshold and fell to OPEN VOCABULARY — e.g.
     * a literal wood cabinet at axes=(0.36, 0.50, 0.41, 0.44) was 0.332
     * from the Wood centroid (0.31, 0.78, 0.43, 0.27), missing classification
     * by 0.032. 0.50 lets that case (and the analogous Glass/Paper near-miss)
     * snap to the right material. Until the new model lands and predictions
     * reach centroid extremes, this is the right knob.
     */
    const val CLASSIFICATION_THRESHOLD: Float = 0.50f

    /**
     * Returns the nearest canonical material if min distance is within
     * [CLASSIFICATION_THRESHOLD], otherwise null (open-vocabulary path).
     * Second element of the pair is always the actual minimum distance,
     * used by the caller to derive a confidence score.
     */
    fun classify(axes: TextureAxes): Pair<Material?, Float> {
        val nearest = centroids.minBy { (_, c) -> distance(axes, c) }
        val dist = distance(axes, nearest.value)
        return if (dist <= CLASSIFICATION_THRESHOLD) nearest.key to dist else null to dist
    }

    private fun distance(a: TextureAxes, b: TextureAxes): Float {
        val dr = a.roughness - b.roughness
        val df = a.density - b.density
        val dfr = a.friction - b.friction
        val dh = a.hardness - b.hardness
        return sqrt(dr * dr + df * df + dfr * dfr + dh * dh)
    }
}
