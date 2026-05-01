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
    val glass: Glass = Glass(),
    val rocks: Rocks = Rocks(),
    val sand: Sand = Sand(),
    val procedural: Procedural = Procedural(),
) {
    /** Wood: warm THUD then quieter TICK tail (knock + ring-out). */
    data class Wood(
        val knockScale: Float = 0.55f,
        val gapMs: Int = 80,
        val tailScale: Float = 0.30f,
    )

    /** Glass: sharp CLICK then short TICK (squeak/snap). */
    data class Glass(
        val clickScale: Float = 0.85f,
        val gapMs: Int = 60,
        val tickScale: Float = 0.40f,
    )

    /** Rocks: medium-density LOW_TICK train (granular crunch). */
    data class Rocks(
        val count: Int = 8,
        val scale: Float = 0.65f,
        val intervalMs: Int = 60,
    )

    /** Sand: high-density low-amplitude LOW_TICKs (whispery hiss). */
    data class Sand(
        val count: Int = 16,
        val scale: Float = 0.25f,
        val intervalMs: Int = 28,
    )

    /**
     * Procedural recipe knobs (axis-driven, fires when material is null).
     * `tickMultiplier` shapes density via roughness.
     * `jitter` is the amplitude randomization window per Wobble pattern.
     */
    data class Procedural(
        val tickMultiplier: Float = 6f,
        val baseScale: Float = 0.5f,
        val jitter: Float = 0.1f,
        val intervalMs: Int = 60,
        val frictionTailScale: Float = 0.4f,
        val frictionTailGapMs: Int = 80,
    )
}
