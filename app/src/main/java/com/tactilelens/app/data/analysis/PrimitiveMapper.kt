package com.tactilelens.app.data.analysis

import com.tactilelens.app.data.model.TextureAxes

/**
 * Port of the ML team's `map_primitives.py` rule. Converts 4 axes
 * into 8 normalized weights for Android Composition primitives.
 *
 * Output order matches `VibrationEffect.Composition.PRIMITIVE_*`:
 *   [0] TICK         rough * hard * dense
 *   [1] LOW_TICK     rough * (1 - hard) * dense
 *   [2] CLICK        hard * dense * (1 - rough)
 *   [3] THUD         (1 - hard) * (1 - dense)
 *   [4] SLOW_RISE    (1 - rough) * (1 - hard) * friction
 *   [5] QUICK_RISE   hard * (1 - rough)
 *   [6] QUICK_FALL   (1 - friction) * (1 - rough)
 *   [7] SPIN         fixed at 0.05 (cannot be inferred from image)
 *
 * Used by `LiteRTAnalysisClient` in Phase 4 to precompute the gesture-
 * start signature for null-material (open-vocab) photos. Canonical
 * materials retain their hand-tuned recipes per locked decision Q7-B.
 */
object PrimitiveMapper {

    fun map(axes: TextureAxes): FloatArray {
        val rough = axes.roughness
        val hard = axes.hardness
        val friction = axes.friction
        val density = axes.density

        val tick      = rough * hard * density
        val lowTick   = rough * (1f - hard) * density
        val click     = hard * density * (1f - rough)
        val thud      = (1f - hard) * (1f - density)
        val slowRise  = (1f - rough) * (1f - hard) * friction
        val quickRise = hard * (1f - rough)
        val quickFall = (1f - friction) * (1f - rough)
        val spin      = 0.05f

        val raw = floatArrayOf(tick, lowTick, click, thud, slowRise, quickRise, quickFall, spin)
        val sum = raw.sum()
        return if (sum > 0f) FloatArray(raw.size) { raw[it] / sum } else raw
    }
}
