package com.tactilelens.app.data.haptic

import android.content.Context
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition.PRIMITIVE_CLICK
import android.os.VibrationEffect.Composition.PRIMITIVE_LOW_TICK
import android.os.VibrationEffect.Composition.PRIMITIVE_QUICK_FALL
import android.os.VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
import android.os.VibrationEffect.Composition.PRIMITIVE_SLOW_RISE
import android.os.VibrationEffect.Composition.PRIMITIVE_SPIN
import android.os.VibrationEffect.Composition.PRIMITIVE_THUD
import android.os.VibrationEffect.Composition.PRIMITIVE_TICK
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.tactilelens.app.data.model.HapticPrimitive
import com.tactilelens.app.data.model.HapticRecipe
import com.tactilelens.app.data.model.HapticTuning
import com.tactilelens.app.data.model.Material
import com.tactilelens.app.data.model.TextureAxes
import kotlin.random.Random

/**
 * Composition-primitive haptic renderer for TactileLens.
 *
 * Replaces the scaffold's audio-coupled-named original (`HapticGenerator`) with
 * `VibrationEffect.Composition` primitives, the only AOSP wide-band path Samsung
 * leaves accessible on the demo device. See ARCHITECTURE.md §6 and §11 for
 * the constraints.
 *
 * Per-material signature recipes (touch-down chord) and continuous-drag
 * primitives are hand-tuned and live in [HapticTuning]. The procedural path
 * (when material is null) uses Wobble-style amplitude jitter and a Pulsar-style
 * 1D frequency-axis primitive selector.
 *
 * The drag stream is event-driven: [onSwipeMove] fires once per dot crossing
 * detected by the grid composable, NOT on an internal timer.
 */
class CompositionHapticRenderer(context: Context) : HapticRenderer {

    private val vibrator: Vibrator? = run {
        val service = context.getSystemService(VibratorManager::class.java)
        service?.defaultVibrator
    }

    private var enabled: Boolean = true

    @Volatile
    var tuning: HapticTuning = HapticTuning()

    private val rng: Random = Random.Default

    init {
        Log.i(
            TAG,
            "Composition haptic renderer ready, vibrator=$vibrator, " +
                "hasAmplitudeControl=${vibrator?.hasAmplitudeControl()}",
        )
    }

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    override fun onGestureStart(
        material: Material?,
        axes: TextureAxes,
        velocity: Float,
        primitiveWeights: FloatArray?,
    ) {
        if (!enabled || vibrator == null) return
        val velocityScale = (0.5f + velocity.coerceIn(0f, 1f) * 0.5f)
        val effect = when (material) {
            Material.WOOD -> woodRecipe(velocityScale)
            Material.PAPER -> paperRecipe(velocityScale)
            Material.ROCKS -> rocksRecipe(velocityScale)
            Material.SAND -> sandRecipe(velocityScale)
            Material.FABRIC -> fabricRecipe(velocityScale)
            Material.GLASS -> glassRecipe(velocityScale)
            // Open-vocabulary path. Locked decision Q7-B: when the ML pipeline
            // produced an 8-vector signature use it as the gesture stamp;
            // otherwise fall back to the axis-driven procedural recipe so
            // canonical-weight-only callers (mock, dropdown override to null)
            // still feel something.
            null -> primitiveWeights?.let { mlPrimitiveRecipe(it, velocityScale) }
                ?: proceduralRecipe(axes, velocityScale)
        }
        vibrator.vibrate(effect)
    }

    override fun onSwipeMove(material: Material?, axes: TextureAxes, velocity: Float) {
        if (!enabled || vibrator == null) return
        val velFactor = (0.85f + velocity.coerceIn(0f, 1f) * 0.15f)
        val effect = swipeEffect(material, axes, velFactor)
        vibrator.vibrate(effect)
    }

    override fun onSwipeEnd() {
        // Lift state lives in the UI (grid composable). Renderer fires
        // per-call; nothing to clean up between gestures.
    }

