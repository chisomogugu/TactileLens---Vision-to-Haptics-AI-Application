package com.tactilelens.app

import android.content.Context
import com.tactilelens.app.data.analysis.AnalysisClient
import com.tactilelens.app.data.analysis.MockAnalysisClient
import com.tactilelens.app.data.audio.AudioRenderer
import com.tactilelens.app.data.audio.SamplePackAudioRenderer
import com.tactilelens.app.data.haptic.CompositionHapticRenderer
import com.tactilelens.app.data.haptic.HapticRenderer

/**
 * Composition root.
 *
 * Constructed once in [MainActivity.onCreate], started in `onStart`, stopped
 * in `onStop`. ViewModels receive it as a constructor argument. No DI
 * framework, no service locator. See ARCHITECTURE.md §9.
 */
class AppContainer(context: Context) {

    val audio: AudioRenderer = SamplePackAudioRenderer(context)
    val haptic: HapticRenderer = CompositionHapticRenderer(context)
    val analysis: AnalysisClient = RealAnalysisClient(context)

    fun start() {
        audio.start()
        haptic.setEnabled(true)
    }

    fun stop() {
        audio.stop()
        haptic.stop()
    }
}
