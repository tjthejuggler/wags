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
import kotlin.math.exp
import kotlin.math.sin

/**
 * Instrument sound struck once per heartbeat during biofeedback holds.
 *
 * Each value carries its own additive-synthesis recipe (frequency partials
 * with independent decay envelopes), so every heartbeat plays a short
 * "note" whose *pace* is the live HR.
 */
enum class BiofeedbackHrSound(
    val displayName: String,
    val emoji: String,
    val description: String
) {
    GONG(
        "Gong", "🔔",
        "Deep, resonant gong strike on every heartbeat"
    ),
    BELL(
        "Tibetan Bell", "🪷",
        "Bright singing-bowl strike with long bloom"
    ),
    HEARTBEAT(
        "Heartbeat", "❤️",
        "Soft low lub-dub thump tracking your pulse"
    ),
    MARIMBA(
        "Marimba", "🎵",
        "Warm wooden bar note per beat"
    ),
    CHIME(
        "Chime", "✨",
        "Delicate high chime tick per beat"
    )
}

/**
 * Peaceful background texture whose tonal quality follows the live SpO2
 * percentage. Higher SpO2 → fuller, brighter, louder; falling SpO2 →
 * darker, quieter, lower — an ambient, subconscious oxygen cue.
 */
enum class BiofeedbackSpo2Texture(
    val displayName: String,
    val emoji: String,
    val description: String
) {
    WARM_PAD(
        "Warm Pad", "🌌",
        "Slow harmonic drone that dims as SpO2 drops"
    ),
    OCEAN(
        "Ocean", "🌊",
        "Gentle waves that recede as SpO2 drops"
    ),
    WIND(
        "Wind", "🍃",
        "Soft breeze that fades as SpO2 drops"
    )
}

/**
 * Real-time biofeedback sonification engine for apnea holds (EXPERIMENTAL).
 *
 * Two independent layers are mixed into one mono [AudioTrack]:
 *
 *  1. **HR layer** — the selected [BiofeedbackHrSound] instrument is struck
 *     once per heartbeat, paced by the live HR (same timing model as
 *     [HrSonificationEngine]: beats are scheduled on the audio stream's
 *     sample clock, so coroutine jitter can never skew the tempo). Strikes
 *     are rendered into a decaying `beatTail` buffer that is mixed
 *     additively into every output chunk, so long gong decays ring out
 *     naturally across chunk boundaries.
 *
 *  2. **SpO2 layer** — a continuously generated peaceful texture (pad /
 *     ocean / wind) whose volume, brightness and (for the pad) pitch bend
 *     with the live SpO2 percentage. The mapping is deliberately slow and
 *     smooth (one-pole filtered) so it reads as ambience, not as an alarm.
 *
 * The public API mirrors [HrSonificationEngine]: [start]/[stop] manage the
 * audio lifecycle, [updateHr]/[updateSpO2] are thread-safe live-value
 * feeds, and the sound/texture selections can be swapped at any time.
 *
 * The engine is deliberately stateless with respect to sessions — nothing
 * is persisted anywhere. It exists purely for the live listening
 * experience while the biofeedback feature is being evaluated.
 */
class BiofeedbackSonificationEngine @Inject constructor() {

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val CHUNK_MS = 100
        private const val CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_MS / 1000

        /** Longest strike decay (gong) — the tail buffer must hold a full ring-out. */
        private const val MAX_STRIKE_SEC = 4
        private const val TAIL_SAMPLES = SAMPLE_RATE * MAX_STRIKE_SEC

        // HR smoothing — blends toward new HR each chunk (10 chunks/s)
        private const val HR_SMOOTHING = 0.06f
        private const val MIN_HR_BPM = 30f
        private const val MAX_HR_BPM = 180f
        private const val DEFAULT_HR_BPM = 60f

        // SpO2 mapping: 85..100 % → 0..1 (clamped). Below 85 % stays at 0 —
        // the texture simply becomes its darkest, quietest self.
        private const val SPO2_FLOOR = 85f
        private const val SPO2_CEIL = 100f
        private const val DEFAULT_SPO2 = 98f
        private const val SPO2_SMOOTHING = 0.04f   // very slow, peaceful drift