    override fun play(recipe: HapticRecipe) {
        if (!enabled || vibrator == null || recipe.isEmpty()) return
        val comp = VibrationEffect.startComposition()
        recipe.forEach { p: HapticPrimitive ->
            comp.addPrimitive(
                p.type.toAndroidConst(),
                p.scale.coerceIn(0.0f, 1.0f),
                p.delayMs.coerceAtLeast(0),
            )
        }
        vibrator.vibrate(comp.compose())
    }

    override fun stop() {
        vibrator?.cancel()
    }

    // ----------------------------------------------------------------
    // PER-CROSSING SWIPE EFFECTS
    // ----------------------------------------------------------------
    // Smooth materials (wood, paper) fire a SHORT discrete primitive per
    // dot crossing — finger crosses, brief tactile event, no resistance.
    //
    // High-friction materials (fabric, rocks, sand) fire a SUSTAINED
    // waveform burst (60–80 ms) per crossing whose duration EXCEEDS the
    // 40 ms grid throttle. During active drag, consecutive bursts overlap
    // and the next vibrate() call cancels the previous in-flight burst.
    // The net felt effect is continuous "catching tension" — that is the
    // resistance the user is asking for.
    //
    // Motion-gating is preserved: stationary finger fires no crossings,
    // so no haptic events. Lift cancels any in-flight burst (stop()).
    //
    // Cross-modal coherence: bursts mirror the audio's stick-slip
    // character. Fabric's rising-amp ramp = "tension building." Rocks'
    // big-then-bigger spike = "climbing over a stone." Sand's rapid low
    // oscillation = "many tiny grains dragging."

    private fun swipeEffect(material: Material?, axes: TextureAxes, velFactor: Float): VibrationEffect {
        return when (material) {
            // Smooth surfaces: discrete primitive, no resistance.
            Material.WOOD -> singlePrimitive(PRIMITIVE_QUICK_RISE, 0.85f, velFactor)
            Material.PAPER -> singlePrimitive(PRIMITIVE_TICK, 0.85f, velFactor)
            // High-friction surfaces: sustained burst per crossing, resistance feel.
            Material.FABRIC -> fabricResistanceBurst(velFactor)
            Material.SAND -> sandResistanceBurst(velFactor)
            Material.ROCKS -> rocksResistanceBurst(velFactor)
            // Glass swipe: per-crossing CLICK with a touch of QUICK_FALL after.
            // CLICK alone on glass-axes was barely perceptible during testing
            // — the second primitive gives the sustained "slick-and-bright"
            // signature that matches the wet-finger-on-windowpane foley.
            Material.GLASS -> glassSwipeBurst(velFactor)
            null -> proceduralSwipeEffect(axes, velFactor)
        }
    }

    /** Smooth-surface effect: single Composition primitive, brief and discrete. */
    private fun singlePrimitive(primitive: Int, ampBase: Float, velFactor: Float): VibrationEffect {
        val baseAmp = (ampBase * velFactor).coerceIn(0.08f, 1.0f)
        return VibrationEffect.startComposition()
            .addPrimitive(primitive, baseAmp, 0)
            .compose()
    }

    /**
     * Fabric per-crossing: single low-amp QUICK_RISE. The fabric foley is a
     * smooth dark drone (crest factor 2.7, ZCR 0.01 — almost no transients),
     * so discrete tap primitives mismatch the audio character. QUICK_RISE has
     * a ~100 ms ramp envelope; at the 40 ms crossing throttle successive
     * crossings overlap mid-ramp, blending into a continuous soft buzz that
     * matches the sonic drone — sustained, not bubbly.
     */
    private fun fabricResistanceBurst(velFactor: Float): VibrationEffect {
        val rise = (0.55f * velFactor).coerceIn(0.15f, 0.9f)
        return VibrationEffect.startComposition()
            .addPrimitive(PRIMITIVE_QUICK_RISE, rise, 0)
            .compose()
    }

    /**
     * Sand resistance: 48 ms rapid-grit burst per crossing. Many tiny
     * grains under the fingertip — high-frequency low-amplitude oscillation.
     */
    private fun sandResistanceBurst(velFactor: Float): VibrationEffect {
        val timings = longArrayOf(8, 8, 8, 8, 8, 8)
        val high = (130 * velFactor).toInt().coerceIn(30, 255)
        val low = (60 * velFactor).toInt().coerceIn(20, 255)
        val amps = intArrayOf(high, low, high, low, high, low)
        return VibrationEffect.createWaveform(timings, amps, -1)
    }

