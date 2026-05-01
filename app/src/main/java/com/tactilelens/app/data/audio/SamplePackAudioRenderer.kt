package com.tactilelens.app.data.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.tactilelens.app.data.model.Material
import com.tactilelens.app.data.model.TextureAxes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sample-bank audio renderer (Option A: native-rate loop + velocity gain).
 *
 * Loads `assets/audio/<material>/loop.wav` per material at startup, plays
 * the active material's loop at native sample rate, modulates output gain
 * by swipe velocity. No pitch shift, no rate scrub. Wrap is hidden by a
 * dual-pointer phase-locked equal-power crossfade. Output goes through a
 * tanh soft saturator.
 *
 * See ARCHITECTURE.md §5 for the full pipeline. Tuned constants in the
 * companion object survived multiple device-tuning rounds; do not change
 * without validating on the demo device.
 *
 * Expected source format (what the project's ffmpeg recipe produces):
 * 44.1 kHz mono 16-bit PCM WAV. Other sample rates work but pitch shifts
 * by ratio.
 */
class SamplePackAudioRenderer(private val context: Context) : AudioRenderer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var renderJob: Job? = null
    private var track: AudioTrack? = null

    private val banks = mutableMapOf<Material, FloatArray>()
    @Volatile private var activeMaterial: Material? = null

    /** Set by onContact each pointer-MOVE event. Decays toward 0 on lift. */
    @Volatile private var swipeVelocity = 0f

    /** Integer pointer into the active material's buffer. Wraps at len. */
    private var readPos: Int = 0

    /** Slewed gain state, smoothed per-sample to defeat slam clicks. */
    private var smoothGain: Float = 0f

    override fun start() {
        if (renderJob != null) return
        loadBanks()

        val sampleRate = 44100
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(4096)

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .build()
        val newTrack = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
            .build()
        newTrack.play()
        track = newTrack
        Log.i(TAG, "AudioTrack started: rate=$sampleRate buf=$bufferSize")

        renderJob = scope.launch {
            val frameCount = 1024
            val out = FloatArray(frameCount)
            while (isActive) {
                synthesize(out)
                newTrack.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
            }
        }
    }

    override fun stop() {
        renderJob?.cancel()
        renderJob = null
        track?.run {
            try { stop() } catch (_: Throwable) {}
            release()
        }
        track = null
    }

    override fun setAxes(axes: TextureAxes) {
        // Option A ignores axes; gain is the only modulation.
        // Future: an axis-driven sample blender lives behind the same interface.
    }

    override fun setMaterial(material: Material?) {
        if (activeMaterial != material) {
            activeMaterial = material
        }
    }

    override fun onContact(velocity: Float) {
        // Symmetric one-pole LPF on the velocity input. Defeats finger-jitter
        // spikes at the input. tau ~25 ms at Compose's typical event rate.
        val v = velocity.coerceIn(0f, 1f)
        swipeVelocity = swipeVelocity * VEL_LPF + v * (1f - VEL_LPF)
    }

    private fun synthesize(out: FloatArray) {
        val mat = activeMaterial
        val buf = if (mat != null) banks[mat] else null
        if (buf == null || buf.isEmpty()) {
            out.fill(0f)
            swipeVelocity *= DECAY_PER_BUFFER
            smoothGain *= 0.9f
            return
        }

        val len = buf.size
        val targetGain = swipeVelocity * GAIN_MAX
        val halfLen = len / 2

        var pos = readPos
        var sg = smoothGain

        for (i in out.indices) {
            // One-pole gain slew, tau ~45 ms. Velocity peaks swell, not slam.
            sg += GAIN_ALPHA * (targetGain - sg)

            val sA = buf[pos]
            val pB = if (pos + halfLen >= len) pos + halfLen - len else pos + halfLen
            val sB = buf[pB]

            // Equal-power crossfade: wA^2 + wB^2 = 1 everywhere.
            val tPhase = 2f * kotlin.math.abs(pos.toFloat() / len - 0.5f)
            val wA = kotlin.math.sqrt(1f - tPhase)
            val wB = kotlin.math.sqrt(tPhase)
            val mixed = sA * wA + sB * wB

            // Soft saturator. Output bounded ~+-0.55, peaks compressed.
            val drive = mixed * sg * SAT_DRIVE
            out[i] = kotlin.math.tanh(drive.toDouble()).toFloat() / SAT_DRIVE

            pos += 1
            if (pos >= len) pos -= len
        }
        readPos = pos
        smoothGain = sg
        swipeVelocity *= DECAY_PER_BUFFER
    }

    private fun loadBanks() {
        if (banks.isNotEmpty()) return
        Material.values().forEach { mat ->
            val name = mat.name.lowercase()
            val candidates = listOf("audio/$name/loop.wav", "audio/$name/grain_01.wav")
            for (path in candidates) {
                val data = tryLoadWav(path) ?: continue
                banks[mat] = data
                Log.i(TAG, "Loaded $path: ${data.size} samples (${data.size / 44100f}s)")
                break
            }
            if (!banks.containsKey(mat)) {
                Log.w(TAG, "No loop.wav or grain_01.wav for ${mat.name} under assets/audio/$name/")
            }
        }
    }

    private fun tryLoadWav(path: String): FloatArray? {
        return try {
            context.assets.open(path).use { stream -> decodeWavMono(stream) }
        } catch (_: Exception) {
            null
        }
    }

    /** Minimal RIFF/WAVE parser. Reads 44.1 kHz mono 16-bit PCM. */
    private fun decodeWavMono(stream: InputStream): FloatArray {
        val bytes = stream.readBytes()
        if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") {
            throw IllegalArgumentException("not a RIFF/WAVE file")
        }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var channels = 1
        var bitsPerSample = 16
        var sampleRate = 44100
        var dataOffset = -1
        var dataSize = 0
        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4)
            val chunkSize = bb.getInt(offset + 4)
            when (chunkId) {
                "fmt " -> {
                    channels = bb.getShort(offset + 10).toInt()
                    sampleRate = bb.getInt(offset + 12)
                    bitsPerSample = bb.getShort(offset + 22).toInt()
                }
                "data" -> {
                    dataOffset = offset + 8
                    dataSize = chunkSize
                }
            }
            offset += 8 + chunkSize + (chunkSize and 1)
            if (dataOffset >= 0) break
        }
        if (dataOffset < 0) throw IllegalArgumentException("no data chunk")

        val bytesPerSample = bitsPerSample / 8
        val frameStride = channels * bytesPerSample
        val frameCount = dataSize / frameStride
        val out = FloatArray(frameCount)
        when (bitsPerSample) {
            16 -> {
                for (f in 0 until frameCount) {
                    var sum = 0
                    for (c in 0 until channels) {
                        val pos = dataOffset + f * frameStride + c * bytesPerSample
                        sum += bb.getShort(pos).toInt()
                    }
                    val avg = sum.toFloat() / channels / 32768f
                    out[f] = avg.coerceIn(-1f, 1f)
                }
            }
            else -> {
                Log.w(TAG, "Unsupported bitsPerSample=$bitsPerSample. Expected 16. Filling silence.")
                return FloatArray(0)
            }
        }
        if (sampleRate != 44100) {
            Log.w(TAG, "Sample rate $sampleRate != 44100. Pitch will shift by ${44100f / sampleRate}.")
        }
        return out
    }

    private companion object {
        private const val TAG = "TactileLensAudio"
        private const val DECAY_PER_BUFFER = 0.85f
        private const val GAIN_ALPHA = 0.00050f
        private const val GAIN_MAX = 0.25f
        private const val VEL_LPF = 0.7f
        private const val SAT_DRIVE = 1.8f
    }
}
