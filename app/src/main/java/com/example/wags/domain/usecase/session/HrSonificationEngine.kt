package com.example.wags.domain.usecase.session

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.sin

/**
 * Continuous HR pitch sonification for meditation/NSDR sessions.
 *
 * Maps HR (40–100 BPM) → pitch (220–440 Hz, A3–A4).
 * Uses AudioTrack in streaming mode with [BUFFER_SIZE]-sample buffer.
 * Smooth frequency transitions via exponential smoothing (portamento).
 * 20ms linear fade-in/fade-out on each buffer to prevent clicks.
 */
class HrSonificationEngine @Inject constructor() {

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val BUFFER_SIZE = 1024
        private const val MIN_HR_BPM = 40f
        private const val MAX_HR_BPM = 100f
        private const val MIN_FREQ_HZ = 220f   // A3
        private const val MAX_FREQ_HZ = 440f   // A4
        private const val SMOOTHING = 0.1f
        private const val VOLUME = 0.3f        // 30% amplitude
        private val FADE_SAMPLES = (SAMPLE_RATE * 0.020).toInt()  // 20ms
    }

    private var audioTrack: AudioTrack? = null
    private var sonificationJob: Job? = null

    @Volatile private var currentFreq = MIN_FREQ_HZ
    @Volatile private var targetFreq = MIN_FREQ_HZ
    private var phase = 0.0

    fun start(scope: CoroutineScope) {
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBufferSize, BUFFER_SIZE * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()

        sonificationJob = scope.launch(Dispatchers.IO) {
            val buffer = FloatArray(BUFFER_SIZE)
            while (isActive) {
                // Exponential smoothing (portamento)
                currentFreq += (targetFreq - currentFreq) * SMOOTHING

                for (i in 0 until BUFFER_SIZE) {
                    val sample = sin(phase).toFloat()
                    val fade = when {
                        i < FADE_SAMPLES -> i.toFloat() / FADE_SAMPLES
                        i > BUFFER_SIZE - FADE_SAMPLES -> (BUFFER_SIZE - i).toFloat() / FADE_SAMPLES
                        else -> 1.0f
                    }
                    buffer[i] = sample * fade * VOLUME
                    phase += 2.0 * PI * currentFreq / SAMPLE_RATE
                    if (phase > 2.0 * PI) phase -= 2.0 * PI
                }
                audioTrack?.write(buffer, 0, BUFFER_SIZE, AudioTrack.WRITE_BLOCKING)
            }
        }
    }

    /** Update target pitch from live HR reading. Thread-safe. */
    fun updateHr(hrBpm: Float) {
        val clamped = hrBpm.coerceIn(MIN_HR_BPM, MAX_HR_BPM)
        val normalized = (clamped - MIN_HR_BPM) / (MAX_HR_BPM - MIN_HR_BPM)
        targetFreq = MIN_FREQ_HZ + normalized * (MAX_FREQ_HZ - MIN_FREQ_HZ)
    }

    fun stop() {
        sonificationJob?.cancel()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        phase = 0.0
        currentFreq = MIN_FREQ_HZ
        targetFreq = MIN_FREQ_HZ
    }
}