    /**
     * Rocks per-crossing: CLICK + LOW_TICK chord. CLICK is the hardest
     * percussive primitive (~20 ms felt), LOW_TICK adds a brief weight-settle
     * after. Total felt duration ~35 ms — fits inside the 40 ms crossing
     * throttle so each event completes cleanly instead of being cancelled
     * mid-decay. Replaces a single THUD which was both too pillowy for hard
     * stone and produced a muffled stutter when cancelled 25 times per second.
     */
    private fun rocksResistanceBurst(velFactor: Float): VibrationEffect {
        val click = (0.95f * velFactor).coerceIn(0.30f, 1f)
        val tick = (0.55f * velFactor).coerceIn(0.20f, 1f)
        return VibrationEffect.startComposition()
            .addPrimitive(PRIMITIVE_CLICK, click, 0)
            .addPrimitive(PRIMITIVE_LOW_TICK, tick, 12)
            .compose()
    }

    /**
     * Procedural swipe (null material). Friction axis decides resistance:
     * friction > 0.5 → waveform burst; otherwise → single primitive.
     * Hardness picks the primitive type for the smooth case.
     */
    private fun proceduralSwipeEffect(axes: TextureAxes, velFactor: Float): VibrationEffect {
        return if (axes.friction > 0.5f) {
            // Resistance burst: amplitude shaped by roughness, rate by hardness.
            val high = (180 + axes.roughness * 65 * velFactor).toInt().coerceIn(40, 255)
            val low = (70 * velFactor).toInt().coerceIn(20, 255)
            val cycleMs = (15 - axes.hardness * 6).toLong().coerceIn(8L, 20L)
            val timings = longArrayOf(cycleMs, cycleMs, cycleMs + 2, cycleMs - 2, cycleMs, cycleMs)
            val amps = intArrayOf(high, low, high, low, high, low)
            VibrationEffect.createWaveform(timings, amps, -1)
        } else {
            // Smooth single primitive: hardness picks sharpness ladder.
            val sharpness = (axes.hardness * 0.7f + (1f - axes.density) * 0.3f).coerceIn(0f, 1f)
            val primitive = when {
                sharpness > 0.66f -> PRIMITIVE_CLICK
                sharpness > 0.33f -> PRIMITIVE_TICK
                else -> PRIMITIVE_LOW_TICK
            }
            val amp = (0.3f + axes.roughness * 0.3f) * velFactor
            VibrationEffect.startComposition()
                .addPrimitive(primitive, amp.coerceIn(0.08f, 1.0f), 0)
                .compose()
        }
    }

    /** Wood: rapid TICK grain run (mirrors scratching-wood-grain foley). */
    private fun woodRecipe(scale: Float): VibrationEffect {
        val cfg = tuning.wood
        val comp = VibrationEffect.startComposition()
        val count = cfg.count.coerceAtLeast(1)
        val baseAmp = (cfg.scale * scale).coerceIn(0f, 1f)
        repeat(count) { i ->
            comp.addPrimitive(PRIMITIVE_TICK, jitterAmp(baseAmp), if (i == 0) 0 else cfg.intervalMs)
        }
        return comp.compose()
    }

    /** Paper: rapid TICK rustle (mirrors crumpling-paper foley). */
    private fun paperRecipe(scale: Float): VibrationEffect {
        val cfg = tuning.paper
        val comp = VibrationEffect.startComposition()
        val count = cfg.count.coerceAtLeast(1)
        val baseAmp = (cfg.scale * scale).coerceIn(0f, 1f)
        repeat(count) { i ->
            comp.addPrimitive(PRIMITIVE_TICK, jitterAmp(baseAmp), if (i == 0) 0 else cfg.intervalMs)
        }
        return comp.compose()
    }

