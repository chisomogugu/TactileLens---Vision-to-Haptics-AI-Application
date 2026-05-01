package com.tactilelens.app.data.analysis

import android.content.Context
import android.util.Log
import com.qualcomm.qti.QnnDelegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.InterpreterApi
import java.io.Closeable
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Single-model wrapper around `org.tensorflow.lite.Interpreter` bound
 * to the Qualcomm QNN delegate (Hexagon HTP / NPU). Falls back to CPU
 * (XNNPack) if QNN cannot bind.
 *
 * Setup mirrors Qualcomm's official `image_classification_android` sample
 * (quic/ai-hub-apps repo, `_shared/android/tflite_helpers/TFLiteHelpers.java`):
 *
 *   1. `QnnDelegate.checkCapability` to detect HTP_RUNTIME_FP16 vs
 *      HTP_RUNTIME_QUANTIZED on the actual device, instead of brute-force
 *      trying both — the device tells us upfront which precision its
 *      Hexagon revision supports.
 *   2. `setSkelLibraryDir(nativeLibraryDir)` only — no `setLibraryPath`.
 *      The skel libs (libQnnHtpV79Skel.so etc.) are loaded by libQnnHtp.so
 *      from this directory; libQnnHtp.so itself is found via standard
 *      System.loadLibrary which respects the same dir.
 *   3. `setCacheDir + setModelToken` so the QNN delegate caches the
 *      compiled-for-NPU graph across launches. Without this, every cold
 *      start recompiles which is slow.
 *   4. `Interpreter.Options.setRuntime(FROM_APPLICATION_ONLY)` to lock
 *      to the bundled LiteRT runtime instead of Play Services.
 *   5. `setUseNNAPI(false)` — explicitly disable NNAPI fallback.
 *   6. `setUseXNNPACK(true) + setAllowBufferHandleOutput(true)` for
 *      sane CPU fallback and zero-copy delegate outputs.
 *
 * Supplemented by `<uses-native-library>` declarations in AndroidManifest
 * for `libcdsprpc.so` (CDSP RPC bridge) so libQnnHtpV*Stub.so can dlopen
 * vendor libraries past Android 13's library-namespace isolation.
 */
class LiteRTSession(
    context: Context,
    private val assetName: String,
) : Closeable {

    private val modelBuffer: MappedByteBuffer = loadAssetAsByteBuffer(context, assetName)
    private val nativeLibDir: String = context.applicationInfo.nativeLibraryDir
    private val cacheDir: String = context.cacheDir.absolutePath
    private val qnnDelegate: QnnDelegate?
    private val interpreter: Interpreter

    /** "NPU" if QNN/HTP bound, "CPU" if it fell back. */
    val backendLabel: String

    /** Input tensor shape. Captured once at init for the caller. */
    val inputShape: IntArray

    /** Output tensor shape of output 0. */
    val outputShape: IntArray

    init {
        val attempted = tryQnnInterpreter()
        if (attempted != null) {
            interpreter = attempted.first
            qnnDelegate = attempted.second
            backendLabel = "NPU"
        } else {
            interpreter = buildCpuInterpreter()
            qnnDelegate = null
            backendLabel = "CPU"
        }

        interpreter.allocateTensors()
        inputShape = interpreter.getInputTensor(0).shape().clone()
        outputShape = interpreter.getOutputTensor(0).shape().clone()

        Log.i(
            TAG,
            "Loaded $assetName on $backendLabel; input=${inputShape.contentToString()}, " +
                "output=${outputShape.contentToString()}",
        )
    }

    fun run(input: Any, output: Any) {
        interpreter.run(input, output)
    }

    fun lastInferenceMs(): Long {
        val ns = interpreter.lastNativeInferenceDurationNanoseconds ?: return -1L
        return ns / 1_000_000L
    }

    override fun close() {
        runCatching { interpreter.close() }
        runCatching { qnnDelegate?.close() }
    }

    private fun tryQnnInterpreter(): Pair<Interpreter, QnnDelegate>? {
        val hasFp16 = QnnDelegate.checkCapability(QnnDelegate.Capability.HTP_RUNTIME_FP16)
        val hasQuantized = QnnDelegate.checkCapability(QnnDelegate.Capability.HTP_RUNTIME_QUANTIZED)
        if (!hasFp16 && !hasQuantized) {
            Log.w(TAG, "$assetName: device reports no HTP runtime support; CPU only.")
            return null
        }

        var delegate: QnnDelegate? = null
        return try {
            val qnnOptions = QnnDelegate.Options().apply {
                setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND)
                setHtpUseConvHmx(QnnDelegate.Options.HtpUseConvHmx.HTP_CONV_HMX_ON)
                setHtpPerformanceMode(
                    QnnDelegate.Options.HtpPerformanceMode.HTP_PERFORMANCE_BURST,
                )
                if (hasFp16) {
                    // FP16 path covers our AI Hub-compiled fp32 TFLite. INT8
                    // quantized models also load on the FP16 runtime; the
                    // native HTP picks per-op based on the graph's dtypes.
                    setHtpPrecision(QnnDelegate.Options.HtpPrecision.HTP_PRECISION_FP16)
                }
                // Skel libs live next to libQnnHtp.so in the app's native lib
                // dir. Without an explicit dir, libQnnHtp falls back to system
                // search paths and can't find our packaged V68..V79 skels.
                setSkelLibraryDir(nativeLibDir)
                // Persistent NPU compile cache. Re-keying on the asset name
                // means swapping a TFLite invalidates only that model's cache.
                setCacheDir(cacheDir)
                setModelToken(assetName)
                setLogLevel(QnnDelegate.Options.LogLevel.LOG_LEVEL_WARN)
            }
            delegate = QnnDelegate(qnnOptions)
            val opts = Interpreter.Options().apply {
                setRuntime(InterpreterApi.Options.TfLiteRuntime.FROM_APPLICATION_ONLY)
                setUseNNAPI(false)
                setUseXNNPACK(true)
                setAllowBufferHandleOutput(true)
                setNumThreads(Runtime.getRuntime().availableProcessors() / 2)
                addDelegate(delegate)
            }
            val interp = Interpreter(modelBuffer, opts)
            interp to delegate
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "$assetName: QNN HTP delegate failed; falling back to CPU. " +
                    "Cause: ${t.message}",
            )
            runCatching { delegate?.close() }
            null
        }
    }

    private fun buildCpuInterpreter(): Interpreter {
        val opts = Interpreter.Options().apply {
            setRuntime(InterpreterApi.Options.TfLiteRuntime.FROM_APPLICATION_ONLY)
            setUseNNAPI(false)
            setUseXNNPACK(true)
            setNumThreads(Runtime.getRuntime().availableProcessors() / 2)
        }
        return Interpreter(modelBuffer, opts)
    }

    private fun loadAssetAsByteBuffer(context: Context, assetName: String): MappedByteBuffer {
        val afd = context.assets.openFd(assetName)
        return afd.use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { stream ->
                val buf = stream.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fd.startOffset,
                    fd.declaredLength,
                )
                buf.order(ByteOrder.nativeOrder())
                buf
            }
        }
    }

    private companion object { private const val TAG = "TactileLensLiteRT" }
}
