package com.tactilelens.app.data.model

import android.os.VibrationEffect

/**
 * Wire format for ML-emitted primitive sequences (future).
 *
 * The renderer's [com.tactilelens.app.data.haptic.HapticRenderer.play] method
 * consumes this directly. v1 of TactileLens does NOT wire this in: ML emits
 * axes today, the renderer derives recipes from axes. When ML migrates to
 * primitive sequences (the prior session called this "wire format B"), the
 * migration is a single call swap on the renderer.
 *
 * See ARCHITECTURE.md §10 (locked decision 2: ML contract) and §12 (open
 * questions for the ML team).
 */
typealias HapticRecipe = List<HapticPrimitive>

/**
 * One slot in a recipe.
 *
 * @property type     which Composition primitive to fire.
 * @property scale    amplitude in [0.0, 1.0]; 0.0 is minimum perceivable, NOT silent.
 * @property delayMs  gap before this primitive fires, in milliseconds, >= 0;
 *                    0 = simultaneous with previous (chord/accent), 50-100 = discernible
 *                    gap, >100 = feels like a separate event.
 */
data class HapticPrimitive(
    val type: PrimitiveType,
    val scale: Float,
    val delayMs: Int,
)

/**
 * The 8 Composition primitives the Android API offers, normalized to a typesafe
 * enum so the wire format can be JSON / Proto without leaking platform constants.
 */
enum class PrimitiveType {
    LOW_TICK,
    TICK,
    CLICK,
    THUD,
    SLOW_RISE,
    QUICK_RISE,
    QUICK_FALL,
    SPIN;

    fun toAndroidConst(): Int = when (this) {
        LOW_TICK -> VibrationEffect.Composition.PRIMITIVE_LOW_TICK
        TICK -> VibrationEffect.Composition.PRIMITIVE_TICK
        CLICK -> VibrationEffect.Composition.PRIMITIVE_CLICK
        THUD -> VibrationEffect.Composition.PRIMITIVE_THUD
        SLOW_RISE -> VibrationEffect.Composition.PRIMITIVE_SLOW_RISE
        QUICK_RISE -> VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
        QUICK_FALL -> VibrationEffect.Composition.PRIMITIVE_QUICK_FALL
        SPIN -> VibrationEffect.Composition.PRIMITIVE_SPIN
    }
}
