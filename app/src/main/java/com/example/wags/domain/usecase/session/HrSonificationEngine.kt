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
 *
 * Timing model: each loop iteration writes a complete beat frame (the
 * lub-dub pair followed by silence padding) sized to exactly one beat
 * interval, and the track is kept one beat ahead of playback. This means
 * coroutine wake-up jitter can never starve the AudioTrack — previously an
 * underrun could audibly truncate the "dub", making the beat sound like a
 * single "bu" instead of "bu-bu".
 *
 * Adaptive pitch (optional): when enabled, the thump frequency scales with
 * HR — lower HR produces a deeper, lower-pitched thump; higher HR produces
 * a higher-pitched one.
 */
class HrSonificationEngine @Inject constructor() {

    companion object {
        private const val SAMPLE_RATE = 44100

        // Heartbeat sound parameters
        private const val BASE_THUMP_FREQ_HZ = 60.0    // deep bass thump frequency at rest
        private const val THUMP_DURATION_MS = 120      // each thump lasts 120ms
        private const val DUB_OFFSET_MS = 110          // "dub" follows "lub" by 110ms
        private const val VOLUME = 0.55f               // amplitude (0..1)

        // Adaptive pitch mapping: HR range -> frequency range (linear)
        private const val PITCH_MIN_HR = 40f
        private const val PITCH_MAX_HR = 120f
        private const val PITCH_MIN_FREQ_HZ = 45.0
        private const val PITCH_MAX_FREQ_HZ = 80.0

        // HR smoothing — blends toward new HR at 20% per update (called ~1/s)
        private const val HR_SMOOTHING = 0.2f

        private const val MIN_HR_BPM = 30f
        private const val MAX_HR_BPM = 180f
        private const val DEFAULT_HR_BPM = 60f

        private const val MS_PER_S = 1000
    }

    private var audioTrack: AudioTrack? = null
    private var beatJob: Job? = null

    @Volatile private var smoothedHr: Float = DEFAULT_HR_BPM
    @Volatile private var targetHr: Float = DEFAULT_HR_BPM
    @Volatile private var adaptivePitch: Boolean = true

    // ── Public API ─────────────────────────────────────────────────────────────

    fun start(scope: CoroutineScope) {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        // Generous buffer (~1.5s of float mono audio) so beat frames queue
        // well ahead of playback.
        val bufferBytes = (minBuf * 8).coerceAtLeast(SAMPLE_RATE * 3 / 2 * 4)
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
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()

        beatJob = scope.launch(Dispatchers.IO) {
            // Prime the track with one extra beat frame so the AudioTrack
            // always has audio queued even if a delay() overshoots.
            writeBeatFrame()
            while (isActive) {
                // Smooth HR toward target
                smoothedHr += (targetHr - smoothedHr) * HR_SMOOTHING
                val bpm = smoothedHr.coerceIn(MIN_HR_BPM, MAX_HR_BPM)

                // Write one complete beat: lub-dub + silence, sized to the
                // beat interval. Tempo is driven by the audio stream itself.
                writeBeatFrame(bpm)

                // Wait roughly one beat before queueing the next frame. The
                // one-frame lead absorbs scheduling jitter without underruns.
                val beatIntervalMs = (60_000f / bpm).toLong()
                delay(beatIntervalMs)
            }
        }
    }

    /** Update target HR from live reading. Thread-safe. */
    fun updateHr(hrBpm: Float) {
        targetHr = hrBpm.coerceIn(MIN_HR_BPM, MAX_HR_BPM)
    }

    /**
     * When enabled, thump pitch rises with HR (faster heart = higher pitch).
     * Thread-safe; takes effect from the next beat frame.
     */
    fun setAdaptivePitch(enabled: Boolean) {
        adaptivePitch = enabled
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

    private fun writeBeatFrame(bpm: Float = DEFAULT_HR_BPM) {
        val track = audioTrack ?: return

        val beatIntervalMs = 60_000f / bpm.coerceIn(MIN_HR_BPM, MAX_HR_BPM)
        val frameSamples = (beatIntervalMs * SAMPLE_RATE / MS_PER_S).toInt()
            .coerceAtLeast((DUB_OFFSET_MS + THUMP_DURATION_MS) * SAMPLE_RATE / MS_PER_S)
        val buffer = FloatArray(frameSamples)

        val freqHz = currentThumpFreqHz(bpm)

        // "Lub" starts at sample 0
        writeThump(buffer, startSample = 0, amplitude = VOLUME, freqHz = freqHz)

        // "Dub" starts at DUB_OFFSET_MS — slightly softer
        val dubStart = DUB_OFFSET_MS * SAMPLE_RATE / MS_PER_S
        writeThump(buffer, startSample = dubStart, amplitude = VOLUME * 0.7f, freqHz = freqHz)

        // Blocking write of the full frame (lub-dub + silence padding).
        track.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
    }

    /**
     * Returns the thump frequency for the current HR. With adaptive pitch
     * disabled this is a constant [BASE_THUMP_FREQ_HZ]; when enabled the HR
     * range [PITCH_MIN_HR, PITCH_MAX_HR] maps linearly onto
     * [PITCH_MIN_FREQ_HZ, PITCH_MAX_FREQ_HZ].
     */
    private fun currentThumpFreqHz(bpm: Float): Double {
        if (!adaptivePitch) return BASE_THUMP_FREQ_HZ
        val t = ((bpm - PITCH_MIN_HR) / (PITCH_MAX_HR - PITCH_MIN_HR))
            .coerceIn(0f, 1f)
        return PITCH_MIN_FREQ_HZ + (PITCH_MAX_FREQ_HZ - PITCH_MIN_FREQ_HZ) * t
    }

    /**
     * Writes a single thump into [buffer] starting at [startSample].
     * Shape: sine at [freqHz] × exponential decay envelope.
     */
    private fun writeThump(buffer: FloatArray, startSample: Int, amplitude: Float, freqHz: Double) {
        val thumpSamples = THUMP_DURATION_MS * SAMPLE_RATE / MS_PER_S
        val decayRate = 30.0 / SAMPLE_RATE   // decay constant — fully silent by ~100ms

        for (i in 0 until thumpSamples) {
            val idx = startSample + i
            if (idx >= buffer.size) break
            val t = i.toDouble() / SAMPLE_RATE
            val envelope = exp(-decayRate * i * SAMPLE_RATE / MS_PER_S.toDouble()).toFloat()
            val sine = sin(2.0 * PI * freqHz * t).toFloat()
            buffer[idx] += sine * envelope * amplitude
        }
    }
}