        // Master safety clamp
        private const val MASTER_LIMIT = 0.9f
    }

    /**
     * One additive-synthesis partial: sine at `freqMult × baseFreq` with
     * amplitude `gain` and exponential decay time-constant `decaySec`.
     */
    private data class Partial(val freqMult: Double, val gain: Float, val decaySec: Double)

    /** Synthesis recipe for one instrument strike. */
    private data class StrikeRecipe(
        val baseFreqHz: Double,
        val partials: List<Partial>,
        val amplitude: Float,
        val attackMs: Int = 5,
        /** For HEARTBEAT: a second, softer thump offset this many ms after the first. */
        val secondThumpOffsetMs: Int = 0
    )

    private fun recipeFor(sound: BiofeedbackHrSound): StrikeRecipe = when (sound) {
        BiofeedbackHrSound.GONG -> StrikeRecipe(
            baseFreqHz = 96.0,
            partials = listOf(
                Partial(1.00, 1.00f, 2.6),
                Partial(1.48, 0.55f, 1.9),
                Partial(2.19, 0.30f, 1.3),
                Partial(2.93, 0.16f, 0.8)
            ),
            amplitude = 0.34f,
            attackMs = 8
        )
        BiofeedbackHrSound.BELL -> StrikeRecipe(
            baseFreqHz = 392.0,
            partials = listOf(
                Partial(1.00, 1.00f, 1.8),
                Partial(2.01, 0.35f, 1.2),
                Partial(2.76, 0.28f, 0.9),
                Partial(5.43, 0.10f, 0.5)
            ),
            amplitude = 0.26f
        )
        BiofeedbackHrSound.HEARTBEAT -> StrikeRecipe(
            baseFreqHz = 58.0,
            partials = listOf(Partial(1.00, 1.00f, 0.10)),
            amplitude = 0.50f,
            attackMs = 2,
            secondThumpOffsetMs = 110
        )
        BiofeedbackHrSound.MARIMBA -> StrikeRecipe(
            baseFreqHz = 220.0,
            partials = listOf(
                Partial(1.00, 1.00f, 0.45),
                Partial(3.95, 0.22f, 0.20)
            ),
            amplitude = 0.40f
        )
        BiofeedbackHrSound.CHIME -> StrikeRecipe(
            baseFreqHz = 880.0,
            partials = listOf(
                Partial(1.00, 1.00f, 1.1),
                Partial(1.61, 0.30f, 0.7)
            ),
            amplitude = 0.18f
        )
    }

    // ── Audio lifecycle ────────────────────────────────────────────────────────

    private var audioTrack: AudioTrack? = null
    private var renderJob: Job? = null

    // Live metric feeds (thread-safe)
    @Volatile private var targetHr: Float = DEFAULT_HR_BPM
    @Volatile private var smoothedHr: Float = DEFAULT_HR_BPM
    @Volatile private var targetSpo2T: Float = spo2ToT(DEFAULT_SPO2)
    @Volatile private var smoothedSpo2T: Float = spo2ToT(DEFAULT_SPO2)

    @Volatile private var hrSound: BiofeedbackHrSound = BiofeedbackHrSound.GONG
    @Volatile private var texture: BiofeedbackSpo2Texture = BiofeedbackSpo2Texture.WARM_PAD

    // Render-loop state (owned by the IO coroutine only)
    private val beatTail = FloatArray(TAIL_SAMPLES)
    private var totalSamples = 0L
    private var nextBeatSample = 0L

    // Pad oscillator phases / filter states
    private var padPhase = DoubleArray(3)
    private var lfoPhase = 0.0
    private var noiseLp1 = 0f
    private var noiseLp2 = 0f

    fun start(scope: CoroutineScope) {
        if (renderJob?.isActive == true) return
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        val bufferBytes = (minBuf * 8).coerceAtLeast(SAMPLE_RATE / 2 * 4)
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

        // Reset render state so a restarted engine begins clean.
        beatTail.fill(0f)
        totalSamples = 0L
        nextBeatSample = 0L
        padPhase.fill(0.0)
        noiseLp1 = 0f; noiseLp2 = 0f

        renderJob = scope.launch(Dispatchers.IO) {
            val chunk = FloatArray(CHUNK_SAMPLES)
            while (isActive) {
                renderChunk(chunk)
                audioTrack?.write(chunk, 0, CHUNK_SAMPLES, AudioTrack.WRITE_BLOCKING) ?: break
            }
        }
    }

    fun stop() {
        renderJob?.cancel()
        renderJob = null
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        smoothedHr = DEFAULT_HR_BPM
        targetHr = DEFAULT_HR_BPM
        targetSpo2T = spo2ToT(DEFAULT_SPO2)
        smoothedSpo2T = targetSpo2T
    }

    // ── Live metric / configuration API ────────────────────────────────────────

    /** Update target HR (bpm) from the live reading. Thread-safe. */
    fun updateHr(hrBpm: Float) {
        targetHr = hrBpm.coerceIn(MIN_HR_BPM, MAX_HR_BPM)
    }

    /** Update SpO2 percentage from the live reading. Thread-safe. */
    fun updateSpO2(spo2Pct: Int) {
        targetSpo2T = spo2ToT(spo2Pct.toFloat())
    }

    /** Choose the instrument struck per heartbeat. Thread-safe. */
    fun setHrSound(sound: BiofeedbackHrSound) { hrSound = sound }

    /** Choose the SpO2-mapped background texture. Thread-safe. */
    fun setSpo2Texture(tex: BiofeedbackSpo2Texture) { texture = tex }

    // ── Rendering ──────────────────────────────────────────────────────────────

    private fun spo2ToT(pct: Float): Float =
        ((pct - SPO2_FLOOR) / (SPO2_CEIL - SPO2_FLOOR)).coerceIn(0f, 1f)

    private fun renderChunk(chunk: FloatArray) {
        // Smooth the live feeds — slow drift, never jarring.
        smoothedHr += (targetHr - smoothedHr) * HR_SMOOTHING
        smoothedSpo2T += (targetSpo2T - smoothedSpo2T) * SPO2_SMOOTHING

        // 1) Background texture layer
        renderTexture(chunk)

        // 2) Schedule heartbeat strikes on the sample clock (jitter-free tempo)
        val chunkEnd = totalSamples + CHUNK_SAMPLES
        while (nextBeatSample < chunkEnd) {
            val offsetInChunk = (nextBeatSample - totalSamples).toInt()
            strike(beatTail, offsetInChunk, recipeFor(hrSound))
            nextBeatSample += beatIntervalSamples()
        }

        // 3) Mix the decaying strike tail in and shift it left by one chunk
        for (i in 0 until CHUNK_SAMPLES) chunk[i] = (chunk[i] + beatTail[i]).coerceIn(-MASTER_LIMIT, MASTER_LIMIT)
        System.arraycopy(beatTail, CHUNK_SAMPLES, beatTail, 0, TAIL_SAMPLES - CHUNK_SAMPLES)
        java.util.Arrays.fill(beatTail, TAIL_SAMPLES - CHUNK_SAMPLES, TAIL_SAMPLES, 0f)

        totalSamples = chunkEnd
    }

    private fun beatIntervalSamples(): Long {
        val bpm = smoothedHr.coerceIn(MIN_HR_BPM, MAX_HR_BPM)
        return (SAMPLE_RATE * 60.0 / bpm).toLong().coerceAtLeast(1)
    }

    /** Adds one instrument strike into [tail] starting [offset] samples in. */
    private fun strike(tail: FloatArray, offset: Int, recipe: StrikeRecipe) {
        strikeOnce(tail, offset, recipe, recipe.amplitude)
        if (recipe.secondThumpOffsetMs > 0) {
            strikeOnce(
                tail,
                offset + recipe.secondThumpOffsetMs * SAMPLE_RATE / 1000,
                recipe,
                recipe.amplitude * 0.7f
            )
        }
    }

    private fun strikeOnce(tail: FloatArray, offset: Int, recipe: StrikeRecipe, amplitude: Float) {
        val maxDecaySec = recipe.partials.maxOf { it.decaySec }
        val strikeSamples = (maxDecaySec * SAMPLE_RATE).toInt().coerceAtMost(TAIL_SAMPLES - offset)
        val attackSamples = recipe.attackMs * SAMPLE_RATE / 1000

        for (i in 0 until strikeSamples) {
            val t = i.toDouble() / SAMPLE_RATE
            var sample = 0.0
            for (p in recipe.partials) {
                val env = exp(-t / p.decaySec) * p.gain
                sample += sin(2.0 * PI * recipe.baseFreqHz * p.freqMult * t) * env
            }
            // Short attack ramp avoids a click at strike onset
            val attack = if (i < attackSamples && attackSamples > 0) i.toFloat() / attackSamples else 1f
            val idx = offset + i
            if (idx >= TAIL_SAMPLES) break
            tail[idx] += (sample * amplitude * attack).toFloat()
        }
    }

    /**
     * Renders the SpO2-mapped background texture for this chunk.
     * `t = smoothedSpo2T` (0..1) drives volume / brightness / pitch:
     * high SpO2 = full and warm, dropping SpO2 = dark and receding.
     */
    private fun renderTexture(chunk: FloatArray) {
        val t = smoothedSpo2T
        val chunkSec = CHUNK_MS / 1000.0

        // Shared slow LFO (breathing-speed swell)
        lfoPhase += 2.0 * PI * 0.09 * chunkSec
        if (lfoPhase > 2.0 * PI) lfoPhase -= 2.0 * PI
        val swell = 0.85 + 0.15 * sin(lfoPhase)

        when (texture) {
            BiofeedbackSpo2Texture.WARM_PAD -> {
                val master = (0.045 + 0.115 * t).toFloat() * swell.toFloat()
                val brightness = 0.30 + 0.70 * t          // upper harmonics fade with SpO2
                val pitch = 0.97 + 0.05 * t               // pad sinks slightly as SpO2 drops
                val freqs = doubleArrayOf(110.0, 164.81, 220.0)
                val gains = doubleArrayOf(1.0, 0.55, 0.38 * brightness)
                for (i in chunk.indices) {
                    val tt = i.toDouble() / SAMPLE_RATE
                    var s = 0.0
                    for (v in freqs.indices) {
                        // Gentle detune wobble keeps the drone organic
                        val wobble = 1.0 + 0.0015 * sin(2.0 * PI * 0.05 * tt + v * 1.7)
                        s += sin(padPhase[v] * 2.0 * PI) * gains[v]
                        padPhase[v] = (padPhase[v] + freqs[v] * pitch * wobble / SAMPLE_RATE) % 1.0
                    }
                    chunk[i] = (s / 3.0 * master).toFloat()
                }
            }

            BiofeedbackSpo2Texture.OCEAN -> {
                val master = (0.05 + 0.11 * t).toFloat() * swell.toFloat()
                val lpCoef = (0.015 + 0.10 * t).toFloat() // darker water as SpO2 drops
                for (i in chunk.indices) {
                    val white = random() * 2f - 1f
                    noiseLp1 += (white - noiseLp1) * lpCoef
                    chunk[i] = noiseLp1 * master
                }
            }

            BiofeedbackSpo2Texture.WIND -> {
                val master = (0.035 + 0.095 * t).toFloat() * swell.toFloat()
                val brightCoef = (0.05 + 0.16 * t).toFloat()
                for (i in chunk.indices) {
                    val white = random() * 2f - 1f
                    noiseLp1 += (white - noiseLp1) * 0.02f       // fixed dark component
                    noiseLp2 += (white - noiseLp2) * brightCoef  // brightness follows SpO2
                    chunk[i] = (noiseLp2 - noiseLp1) * master    // crude band-pass
                }
            }
        }
    }

    /** Deterministic-ish uniform noise in [-1, 1]. */
    private var noiseSeed = 123456789L
    private fun random(): Float {
        noiseSeed = noiseSeed * 6364136223846793005L + 1442695040888963407L
        return ((noiseSeed ushr 40) and 0xFFFFF).toFloat() / 0xFFFFF * 2f - 1f
    }
}
