package com.example.tactilelens.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log

/**
 * Maps the 8-float array from the AI model to Android's VibrationEffect primitives.
 * Includes hardware fallback protections for devices that lack specific actuator capabilities.
 */
class HapticsHelper(context: Context) {

    private var vibratorManager: VibratorManager? = null

    // Track which primitives the device hardware actually supports
    private var supportsPrimitives = false

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            checkHardwareSupport()
        } else {
            Log.e("HapticsHelper", "TactileLens requires Android 12+ (API 31+) for VibratorManager.")
        }
    }

    private fun checkHardwareSupport() {
        val vibrator = vibratorManager?.defaultVibrator ?: return
        
        // We need to check if the device's actuator can physically handle Composition APIs
        supportsPrimitives = vibrator.areAllPrimitivesSupported(
            VibrationEffect.Composition.PRIMITIVE_TICK,
            VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            VibrationEffect.Composition.PRIMITIVE_CLICK,
            VibrationEffect.Composition.PRIMITIVE_THUD,
            VibrationEffect.Composition.PRIMITIVE_SLOW_RISE,
            VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
            VibrationEffect.Composition.PRIMITIVE_QUICK_FALL,
            VibrationEffect.Composition.PRIMITIVE_SPIN
        )

        if (!supportsPrimitives) {
            Log.w("HapticsHelper", "WARNING: Device does not support all 8 haptic primitives. Fallback modes will be used.")
        } else {
            Log.i("HapticsHelper", "Device fully supports all 8 required haptic primitives!")
        }
    }

    /**
     * Plays the haptic texture based on the CNN output.
     * @param weights The 8-float array of primitive weights.
     */
    fun playTexture(weights: FloatArray) {
        if (weights.size != 8) return
        val vibrator = vibratorManager?.defaultVibrator ?: return

        if (supportsPrimitives && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Devices with advanced actuators (e.g. S25 Ultra) can compose the exact blend
            val composition = VibrationEffect.startComposition()
            
            // Map Model Array -> Android Composition Primitive
            // Scale weights to Android's 0.0 - 1.0 amplitude scale
            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, weights[0])
            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, weights[1])
            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, weights[2])
            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, weights[3])
            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, weights[4])
            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, weights[5])
            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, weights[6])
            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_SPIN, weights[7])

            vibrator.vibrate(composition.compose())
            
        } else {
            // Hardware Fallback: Device lacks advanced actuator.
            // Map the strongest primitive weight to a standard generic vibration burst.
            val maxWeightIndex = weights.indices.maxByOrNull { weights[it] } ?: 0
            
            // Example fallback: duration scales with whether it's a THUD (long) or TICK (short)
            val duration = if (maxWeightIndex == 3) 50L else 15L
            val amplitude = (weights[maxWeightIndex] * 255).toInt().coerceIn(1, 255)
            
            val fallbackEffect = VibrationEffect.createOneShot(duration, amplitude)
            vibrator.vibrate(fallbackEffect)
        }
    }
}
