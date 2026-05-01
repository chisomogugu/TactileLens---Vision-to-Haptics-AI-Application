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
import android.os.VibrationEffect.Composition.PRIMITIVE_QUICK_FALL
import android.os.VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
import android.os.VibrationEffect.Composition.PRIMITIVE_SPIN
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
            Material.GLASS -> proceduralRecipe(axes, velocityScale)  // Phase 6: glassRecipe()
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
            Material.GLASS -> proceduralSwipeEffect(axes, velFactor)  // Phase 6: PRIMITIVE_QUICK_FALL
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
     * Fabric resistance: 80 ms rising-then-falling burst per crossing.
     * Communicates "tension builds, then releases" — pulling through cloth.
     */
    private fun fabricResistanceBurst(velFactor: Float): VibrationEffect {
        val timings = longArrayOf(10, 15, 20, 15, 10, 10)
        val amps = intArrayOf(
            (120 * velFactor).toInt().coerceIn(40, 255),
            (180 * velFactor).toInt().coerceIn(40, 255),
            (240 * velFactor).toInt().coerceIn(60, 255),
            (200 * velFactor).toInt().coerceIn(50, 255),
            (150 * velFactor).toInt().coerceIn(40, 255),
            (80 * velFactor).toInt().coerceIn(30, 255),
        )
        return VibrationEffect.createWaveform(timings, amps, -1)
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
     * Rocks resistance: 60 ms big-catch burst per crossing. Initial
     * spike + brief release + bigger spike = climbing over a stone.
     */
    private fun rocksResistanceBurst(velFactor: Float): VibrationEffect {
        val timings = longArrayOf(15, 12, 25, 8)
        val amps = intArrayOf(
            (220 * velFactor).toInt().coerceIn(40, 255),
            (60 * velFactor).toInt().coerceIn(20, 255),
            (245 * velFactor).toInt().coerceIn(80, 255),
            (90 * velFactor).toInt().coerceIn(30, 255),
        )
        return VibrationEffect.createWaveform(timings, amps, -1)
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
            val sharpness = (axes.hardness * 0.7f + (1f - axes.flatBumpy) * 0.3f).coerceIn(0f, 1f)
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

    /** Rocks: N x LOW_TICK at fixed intervals (granular crunch). */
    private fun rocksRecipe(scale: Float): VibrationEffect {
        val cfg = tuning.rocks
        val comp = VibrationEffect.startComposition()
        val count = cfg.count.coerceAtLeast(1)
        val baseAmp = (cfg.scale * scale).coerceIn(0f, 1f)
        repeat(count) { i ->
            comp.addPrimitive(PRIMITIVE_LOW_TICK, jitterAmp(baseAmp), if (i == 0) 0 else cfg.intervalMs)
        }
        return comp.compose()
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
     * Fabric: mid-density LOW_TICK weave with a SLOW_RISE drag-out tail.
     * Communicates "soft scratchy buzz then sustained drag" — distinct from
     * sand (denser, no tail) and rocks (sparser, much louder).
     */
    private fun fabricRecipe(scale: Float): VibrationEffect {
        val cfg = tuning.fabric
        val comp = VibrationEffect.startComposition()
        val count = cfg.count.coerceAtLeast(1)
        val baseAmp = (cfg.scale * scale).coerceIn(0f, 1f)
        repeat(count) { i ->
            comp.addPrimitive(PRIMITIVE_LOW_TICK, jitterAmp(baseAmp), if (i == 0) 0 else cfg.intervalMs)
        }
        comp.addPrimitive(
            PRIMITIVE_SLOW_RISE,
            (cfg.dragTailScale * scale).coerceIn(0.01f, 1f),
            cfg.dragTailGapMs,
        )
        return comp.compose()
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
        val weights = predictPrimitives(axes)
        val comp = VibrationEffect.startComposition()
        
        // Take top 3 dominant primitives (excluding SPIN)
        val dominant = weights.filterKeys { it != PRIMITIVE_SPIN }
            .entries.sortedByDescending { it.value }
            .take(3)
            
        dominant.forEachIndexed { index, entry ->
            // Boost the weight scale slightly so it's felt
            val amp = (entry.value * scale * 1.5f).coerceIn(0.01f, 1.0f)
            // Add a small 10ms gap between primitives in the chord
            comp.addPrimitive(entry.key, jitterAmp(amp), if (index == 0) 0 else 10)
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
