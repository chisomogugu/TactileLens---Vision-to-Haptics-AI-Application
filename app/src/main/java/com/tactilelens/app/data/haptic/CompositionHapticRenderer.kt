package com.tactilelens.app.data.haptic

import android.content.Context
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition.PRIMITIVE_CLICK
import android.os.VibrationEffect.Composition.PRIMITIVE_LOW_TICK
import android.os.VibrationEffect.Composition.PRIMITIVE_SLOW_RISE
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

    override fun onGestureStart(material: Material?, axes: TextureAxes, velocity: Float) {
        if (!enabled || vibrator == null) return
        val velocityScale = (0.5f + velocity.coerceIn(0f, 1f) * 0.5f)
        val effect = when (material) {
            Material.WOOD -> woodRecipe(velocityScale)
            Material.GLASS -> glassRecipe(velocityScale)
            Material.ROCKS -> rocksRecipe(velocityScale)
            Material.SAND -> sandRecipe(velocityScale)
            null -> proceduralRecipe(axes, velocityScale)
        }
        vibrator.vibrate(effect)
    }

    override fun onSwipeMove(material: Material?, axes: TextureAxes, velocity: Float) {
        if (!enabled || vibrator == null) return
        val (primitive, ampBase) = swipeShape(material, axes)
        // Velocity floor 0.85 (narrow [0.85, 1.0] range). A wider range
        // collapses slow drags below feeling threshold; this was the
        // "haptics feel too subtle" fix from the prior session.
        val velFactor = 0.85f + velocity.coerceIn(0f, 1f) * 0.15f
        val baseAmp = (ampBase * velFactor).coerceIn(0.08f, 1.0f)
        val effect = VibrationEffect.startComposition()
            .addPrimitive(primitive, baseAmp, 0)
            .compose()
        vibrator.vibrate(effect)
    }

    override fun onSwipeEnd() {
        // No-op; the grid composable owns crossing detection and rate-throttle,
        // and the renderer fires per-call. Lift state lives in the UI.
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

    /**
     * Per-material drag character: which primitive to repeat, how loud
     * relative to velocity. Density is owned by the grid composable
     * (per-material dot spacing in the UI), not by this renderer.
     */
    private fun swipeShape(material: Material?, axes: TextureAxes): Pair<Int, Float> = when (material) {
        Material.WOOD -> PRIMITIVE_THUD to 0.60f
        Material.GLASS -> PRIMITIVE_TICK to 0.45f
        Material.ROCKS -> PRIMITIVE_LOW_TICK to 0.55f
        Material.SAND -> PRIMITIVE_LOW_TICK to 0.45f
        null -> proceduralSwipeShape(axes)
    }

    /**
     * 1D frequency-axis primitive selector (Pulsar pattern). Continuous
     * `sharpness` derived from hardness + flatBumpy drives the choice
     * between LOW_TICK / TICK / CLICK on a sharpness ladder.
     */
    private fun proceduralSwipeShape(axes: TextureAxes): Pair<Int, Float> {
        val sharpness = (axes.hardness * 0.7f + (1f - axes.flatBumpy) * 0.3f).coerceIn(0f, 1f)
        val primitive = when {
            sharpness > 0.66f -> PRIMITIVE_CLICK
            sharpness > 0.33f -> PRIMITIVE_TICK
            else -> PRIMITIVE_LOW_TICK
        }
        val amp = 0.3f + axes.roughness * 0.3f
        return primitive to amp
    }

    /** Wood: THUD + TICK (warm knock + ring-out). */
    private fun woodRecipe(scale: Float): VibrationEffect {
        val cfg = tuning.wood
        return VibrationEffect.startComposition()
            .addPrimitive(PRIMITIVE_THUD, (cfg.knockScale * scale).coerceIn(0f, 1f), 0)
            .addPrimitive(PRIMITIVE_TICK, (cfg.tailScale * scale).coerceIn(0f, 1f), cfg.gapMs)
            .compose()
    }

    /** Glass: CLICK + TICK (sharp squeak/snap). */
    private fun glassRecipe(scale: Float): VibrationEffect {
        val cfg = tuning.glass
        return VibrationEffect.startComposition()
            .addPrimitive(PRIMITIVE_CLICK, (cfg.clickScale * scale).coerceIn(0f, 1f), 0)
            .addPrimitive(PRIMITIVE_TICK, (cfg.tickScale * scale).coerceIn(0f, 1f), cfg.gapMs)
            .compose()
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
     * Procedural recipe (axes drive everything). Wobble-pattern amplitude
     * jitter on each tick. 1D-frequency-axis primitive selection. Adds a
     * SLOW_RISE friction-tail when friction > 0.5.
     */
    private fun proceduralRecipe(axes: TextureAxes, scale: Float): VibrationEffect {
        val cfg = tuning.procedural
        val comp = VibrationEffect.startComposition()
        val tickCount = (1 + (axes.roughness * cfg.tickMultiplier).toInt()).coerceAtLeast(1)
        val sharpness = (axes.hardness * 0.7f + (1f - axes.flatBumpy) * 0.3f).coerceIn(0f, 1f)
        val primitive = when {
            sharpness > 0.66f -> PRIMITIVE_CLICK
            sharpness > 0.33f -> PRIMITIVE_TICK
            else -> PRIMITIVE_LOW_TICK
        }
        val baseAmp = (cfg.baseScale * scale * (0.6f + axes.flatBumpy * 0.4f)).coerceIn(0.01f, 1f)
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
    }
}
