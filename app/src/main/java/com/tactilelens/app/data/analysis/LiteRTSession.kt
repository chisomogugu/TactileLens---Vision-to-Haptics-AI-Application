package com.tactilelens.app.data.analysis

import android.content.Context
import android.util.Log
import com.qualcomm.qti.QnnDelegate
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Single-model wrapper around `org.tensorflow.lite.Interpreter` bound
 * to the Qualcomm QNN delegate (Hexagon HTP / NPU). Falls back to CPU
 * if QNN cannot bind (older device, missing libQnnHtp, etc).
 *
 * The plan envisioned a `CompiledModel.create()` API; that class does
 * not ship in `com.google.ai.edge.litert:litert:1.0.1`. The 1.0.x AAR
 * is the standard `Interpreter` API repackaged. Per Qualcomm's hackathon
 * guidance, the correct NPU path is `Interpreter` + an explicit
 * `QnnDelegate(BackendType.HTP_BACKEND)` plugged via `addDelegate()`.
 * The "NOT Interpreter+NNAPI" note in the locked decisions referred to
 * the NNAPI delegate specifically; the Interpreter class itself stays.
 *
 * Reads the asset as a `MappedByteBuffer` so LiteRT can mmap it (the
 * APK keeps `.tflite` uncompressed via `noCompress` in build.gradle).
 */
class LiteRTSession(
    context: Context,
    assetName: String,
) : Closeable {

    private val modelBuffer: MappedByteBuffer = loadAssetAsByteBuffer(context, assetName)
    private val qnnDelegate: QnnDelegate?
    private val interpreter: Interpreter

    /** "NPU" if QNN/HTP bound, "CPU" if it fell back. */
    val backendLabel: String

    /** Input tensor shape. Captured once at init for the caller. */
    val inputShape: IntArray

    /** Output tensor shape of output 0. */
    val outputShape: IntArray

    init {
        // Try QNN HTP first. If the delegate constructor throws (no
        // libQnnHtp on this device, etc.), fall back to plain CPU.
        val (delegate, options) = buildOptionsWithQnn()
        qnnDelegate = delegate
        backendLabel = if (delegate != null) "NPU" else "CPU"

        interpreter = Interpreter(modelBuffer, options)
        interpreter.allocateTensors()
        inputShape = interpreter.getInputTensor(0).shape().clone()
        outputShape = interpreter.getOutputTensor(0).shape().clone()

        Log.i(
            TAG,
            "Loaded $assetName on $backendLabel; input=${inputShape.contentToString()}, " +
                "output=${outputShape.contentToString()}",
        )
    }

    /**
     * Run the model. `input` and `output` follow `Interpreter.run`'s
     * shape conventions: a primitive multi-dim array for each tensor.
     * For a single-input single-output float model the typical call is:
     *   `session.run(inputArr3d, Array(1) { FloatArray(N) })`
     */
    fun run(input: Any, output: Any) {
        interpreter.run(input, output)
    }

    /** Wall-clock-friendly accessor; LiteRT exposes nanoseconds for the last `run()`. */
    fun lastInferenceMs(): Long {
        val ns = interpreter.lastNativeInferenceDurationNanoseconds ?: return -1L
        return ns / 1_000_000L
    }

    override fun close() {
        runCatching { interpreter.close() }
        runCatching { qnnDelegate?.close() }
    }

    private fun buildOptionsWithQnn(): Pair<QnnDelegate?, Interpreter.Options> {
        return try {
            val qnnOptions = QnnDelegate.Options().apply {
                setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND)
                setHtpPerformanceMode(
                    QnnDelegate.Options.HtpPerformanceMode.HTP_PERFORMANCE_BURST,
                )
            }
            val delegate = QnnDelegate(qnnOptions)
            val opts = Interpreter.Options().apply {
                addDelegate(delegate)
                setNumThreads(2)
            }
            delegate to opts
        } catch (t: Throwable) {
            // QNN unavailable on this device or this OS image. CPU path.
            Log.w(TAG, "QNN delegate unavailable; falling back to CPU: ${t.message}")
            val opts = Interpreter.Options().apply { setNumThreads(4) }
            null to opts
        }
    }

    private fun loadAssetAsByteBuffer(context: Context, assetName: String): MappedByteBuffer {
        // Standard Android mmap: AssetFileDescriptor -> FileInputStream channel
        // -> map READ_ONLY honoring the asset's offset/length within the APK.
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
