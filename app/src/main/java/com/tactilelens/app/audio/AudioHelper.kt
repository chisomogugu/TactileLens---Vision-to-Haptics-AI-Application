package com.tactilelens.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/**
 * Handles immersive audio synthesis to complement haptic feedback.
 * Uses native AudioTrack in MODE_STATIC to achieve sub-20ms latency.
 */
class AudioHelper {

    private val sampleRate = 48000
    private var audioTrack: AudioTrack? = null

    init {
        setupAudioTrack()
    }

    private fun setupAudioTrack() {
        // Use AudioAttributes optimized for low latency media
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION) // Best for UI/feedback sounds
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        // Minimum buffer size for safety, but we'll use MODE_STATIC for preloaded low-latency playback
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack(
            audioAttributes,
            audioFormat,
            minBufferSize * 4, // Allocate enough space for short synthesis transients
            AudioTrack.MODE_STATIC,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        
        Log.i("AudioHelper", "Low-latency AudioTrack initialized in MODE_STATIC.")
    }

    /**
     * Plays an audio transient modulated by the CNN model's output weights.
     * @param weights The 8-float array of primitive weights from the MobileNet model.
     */
    fun playSynchronizedAudio(weights: FloatArray) {
        if (weights.size != 8) return

        // TODO: Map the 8 weights to specific audio buffer generation logic.
        // For example, if weights[0] (PRIMITIVE_TICK) is high, generate a high-frequency transient.
        // If weights[3] (PRIMITIVE_THUD) is high, generate a low-frequency rumble.
        
        // Pseudo-code for playback:
        // val generatedPcmData: ByteArray = synthesizeAudioBasedOnWeights(weights)
        // audioTrack?.write(generatedPcmData, 0, generatedPcmData.size)
        // audioTrack?.reloadStaticData()
        // audioTrack?.play()
    }

    fun release() {
        audioTrack?.release()
        audioTrack = null
    }
}
