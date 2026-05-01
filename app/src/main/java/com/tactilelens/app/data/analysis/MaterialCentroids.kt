package com.tactilelens.app.data.analysis

import com.tactilelens.app.data.model.Material
import com.tactilelens.app.data.model.TextureAxes
import kotlin.math.sqrt

/**
 * 6-material centroid table from the ML team's training set
 * (`modeloutput.md` on `origin/haptics_model`). Axes are stored after
 * the `density -> flatBumpy` mapping (locked decision Q1-D) so they
 * line up directly with our [TextureAxes] type.
 *
 * Mapping reference: roughness <-> rough, hardness <-> hard,
 * friction <-> friction, flatBumpy <-> density.
 *
 * Used by [LiteRTAnalysisClient] in Phase 4 to classify regression
 * outputs into a canonical [Material] when the result is within
 * [CLASSIFICATION_THRESHOLD] Euclidean distance of one centroid.
 */
object MaterialCentroids {

    private val centroids: Map<Material, TextureAxes> = mapOf(
        Material.ROCKS  to TextureAxes(roughness = 0.85f, flatBumpy = 0.33f, friction = 0.70f, hardness = 0.94f),
        Material.GLASS  to TextureAxes(roughness = 0.05f, flatBumpy = 0.05f, friction = 0.08f, hardness = 0.96f),
        Material.SAND   to TextureAxes(roughness = 0.57f, flatBumpy = 0.89f, friction = 0.63f, hardness = 0.23f),
        Material.FABRIC to TextureAxes(roughness = 0.35f, flatBumpy = 0.61f, friction = 0.73f, hardness = 0.10f),
        Material.WOOD   to TextureAxes(roughness = 0.31f, flatBumpy = 0.27f, friction = 0.43f, hardness = 0.78f),
        Material.PAPER  to TextureAxes(roughness = 0.12f, flatBumpy = 0.74f, friction = 0.28f, hardness = 0.26f),
    )

    /** Euclidean distance below which axes are classified as canonical. */
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
        val df = a.flatBumpy - b.flatBumpy
        val dfr = a.friction - b.friction
        val dh = a.hardness - b.hardness
        return sqrt(dr * dr + df * df + dfr * dfr + dh * dh)
    }
}
