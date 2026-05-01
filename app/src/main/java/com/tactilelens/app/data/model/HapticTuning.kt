package com.tactilelens.app.data.model

/**
 * Runtime-mutable haptic recipe parameters, keyed by material.
 *
 * Values match the venue-tuned defaults from the scaffold lab. Treat them as
 * the starting line for further tuning; not the final spec. The renderer
 * reads these via a `@Volatile` field so values can be hot-swapped without
 * rebuilding.
 */
data class HapticTuning(
    val wood: Wood = Wood(),
    val paper: Paper = Paper(),
    val rocks: Rocks = Rocks(),
    val sand: Sand = Sand(),
    val fabric: Fabric = Fabric(),
    val glass: Glass = Glass(),
    val procedural: Procedural = Procedural(),
) {
    /**
     * Wood: rapid TICK grain run (matches scratching-wood foley).
     *
     * Mirrors stick-slip dynamics of a finger crossing wood grain (~100 Hz
     * stick-slip = TICK band per AOSP haptics-constants-primitives). Replaces
     * the prior THUD-knock-and-TICK-tail signature, which read as wood being
     * STRUCK rather than wood being SCRATCHED. Cross-modal coherence per
     * Google Haptics Principles: haptic gesture must match audio gesture.
     */
    data class Wood(
        val count: Int = 6,
        val scale: Float = 0.85f,
        val intervalMs: Int = 35,
    )

    /**
     * Paper: rapid TICK rustle (mirrors crumpling-paper foley).
     * Distinct from sand (LOW_TICK whispery) and fabric (LOW_TICK + tail).
     */
    data class Paper(
        val count: Int = 5,
        val scale: Float = 0.85f,
        val intervalMs: Int = 40,
    )

    /**
     * Rocks tap-down: sharp CLICK impact followed by a weighted THUD settle.
     * The "set hand on stone" contact: hard percussive landing, then weight
     * transfer. One-shot, so the THUD tail is fully felt (no cancellation).
     */
    data class Rocks(
        val clickScale: Float = 1.0f,
        val thudScale: Float = 0.85f,
        val thudGapMs: Int = 40,
    )

    /**
     * Sand: very-high-density very-low-amplitude LOW_TICKs (whispery hiss).
     * Tuned down to match the new highpassed/quieted sand foley — sand
     * should feel almost subliminal under the finger, distinct from rocks
     * which has weighty impact transients.
     */
    data class Sand(
        val count: Int = 18,
        val scale: Float = 0.50f,
        val intervalMs: Int = 22,
    )

    /**
     * Fabric tap-down: a single soft LOW_TICK contact followed by a SLOW_RISE
     * settle — the "settling into cloth" feel. Replaces the prior 6x LOW_TICK
     * train, which stuttered as a bubbly run instead of the soft single
     * contact a textile surface should give.
     */
    data class Fabric(
        val tickScale: Float = 0.75f,
        val riseScale: Float = 0.55f,
        val riseGapMs: Int = 40,
    )

    /**
     * Glass: bright CLICK followed by two decaying TICKs — a crystalline
     * "tap-ting-ting" snap. CLICK is the sharpest Composition primitive on
     * the demo device; TICKs decay so the chord reads as ringing rather than
     * a flat impact. Slightly heavier than the original Phase 6 plan (which
     * was just CLICK + TICK) because a single CLICK on glass-axes was barely
     * perceptible during testing.
     */
    data class Glass(
        val clickScale: Float = 1.0f,
        val tick1Scale: Float = 0.70f,
        val tick2Scale: Float = 0.45f,
        val gap1Ms: Int = 35,
        val gap2Ms: Int = 40,
    )

    /**
     * Procedural recipe knobs (axis-driven, fires when material is null).
     * `tickMultiplier` shapes density via roughness.
     * `jitter` is the amplitude randomization window per Wobble pattern.
     */
    data class Procedural(
        val tickMultiplier: Float = 6f,
        val baseScale: Float = 0.92f,
        val jitter: Float = 0.1f,
        val intervalMs: Int = 60,
        val frictionTailScale: Float = 0.80f,
        val frictionTailGapMs: Int = 80,
    )
}
