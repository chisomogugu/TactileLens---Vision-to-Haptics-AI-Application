package com.tactilelens.app.data.haptic

import com.tactilelens.app.data.model.HapticRecipe
import com.tactilelens.app.data.model.Material
import com.tactilelens.app.data.model.TextureAxes

/**
 * Engine-agnostic haptic renderer interface.
 *
 * Two firing modes per gesture (see ARCHITECTURE.md §6):
 *  - [onGestureStart] — fires once per gesture (touch-down, material change,
 *    preview tap). Plays a multi-primitive signature recipe ("the stamp").
 *  - [onSwipeMove] — fires per dot crossing during a drag (NOT on a fixed
 *    timer). Plays one primitive per call. The grid composable owns spatial
 *    detection and throttling; the renderer plays what it is asked to play.
 *
 * [play] is a documented future hook for wire format B (ML emits primitive
 * sequences directly). v1 does not wire it in.
 */
interface HapticRenderer {
    fun setEnabled(enabled: Boolean)
    fun onGestureStart(material: Material?, axes: TextureAxes, velocity: Float)
    fun onSwipeMove(material: Material?, axes: TextureAxes, velocity: Float)
    fun onSwipeEnd()
    fun play(recipe: HapticRecipe)
    fun stop()
}
