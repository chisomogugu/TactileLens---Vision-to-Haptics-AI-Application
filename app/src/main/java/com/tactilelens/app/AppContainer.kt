package com.tactilelens.app

import android.content.Context
import android.util.Log
import com.tactilelens.app.data.analysis.AnalysisClient
import com.tactilelens.app.data.analysis.LiteRTSession
import com.tactilelens.app.data.analysis.MockAnalysisClient
import com.tactilelens.app.data.analysis.U2NetSegmenter
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
class AppContainer(private val context: Context) {

    val audio: AudioRenderer = SamplePackAudioRenderer(context)
    val haptic: HapticRenderer = CompositionHapticRenderer(context)
    val analysis: AnalysisClient = MockAnalysisClient(context)

    /**
     * U2Net foreground segmenter. Loaded eagerly in `start()` so the
     * first photo capture has no model-load latency, and closed in
     * `stop()` to free the QNN delegate's native resources.
     *
     * Phase 4 will replace the public callsite with `LiteRTAnalysisClient`
     * which owns the segmenter internally; for Phase 2 it lives here so
     * `MainActivity` can run U2Net for the scanner reveal animation.
     */
    private var _segmenter: U2NetSegmenter? = null
    val segmenter: U2NetSegmenter
        get() = _segmenter ?: U2NetSegmenter(context).also { _segmenter = it }

    fun start() {
        audio.start()
        haptic.setEnabled(true)
        runCatching { segmenter }.onFailure {
            Log.e(TAG, "U2NetSegmenter eager init failed", it)
        }
        // Phase 2 diagnostic: open the encoder + head as bare LiteRT
        // sessions just so the resolved backend (NPU vs CPU) shows up
        // in logcat. Closed immediately; Phase 4 owns the real lifecycle.
        listOf("efficientnet_lite0.tflite", "linear_head.tflite").forEach { asset ->
            runCatching { LiteRTSession(context, asset).use { /* logged in init */ } }
                .onFailure { Log.e(TAG, "Smoke-load $asset failed", it) }
        }
    }

    fun stop() {
        audio.stop()
        haptic.stop()
        _segmenter?.close()
        _segmenter = null
    }

    private companion object { private const val TAG = "TactileLensContainer" }
}
