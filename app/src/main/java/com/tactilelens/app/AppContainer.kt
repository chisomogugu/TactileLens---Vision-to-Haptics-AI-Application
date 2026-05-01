package com.tactilelens.app

import android.content.Context
import android.util.Log
import com.tactilelens.app.data.analysis.AnalysisClient
import com.tactilelens.app.data.analysis.LiteRTAnalysisClient
import com.tactilelens.app.data.analysis.Segmenter
import com.tactilelens.app.data.analysis.U2NetSegmenter
import com.tactilelens.app.data.audio.AudioRenderer
import com.tactilelens.app.data.audio.SamplePackAudioRenderer
import com.tactilelens.app.data.haptic.CompositionHapticRenderer
import com.tactilelens.app.data.haptic.HapticRenderer
import java.io.Closeable

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

    /**
     * U2Net foreground segmenter. Loaded eagerly in `start()` so the
     * first photo capture has no model-load latency, and closed in
     * `stop()` to free the QNN delegate's native resources.
     *
     * Shared between [MainActivity] (drives the scanner reveal animation
     * directly) and [LiteRTAnalysisClient] (consumes the bbox to crop
     * the encoder input). `MainActivity` segments first, then passes the
     * result to `analysis.analyze(...)` so U2Net runs only once per photo.
     */
    /**
     * Segmenter is [U2NetSegmenter] — fast (~8 ms NPU), produces a soft
     * silhouette good enough for the reveal animation. Used purely for
     * visual feedback; classification crops a fixed patch independently
     * (see [com.tactilelens.app.data.analysis.LiteRTAnalysisClient]).
     *
     * MobileSAM was tried (commit history) and reverted — its SAM-class
     * precision was nice but the 350 ms encoder latency wasn't worth it
     * once we decoupled the visual from the encoder crop. Recoverable
     * from git if a future re-export at 512² input or with baked-in
     * normalization makes it viable.
     */
    private var _segmenter: Segmenter? = null
    val segmenter: Segmenter
        get() = _segmenter ?: U2NetSegmenter(context).also { _segmenter = it }

    /**
     * Analysis client is lazy-init so [stop] / [start] cycles (e.g. user
     * backgrounds the app, returns) recreate it cleanly. The previous
     * eager `val analysis = LiteRTAnalysisClient(...)` was a one-shot
     * factory: after stop() closed its encoder Interpreter, start() never
     * rebuilt it, and the next analyze() crashed with "Interpreter has
     * already been closed" (logcat 2026-05-01 23:23:41 on R3CXC0803XW).
     */
    private var _analysis: AnalysisClient? = null
    val analysis: AnalysisClient
        get() = _analysis ?: LiteRTAnalysisClient(context, segmenter).also { _analysis = it }

    fun start() {
        audio.start()
        haptic.setEnabled(true)
        // Eagerly warm up the model interpreters so the first capture has
        // no cold-start latency. Each `get()` triggers lazy init if null.
        runCatching {
            segmenter
            analysis
        }.onFailure {
            Log.e(TAG, "ML interpreter init failed", it)
        }
    }

    fun stop() {
        audio.stop()
        haptic.stop()
        // Close analysis first (it holds an encoder Interpreter); then the
        // segmenter (separate U2Net Interpreter). Both null out so the
        // next start() rebuilds them via the lazy getters above.
        (_analysis as? Closeable)?.close()
        _analysis = null
        _segmenter?.close()
        _segmenter = null
    }

    private companion object { private const val TAG = "TactileLensContainer" }
}
