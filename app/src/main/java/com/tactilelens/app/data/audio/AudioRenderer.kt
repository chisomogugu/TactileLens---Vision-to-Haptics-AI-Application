package com.tactilelens.app.data.audio

import com.tactilelens.app.data.model.Material
import com.tactilelens.app.data.model.TextureAxes

/**
 * Engine-agnostic audio renderer interface.
 *
 * Survived libpd → Kortholt → AudioTrack-direct transitions in the scaffold
 * without modification. Spatial information does not enter the renderer; the
 * grid composable resolves spatial events into [onContact] velocity calls.
 *
 * Lifecycle: [start] from `Activity.onStart` (after AppContainer is built),
 * [stop] from `Activity.onStop`.
 */
interface AudioRenderer {
    fun start()
    fun stop()
    fun setAxes(axes: TextureAxes)
    fun setMaterial(material: Material?)
    fun onContact(velocity: Float)
}
