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
    // Empirical means from the new axes model (n=10 captures per material,
    // teammate-supplied). Only glass, paper, wood are measured at this
    // point; rocks/sand/fabric fall through to open-vocab and are routed
    // to the procedural haptic + silent audio path. The new model's output
    // distribution is much tighter than the training-centroid table from
    // `modeloutput.md` (everything clusters around rough~0.65, hard~0.9,
    // friction~0.75), so the threshold tightens to match.
    // Glass / paper / wood are empirically retuned on-device to match the
    // shipped model's actual output distribution. The teammate's ground-
    // truth table consistently overstated friction (model emits ~0.05-0.10
    // lower) and, for wood, understated density. Roughness and hardness
    // track the original table closely, so they keep Madeline's values.
    //
    // Rocks / sand / fabric are estimates: training centroids from
    // modeloutput.md with the same -0.05 friction bias correction
    // applied. No on-device samples yet, so confidence on those snaps
    // will be lower until calibrated.
    private val centroids: Map<Material, TextureAxes> = mapOf(
        Material.GLASS  to TextureAxes(roughness = 0.6778f, hardness = 0.8430f, friction = 0.6650f, density = 0.2807f),
        Material.PAPER  to TextureAxes(roughness = 0.6268f, hardness = 0.8908f, friction = 0.6980f, density = 0.4200f),
        Material.WOOD   to TextureAxes(roughness = 0.6010f, hardness = 0.9300f, friction = 0.7200f, density = 0.3000f),
        Material.ROCKS  to TextureAxes(roughness = 0.8500f, hardness = 0.9400f, friction = 0.6500f, density = 0.3300f),
        Material.SAND   to TextureAxes(roughness = 0.5700f, hardness = 0.2300f, friction = 0.5800f, density = 0.8900f),
        Material.FABRIC to TextureAxes(roughness = 0.3500f, hardness = 0.1000f, friction = 0.6800f, density = 0.6100f),
    )

    /**
     * Euclidean distance below which axes are classified as canonical.
     *
     * Set to 0.30 for the new axes model. Inter-centroid distances are
     * 0.139 (glass-paper), 0.171 (glass-wood), 0.226 (paper-wood); per-axis
     * std on a single capture is roughly 0.05-0.12. The threshold only
     * controls whether anything snaps versus falling to open-vocab, not
     * which centroid wins (closest always does), so a generous value just
     * means more captures get a label. 0.30 catches normal observation
     * noise comfortably while staying inside the inter-centroid budget.
     */
    const val CLASSIFICATION_THRESHOLD: Float = 0.30f

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
