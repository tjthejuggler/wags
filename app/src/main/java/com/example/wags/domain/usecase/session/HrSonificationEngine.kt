package com.example.wags.domain.usecase.session

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * HR-paced heartbeat sonification for meditation/NSDR sessions.
 *
 * Generates a deep, pleasant "lub-dub" heartbeat sound whose pace tracks the
 * live HR reading. Each beat consists of two short thumps (lub at t=0,
 * dub at t=100ms) synthesised from a low-frequency sine burst with an
 * exponential decay envelope — giving a warm, organic thump rather than a
 * harsh click or continuous tone.
 *
 * HR is smoothed with a simple exponential filter so sudden spikes don't
 * cause jarring tempo jumps.
 */
class HrSonificationEngine @Inject constructor() {

    companion object {
        private const val SAMPLE_RATE = 44100

        // Heartbeat sound parameters
        private const val THUMP_FREQ_HZ = 60.0      // deep bass thump frequency
        private const val THUMP_DURATION_MS = 120    // each thump lasts 120ms
        private const val DUB_OFFSET_MS = 110        // "dub" follows "lub" by 110ms
        private const val VOLUME = 0.55f             // amplitude (0..1)

        // HR smoothing — blends toward new HR at 20% per update (called ~1/s)
        private const val HR_SMOOTHING = 0.2f

        private const val MIN_HR_BPM = 30f
        private const val MAX_HR_BPM = 180f
        private const val DEFAULT_HR_BPM = 60f
    }

    private var audioTrack: AudioTrack? = null
    private var beatJob: Job? = null

    @Volatile private var smoothedHr: Float = DEFAULT_HR_BPM
    @Volatile private var targetHr: Float = DEFAULT_HR_BPM

    // ── Public API ─────────────────────────────────────────────────────────────

    fun start(scope: CoroutineScope) {
        val minBuf = AudioTrack.getMinBufferSize(
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
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()

        beatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                // Smooth HR toward target
                smoothedHr += (targetHr - smoothedHr) * HR_SMOOTHING
                val bpm = smoothedHr.coerceIn(MIN_HR_BPM, MAX_HR_BPM)

                // Beat interval in ms
                val beatIntervalMs = (60_000f / bpm).toLong()

                // Write lub + dub into the AudioTrack
                writeLubDub()

                // Wait for the remainder of the beat interval
                val thumpsMs = (DUB_OFFSET_MS + THUMP_DURATION_MS).toLong()
                val waitMs = (beatIntervalMs - thumpsMs).coerceAtLeast(50L)
                delay(waitMs)
            }
        }
    }

    /** Update target HR from live reading. Thread-safe. */
    fun updateHr(hrBpm: Float) {
        targetHr = hrBpm.coerceIn(MIN_HR_BPM, MAX_HR_BPM)
    }

    fun stop() {
        beatJob?.cancel()
        beatJob = null
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        smoothedHr = DEFAULT_HR_BPM
        targetHr = DEFAULT_HR_BPM
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Synthesises and writes a "lub-dub" pair to the AudioTrack.
     * Each thump is a sine burst at [THUMP_FREQ_HZ] with an exponential decay
     * envelope — warm and organic, not harsh.
     */
    private fun writeLubDub() {
        val track = audioTrack ?: return

        val totalSamples = ((DUB_OFFSET_MS + THUMP_DURATION_MS) * SAMPLE_RATE / 1000)
        val buffer = FloatArray(totalSamples)

        // "Lub" starts at sample 0
        writeThump(buffer, startSample = 0, amplitude = VOLUME)

        // "Dub" starts at DUB_OFFSET_MS — slightly softer
        val dubStart = DUB_OFFSET_MS * SAMPLE_RATE / 1000
        writeThump(buffer, startSample = dubStart, amplitude = VOLUME * 0.7f)

        track.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
    }

    /**
     * Writes a single thump into [buffer] starting at [startSample].
     * Shape: sine at [THUMP_FREQ_HZ] × exponential decay envelope.
     */
    private fun writeThump(buffer: FloatArray, startSample: Int, amplitude: Float) {
        val thumpSamples = THUMP_DURATION_MS * SAMPLE_RATE / 1000
        val decayRate = 30.0 / SAMPLE_RATE   // decay constant — fully silent by ~100ms

        for (i in 0 until thumpSamples) {
            val idx = startSample + i
            if (idx >= buffer.size) break
            val t = i.toDouble() / SAMPLE_RATE
            val envelope = exp(-decayRate * i * SAMPLE_RATE / 1000.0).toFloat()
            val sine = sin(2.0 * PI * THUMP_FREQ_HZ * t).toFloat()
            buffer[idx] += sine * envelope * amplitude
        }
    }
}