    /**
     * Rocks tap-down: sharp CLICK impact followed by a weighted THUD settle.
     * One-shot event so THUD's long decay tail is fully felt — the "set hand
     * on stone" contact: hard percussive landing, then weight transfer.
     */
    private fun rocksRecipe(scale: Float): VibrationEffect {
        val cfg = tuning.rocks
        val click = (cfg.clickScale * scale).coerceIn(0.05f, 1f)
        val thud = (cfg.thudScale * scale).coerceIn(0.05f, 1f)
        return VibrationEffect.startComposition()
            .addPrimitive(PRIMITIVE_CLICK, click, 0)
            .addPrimitive(PRIMITIVE_THUD, thud, cfg.thudGapMs)
            .compose()
    }

    /** Sand: dense low-amplitude LOW_TICKs with jitter (whispery hiss). */
    private fun sandRecipe(scale: Float): VibrationEffect {
        val cfg = tuning.sand
        val comp = VibrationEffect.startComposition()
        val count = cfg.count.coerceAtLeast(1)
        val baseAmp = (cfg.scale * scale).coerceIn(0f, 1f)
        repeat(count) { i ->
            comp.addPrimitive(PRIMITIVE_LOW_TICK, jitterAmp(baseAmp), if (i == 0) 0 else cfg.intervalMs)
        }
        return comp.compose()
    }

    /**
     * Fabric tap-down: a single soft LOW_TICK contact followed by a SLOW_RISE
     * settle — the "settling into cloth" feel. The prior 6x LOW_TICK train
     * stuttered as a bubbly run; the single tap + slow tail keeps the soft
     * draggy character that distinguishes fabric from sand and rocks without
     * the rapid-fire repetition.
     */
    private fun fabricRecipe(scale: Float): VibrationEffect {
        val cfg = tuning.fabric
        val tick = (cfg.tickScale * scale).coerceIn(0.05f, 1f)
        val rise = (cfg.riseScale * scale).coerceIn(0.05f, 1f)
        return VibrationEffect.startComposition()
            .addPrimitive(PRIMITIVE_LOW_TICK, tick, 0)
            .addPrimitive(PRIMITIVE_SLOW_RISE, rise, cfg.riseGapMs)
            .compose()
    }

    /**
     * Glass: bright CLICK + two decaying TICKs — crystalline "tap-ting-ting".
     * CLICK is the sharpest Composition primitive on the demo device; the
     * decaying TICK tail gives the chord a ringing character matching the
     * wet-finger-on-windowpane foley.
     */
    private fun glassRecipe(scale: Float): VibrationEffect {
        val cfg = tuning.glass
        return VibrationEffect.startComposition()
            .addPrimitive(PRIMITIVE_CLICK, (cfg.clickScale * scale).coerceIn(0.01f, 1f), 0)
            .addPrimitive(PRIMITIVE_TICK,  (cfg.tick1Scale * scale).coerceIn(0.01f, 1f), cfg.gap1Ms)
            .addPrimitive(PRIMITIVE_TICK,  (cfg.tick2Scale * scale).coerceIn(0.01f, 1f), cfg.gap2Ms)
            .compose()
    }

    /**
     * Glass swipe per-crossing: CLICK + QUICK_FALL chord. CLICK gives the
     * bright "tap" the user expects from glass; QUICK_FALL adds a slick
     * release tail so consecutive crossings during a drag read as a
     * polished surface, not a series of disconnected taps.
     */
    private fun glassSwipeBurst(velFactor: Float): VibrationEffect {
        val click = (0.85f * velFactor).coerceIn(0.10f, 1f)
        val fall = (0.60f * velFactor).coerceIn(0.08f, 1f)
        return VibrationEffect.startComposition()
            .addPrimitive(PRIMITIVE_CLICK, click, 0)
            .addPrimitive(PRIMITIVE_QUICK_FALL, fall, 20)
            .compose()
    }

