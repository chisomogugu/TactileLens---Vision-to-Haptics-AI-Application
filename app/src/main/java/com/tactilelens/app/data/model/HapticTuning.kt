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

    /** Rocks: medium-density LOW_TICK train (granular crunch). */
    data class Rocks(
        val count: Int = 8,
        val scale: Float = 0.98f,
        val intervalMs: Int = 60,
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
     * Fabric: mid-density LOW_TICKs followed by a SLOW_RISE drag-out
     * (soft weave + draggy textile character). Sits between sand (denser,
     * quieter, gritty) and rocks (sparser, louder, granular).
     */
    data class Fabric(
        val count: Int = 6,
        val scale: Float = 0.75f,
        val intervalMs: Int = 50,
        val dragTailScale: Float = 0.65f,
        val dragTailGapMs: Int = 60,
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