    /**
     * ML null-path gesture stamp. Plays the 8-element `map_primitives()`
     * vector as a single Composition fired on touch-down — the open-
     * vocabulary equivalent of a per-material signature recipe. Per locked
     * decision Q7-B; canonical materials and the drag stream do not consume
     * this. Primitives below a 0.05 amplitude floor are dropped to avoid
     * inaudible micro-events; if every primitive falls under the floor the
     * single strongest one is added so `compose()` doesn't see an empty
     * composition (which throws).
     */
    private fun mlPrimitiveRecipe(weights: FloatArray, scale: Float): VibrationEffect {
        require(weights.size == ML_PRIMITIVES.size) {
            "primitiveWeights must have ${ML_PRIMITIVES.size} entries, got ${weights.size}"
        }
        val comp = VibrationEffect.startComposition()
        var added = 0
        for (i in ML_PRIMITIVES.indices) {
            val amp = (weights[i] * scale).coerceIn(0.01f, 1.0f)
            if (amp > 0.05f) {
                comp.addPrimitive(ML_PRIMITIVES[i], amp, 0)
                added++
            }
        }
        if (added == 0) {
            val maxIdx = weights.indices.maxBy { weights[it] }
            val amp = (weights[maxIdx] * scale).coerceIn(0.01f, 1.0f)
            comp.addPrimitive(ML_PRIMITIVES[maxIdx], amp, 0)
        }
        return comp.compose()
    }

    /**
     * Procedural recipe (axes drive everything). Wobble-pattern amplitude
     * jitter on each tick. 1D-frequency-axis primitive selection. Adds a
     * SLOW_RISE friction-tail when friction > 0.5.
     */
    private fun proceduralRecipe(axes: TextureAxes, scale: Float): VibrationEffect {
        val cfg = tuning.procedural
        val comp = VibrationEffect.startComposition()
        val tickCount = (1 + (axes.roughness * cfg.tickMultiplier).toInt()).coerceAtLeast(1)
        val sharpness = (axes.hardness * 0.7f + (1f - axes.density) * 0.3f).coerceIn(0f, 1f)
        val primitive = when {
            sharpness > 0.66f -> PRIMITIVE_CLICK
            sharpness > 0.33f -> PRIMITIVE_TICK
            else -> PRIMITIVE_LOW_TICK
        }
        val baseAmp = (cfg.baseScale * scale * (0.6f + axes.density * 0.4f)).coerceIn(0.01f, 1f)
        repeat(tickCount) { i ->
            comp.addPrimitive(primitive, jitterAmp(baseAmp, cfg.jitter), if (i == 0) 0 else cfg.intervalMs)
        }
        if (axes.friction > 0.5f) {
            // Friction tail: a SLOW_RISE drag-out. Amp deterministic; accents
            // shouldn't jitter (Wobble convention).
            comp.addPrimitive(
                PRIMITIVE_SLOW_RISE,
                (cfg.frictionTailScale * scale).coerceIn(0.01f, 1f),
                cfg.frictionTailGapMs,
            )
        }
        return comp.compose()
    }

    /**
     * Wobble-pattern amplitude randomization. Clamps to [0.01, 1.0]; 0.01
     * matches Google's intensity floor convention (scale=0 = "minimum
     * perceivable", not silent).
     */
    private fun jitterAmp(base: Float, window: Float = tuning.procedural.jitter): Float {
        if (window <= 0f) return base
        val low = (base - window).coerceAtLeast(0.01f)
        val high = (base + window).coerceAtMost(1.0f)
        return rng.nextFloat() * (high - low) + low
    }

    private companion object {
        private const val TAG = "TactileLensHaptic"

        /**
         * Primitive order MUST match `PrimitiveMapper.map(...)`'s output:
         *   [0] TICK, [1] LOW_TICK, [2] CLICK, [3] THUD,
         *   [4] SLOW_RISE, [5] QUICK_RISE, [6] QUICK_FALL, [7] SPIN.
         */
        private val ML_PRIMITIVES = intArrayOf(
            PRIMITIVE_TICK,
            PRIMITIVE_LOW_TICK,
            PRIMITIVE_CLICK,
            PRIMITIVE_THUD,
            PRIMITIVE_SLOW_RISE,
            PRIMITIVE_QUICK_RISE,
            PRIMITIVE_QUICK_FALL,
            PRIMITIVE_SPIN,
        )
    }
}
