package com.example.wags.domain.usecase.session

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.example.wags.R
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Instrument sound struck once per heartbeat during biofeedback holds.
 *
 * Each value carries its own additive-synthesis recipe (frequency partials
 * with independent decay envelopes), so every heartbeat plays a short
 * "note" whose *pace* is the live HR. The note's **pitch also follows the
 * live HR** (rising HR → rising pitch, falling HR → falling pitch) so HR
 * changes are audible both rhythmically and tonally.
 */
enum class BiofeedbackHrSound(
    val displayName: String,
    val description: String
) {
    GONG(
        "Gong",
        "Deep, resonant gong strike on every heartbeat"
    ),
    BELL(
        "Tibetan Bell",
        "Bright singing-bowl strike with long bloom"
    ),
    HEARTBEAT(
        "Heartbeat",
        "Soft low lub-dub thump tracking your pulse"
    ),
    MARIMBA(
        "Marimba",
        "Warm wooden bar note per beat"
    ),
    CHIME(
        "Chime",
        "Delicate high chime tick per beat"
    ),
    PIANO(
        "Piano",
        "Soft felt-piano note per beat"
    ),
    KALIMBA(
        "Kalimba",
        "Plinking thumb-piano note per beat"
    ),
    HARP(
        "Harp",
        "Gentle plucked harp string per beat"
    ),
    WOODBLOCK(
        "Woodblock",
        "Dry percussive wood click per beat"
    ),
    TOM(
        "Tom Drum",
        "Deep round drum hit per beat"
    ),
    XYLOPHONE(
        "Xylophone",
        "Bright wooden xylophone bar per beat"
    ),
    TUBULAR(
        "Tubular Bells",
        "Long ringing orchestral tube per beat"
    )
}

/**
 * Peaceful background texture whose tonal quality follows the live SpO2
 * percentage. Higher SpO2 → fuller, brighter, louder; falling SpO2 →
 * darker, quieter, lower. The mapping is strongly stepped at the decade
 * thresholds (90, 80, 70, 60, 50, 40 %) — each threshold crossed makes
 * the texture noticeably darker, lower and quieter, so desaturation is
 * clearly audible.
 *
 * The nature textures (Ocean, Wind, Rain, Stream) are real field
 * recordings bundled as WAV resources (see BiofeedbackSonificationEngine)
 * whose playback pitch, brightness and volume are modulated live.
 */
enum class BiofeedbackSpo2Texture(
    val displayName: String,
    val description: String
) {
    WARM_PAD(
        "Warm Pad",
        "Slow harmonic drone that dims and sinks as SpO2 drops"
    ),
    OCEAN(
        "Ocean",
        "Real ocean-wave recording that slows and deepens as SpO2 drops"
    ),
    WIND(
        "Wind",
        "Real breeze recording that fades and darkens as SpO2 drops"
    ),
    RAIN(
        "Rain",
        "Real rainfall recording that thins and deepens as SpO2 drops"
    ),
    STREAM(
        "Stream",
        "Real brook recording that slows and stills as SpO2 drops"
    ),
    DEEP_DRONE(
        "Deep Drone",
        "Low cello-like drone that sinks a semitone per SpO2 decade"
    ),
    CHOIR(
        "Choir Pad",
        "Vowel-like choir swell that hollows out as SpO2 drops"
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
 *     sample clock, so coroutine jitter can never skew the tempo). The
 *     strike **pitch also tracks the live HR**: the base frequency is
 *     scaled by `2^((hr - 60) / 60)`, i.e. roughly two octaves up between
 *     60 and 180 bpm and a smooth drop below 60 bpm, so HR changes are
 *     heard as both tempo AND pitch. Strikes are rendered into a decaying
 *     `beatTail` buffer that is mixed additively into every output chunk,
 *     so long gong decays ring out naturally across chunk boundaries.
 *
 *  2. **SpO2 layer** — a continuously generated peaceful texture (pad /
 *     ocean / wind / rain / …) whose volume, brightness and pitch bend
 *     with the live SpO2 percentage. The mapping combines a smooth
 *     component with a strong **decade step** component: every threshold
 *     at 90, 80, 70, 60, 50 and 40 % that is crossed downward drops the
 *     texture's pitch by a semitone, darkens its timbre and cuts its
 *     volume, making deep desaturation unmistakable while staying musical.
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

        /**
         * HR → pitch: pitchMult = 2^((hr - 60) / HR_PITCH_OCTAVE_BPM).
         * 60 bpm = unity; each 60 bpm rise doubles the pitch (one octave),
         * each 60 bpm fall halves it. Large enough to be clearly audible.
         */
        private const val HR_PITCH_OCTAVE_BPM = 60.0

        // SpO2 mapping: 40..100 % → 0..1 (clamped). The smooth component
        // spans the whole range, and each decade threshold (90, 80, …, 40)
        // crossed adds an extra audible step down in pitch/timbre/volume.
        private const val SPO2_FLOOR = 40f
        private const val SPO2_CEIL = 100f
        private const val DEFAULT_SPO2 = 98f
        private const val SPO2_SMOOTHING = 0.08f   // noticeable but not jarring

        /** Decade thresholds that add an extra pitch/timbre step when crossed. */
        private val SPO2_DECADES = intArrayOf(90, 80, 70, 60, 50, 40)

        /** Semitone drop per crossed decade threshold. */
        private const val SEMITONES_PER_DECADE = 1.0

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
        BiofeedbackHrSound.PIANO -> StrikeRecipe(
            // Felt piano: fundamental + slightly detuned octave + soft 3rd partial
            baseFreqHz = 261.63,
            partials = listOf(
                Partial(1.00, 1.00f, 1.4),
                Partial(2.00, 0.30f, 0.8),
                Partial(3.01, 0.12f, 0.4)
            ),
            amplitude = 0.34f,
            attackMs = 4
        )
        BiofeedbackHrSound.KALIMBA -> StrikeRecipe(
            // Thumb piano: bright pluck with quick decay + metallic overtone
            baseFreqHz = 523.25,
            partials = listOf(
                Partial(1.00, 1.00f, 0.35),
                Partial(2.76, 0.28f, 0.15),
                Partial(5.40, 0.08f, 0.08)
            ),
            amplitude = 0.36f,
            attackMs = 2
        )
        BiofeedbackHrSound.HARP -> StrikeRecipe(
            // Plucked string: cascading partials, medium ring
            baseFreqHz = 329.63,
            partials = listOf(
                Partial(1.00, 1.00f, 1.1),
                Partial(2.00, 0.42f, 0.7),
                Partial(3.00, 0.25f, 0.5),
                Partial(4.02, 0.12f, 0.3)
            ),
            amplitude = 0.32f,
            attackMs = 3
        )
        BiofeedbackHrSound.WOODBLOCK -> StrikeRecipe(
            // Dry wood click: high damped resonance, very fast decay
            baseFreqHz = 1050.0,
            partials = listOf(
                Partial(1.00, 1.00f, 0.05),
                Partial(1.83, 0.45f, 0.03)
            ),
            amplitude = 0.42f,
            attackMs = 1
        )
        BiofeedbackHrSound.TOM -> StrikeRecipe(
            // Round floor-tom: low pitch bend feel via close partials
            baseFreqHz = 110.0,
            partials = listOf(
                Partial(1.00, 1.00f, 0.28),
                Partial(1.50, 0.35f, 0.18)
            ),
            amplitude = 0.46f,
            attackMs = 3
        )
        BiofeedbackHrSound.XYLOPHONE -> StrikeRecipe(
            // Bright xylophone bar: strong 3rd partial, short ring
            baseFreqHz = 659.26,
            partials = listOf(
                Partial(1.00, 1.00f, 0.30),
                Partial(3.02, 0.55f, 0.15),
                Partial(6.50, 0.15f, 0.06)
            ),
            amplitude = 0.34f,
            attackMs = 1
        )
        BiofeedbackHrSound.TUBULAR -> StrikeRecipe(
            // Orchestral chime tube: inharmonic shimmer, long decay
            baseFreqHz = 587.33,
            partials = listOf(
                Partial(1.00, 1.00f, 2.4),
                Partial(2.76, 0.35f, 1.6),
                Partial(5.40, 0.15f, 0.9)
            ),
            amplitude = 0.24f,
            attackMs = 6
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
    private var gustLfoPhase = 0.0
    private var noiseLp1 = 0f
    private var noiseLp2 = 0f

    // ── Real-recording SpO2 textures ────────────────────────────────────────────
    // Field recordings (mono 22.05 kHz PCM16 WAV in res/raw) played back in a
    // loop with live-modulated pitch (resampling), brightness (one-pole LP)
    // and volume, driven by the SpO2 mapping.
    //
    // Sources (Wikimedia Commons):
    //  - bf_ocean.wav  "Waves.ogg" by Dsw4 — Public Domain
    //  - bf_wind.wav   "Breeze birds and geese.ogg" by ezwa (PDSounds) — Public Domain
    //  - bf_rain.wav   "Rain against the window.ogg" by cori (PDSounds) — Public Domain
    //  - bf_stream.wav "Brook sound.ogg" by TwoWings — CC BY 3.0
    private val SAMPLE_SRC_RATE = 22050
    private val samplePos = DoubleArray(4)
    private var sampleLp = 0f
    @Volatile private var oceanSample: FloatArray? = null
    @Volatile private var windSample: FloatArray? = null
    @Volatile private var rainSample: FloatArray? = null
    @Volatile private var streamSample: FloatArray? = null
    private var samplesLoaded = false

    /** Loads the bundled field recordings. Idempotent; call once at app/session start. */
    fun loadSamples(context: Context) {
        if (samplesLoaded) return
        synchronized(this) {
            if (samplesLoaded) return
            oceanSample = loadWav(context, R.raw.bf_ocean)
            windSample = loadWav(context, R.raw.bf_wind)
            rainSample = loadWav(context, R.raw.bf_rain)
            streamSample = loadWav(context, R.raw.bf_stream)
            samplesLoaded = true
        }
    }

    /** Minimal RIFF/WAV parser: mono/stereo 16-bit PCM → mono FloatArray. */
    private fun loadWav(context: Context, resId: Int): FloatArray? = runCatching {
        val bytes = context.resources.openRawResource(resId).use { it.readBytes() }
        // Walk RIFF chunks to find "fmt " and "data"
        var pos = 12 // past RIFF header
        var channels = 1
        var dataOffset = -1
        var dataLen = -1
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val len = readLeInt(bytes, pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> channels = readLeShort(bytes, body + 2)
                "data" -> { dataOffset = body; dataLen = len }
            }
            pos = body + len + (len and 1)
        }
        require(dataOffset > 0 && channels in 1..2) { "unsupported wav" }
        val frames = dataLen / (2 * channels)
        val out = FloatArray(frames)
        var i = 0
        var p = dataOffset
        while (i < frames) {
            var acc = 0
            for (c in 0 until channels) {
                val lo = bytes[p].toInt() and 0xFF
                val hi = bytes[p + 1].toInt()
                acc += ((hi shl 8) or lo).toShort().toInt()
                p += 2
            }
            out[i] = acc.toFloat() / (channels * 32768f)
            i++
        }
        out
    }.getOrNull()

    private fun readLeInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun readLeShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    /**
     * Plays one looping field recording with SpO2-driven modulation:
     * pitch (resample step), brightness (one-pole low-pass cutoff) and
     * volume all follow the live mapping, including the decade steps.
     */
    private fun renderSampleTexture(
        chunk: FloatArray,
        sample: FloatArray?,
        posIdx: Int,
        t: Float,
        pitch: Double,
        swell: Float
    ) {
        if (sample == null || sample.size < 4) { chunk.fill(0f); return }
        val step = pitch * SAMPLE_SRC_RATE.toDouble() / SAMPLE_RATE
        val lpCoef = (0.03 + 0.17 * t).toFloat()          // darker as SpO2 drops
        val gain = (0.16 + 0.38 * t) * swell              // receding as SpO2 drops
        var pos = samplePos[posIdx]
        val n = sample.size
        for (i in chunk.indices) {
            val i0 = pos.toInt()
            val frac = (pos - i0).toFloat()
            val s0 = sample[i0]
            val s1 = sample[(i0 + 1) % n]
            val raw = s0 + (s1 - s0) * frac
            sampleLp += (raw - sampleLp) * lpCoef
            chunk[i] = (sampleLp * gain).toFloat()
            pos += step
            if (pos >= n) pos -= n
        }
        samplePos[posIdx] = pos
    }

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
        stopPreview()
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

    // ── Picker previews ─────────────────────────────────────────────────────────

    private val previewScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var previewJob: Job? = null
    private var previewTrack: AudioTrack? = null

    /**
     * Plays a 3-second demo of the given heartbeat instrument: a simulated
     * HR sweep from 55 → 110 bpm so the user hears both the tempo AND the
     * pitch rise of that instrument. Replaces any preview in flight.
     */
    fun previewHrSound(sound: BiofeedbackHrSound) {
        stopPreview()
        val durSamples = SAMPLE_RATE * 3
        val out = FloatArray(durSamples + TAIL_SAMPLES)
        var nextBeat = 0L
        while (nextBeat < durSamples) {
            val frac = nextBeat.toDouble() / durSamples
            val bpm = 55.0 + 55.0 * frac                       // 55 → 110 bpm sweep
            val pitch = 2.0.pow((bpm - DEFAULT_HR_BPM) / HR_PITCH_OCTAVE_BPM)
            val base = recipeFor(sound)
            val recipe = base.copy(baseFreqHz = base.baseFreqHz * pitch)
            strike(out, nextBeat.toInt(), recipe, out.size)
            nextBeat += (SAMPLE_RATE * 60.0 / bpm).toLong()
        }
        playPreview(out, durSamples)
    }

    /**
     * Plays a 6-second demo of the given SpO2 texture: a simulated sweep
     * from 98 % down to 65 % so the user hears the texture darken, sink and
     * step down through the 90/80/70 thresholds.
     */
    fun previewSpo2Texture(tex: BiofeedbackSpo2Texture) {
        stopPreview()
        val savedTexture = texture
        texture = tex
        val durSamples = SAMPLE_RATE * 6
        val out = FloatArray(durSamples)
        val chunk = FloatArray(CHUNK_SAMPLES)
        val savedT = smoothedSpo2T
        val savedTotal = totalSamples
        totalSamples = 0L
        var written = 0
        // Sweep SpO2 98 → 65 %, updating the smoothed mapping per chunk
        while (written < durSamples) {
            val frac = written.toDouble() / durSamples
            val spo2 = 98.0 - 33.0 * frac
            targetSpo2T = spo2ToT(spo2.toFloat())
            smoothedSpo2T = targetSpo2T
            renderTexture(chunk)
            val n = minOf(CHUNK_SAMPLES, durSamples - written)
            System.arraycopy(chunk, 0, out, written, n)
            written += n
            totalSamples += n
        }
        // Restore live state — the preview borrows the render filters/phases
        texture = savedTexture
        targetSpo2T = savedT
        smoothedSpo2T = savedT
        totalSamples = savedTotal
        playPreview(out, durSamples)
    }

    private fun playPreview(samples: FloatArray, length: Int) {
        val track = AudioTrack.Builder()
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
            .setBufferSizeInBytes(length * 4)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(samples, 0, length, AudioTrack.WRITE_BLOCKING)
        track.setNotificationMarkerPosition(length - 1)
        previewTrack = track
        previewJob = previewScope.launch {
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack?) { stopPreview() }
                override fun onPeriodicNotification(t: AudioTrack?) {}
            })
            track.play()
        }
    }

    /** Stops any in-flight preview immediately. */
    fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        previewTrack?.stop()
        previewTrack?.release()
        previewTrack = null
    }

    // ── Rendering ──────────────────────────────────────────────────────────────

    private fun spo2ToT(pct: Float): Float =
        ((pct - SPO2_FLOOR) / (SPO2_CEIL - SPO2_FLOOR)).coerceIn(0f, 1f)

    /**
     * Number of decade thresholds (90, 80, …, 40) that the live SpO2 has
     * fallen below — each one adds an extra audible pitch/timbre step.
     */
    private fun decadeSteps(t: Float): Int {
        val spo2 = SPO2_FLOOR + t * (SPO2_CEIL - SPO2_FLOOR)
        return SPO2_DECADES.count { spo2 < it }
    }

    /**
     * Overall pitch factor for the SpO2 texture: a smooth glide across the
     * full 40..100 range PLUS one semitone down per crossed decade, so the
     * drop at 90, 80, 70, 60, 50, 40 is unmistakable.
     */
    private fun spo2PitchFactor(t: Float): Double {
        val glide = 0.75 + 0.25 * t                       // smooth −3 .. +0 semitones-ish
        val steps = 2.0.pow(-decadeSteps(t) * SEMITONES_PER_DECADE / 12.0)
        return glide * steps
    }

    private fun renderChunk(chunk: FloatArray) {
        // Smooth the live feeds — slow drift, never jarring.
        smoothedHr += (targetHr - smoothedHr) * HR_SMOOTHING
        smoothedSpo2T += (targetSpo2T - smoothedSpo2T) * SPO2_SMOOTHING

        // 1) Background texture layer
        renderTexture(chunk)

        // 2) Schedule heartbeat strikes on the sample clock (jitter-free tempo).
        //    Pitch scales with the live HR so rising HR = rising pitch.
        val chunkEnd = totalSamples + CHUNK_SAMPLES
        val recipe = recipeFor(hrSound)
        val hrPitch = 2.0.pow((smoothedHr.toDouble() - DEFAULT_HR_BPM) / HR_PITCH_OCTAVE_BPM)
        val pitched = recipe.copy(baseFreqHz = recipe.baseFreqHz * hrPitch)
        while (nextBeatSample < chunkEnd) {
            val offsetInChunk = (nextBeatSample - totalSamples).toInt()
            strike(beatTail, offsetInChunk, pitched)
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
    private fun strike(tail: FloatArray, offset: Int, recipe: StrikeRecipe, limit: Int = TAIL_SAMPLES) {
        strikeOnce(tail, offset, recipe, recipe.amplitude, limit)
        if (recipe.secondThumpOffsetMs > 0) {
            strikeOnce(
                tail,
                offset + recipe.secondThumpOffsetMs * SAMPLE_RATE / 1000,
                recipe,
                recipe.amplitude * 0.7f,
                limit
            )
        }
    }

    private fun strikeOnce(tail: FloatArray, offset: Int, recipe: StrikeRecipe, amplitude: Float, limit: Int) {
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
            if (idx >= limit) break
            tail[idx] += (sample * amplitude * attack).toFloat()
        }
    }

    /**
     * Renders the SpO2-mapped background texture for this chunk.
     * `t = smoothedSpo2T` (0..1) drives volume / brightness / pitch:
     * high SpO2 = full and warm; dropping SpO2 = darker, lower, quieter,
     * with an extra semitone-and-timbre step at each decade threshold.
     */
    private fun renderTexture(chunk: FloatArray) {
        val t = smoothedSpo2T
        val chunkSec = CHUNK_MS / 1000.0
        val pitch = spo2PitchFactor(t)
        // Strong volume mapping — clearly audible across the SpO2 range.
        val masterBase = (0.02 + 0.16 * t).toFloat()

        // Shared slow LFO (breathing-speed swell)
        lfoPhase += 2.0 * PI * 0.09 * chunkSec
        if (lfoPhase > 2.0 * PI) lfoPhase -= 2.0 * PI
        val swell = 0.80 + 0.20 * sin(lfoPhase)

        when (texture) {
            BiofeedbackSpo2Texture.WARM_PAD -> {
                val master = masterBase * swell.toFloat()
                val brightness = 0.20 + 0.80 * t          // upper harmonics fade with SpO2
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

            BiofeedbackSpo2Texture.OCEAN -> renderSampleTexture(chunk, oceanSample, 0, t, pitch, swell.toFloat())

            BiofeedbackSpo2Texture.WIND -> renderSampleTexture(chunk, windSample, 1, t, pitch, swell.toFloat())

            BiofeedbackSpo2Texture.RAIN -> renderSampleTexture(chunk, rainSample, 2, t, pitch, swell.toFloat())

            BiofeedbackSpo2Texture.STREAM -> renderSampleTexture(chunk, streamSample, 3, t, pitch, swell.toFloat())

            BiofeedbackSpo2Texture.DEEP_DRONE -> {
                // Cello-like low drone: the decade steps dominate — one
                // semitone per threshold makes the descent very obvious.
                val master = (0.04 + 0.13 * t).toFloat() * swell.toFloat()
                val brightness = 0.25 + 0.75 * t
                val freqs = doubleArrayOf(65.41, 98.0, 130.81)
                val gains = doubleArrayOf(1.0, 0.45, 0.30 * brightness)
                for (i in chunk.indices) {
                    val tt = i.toDouble() / SAMPLE_RATE
                    var s = 0.0
                    for (v in freqs.indices) {
                        val vibrato = 1.0 + 0.002 * sin(2.0 * PI * 0.7 * tt + v)
                        s += sin(padPhase[v] * 2.0 * PI) * gains[v]
                        padPhase[v] = (padPhase[v] + freqs[v] * pitch * vibrato / SAMPLE_RATE) % 1.0
                    }
                    chunk[i] = (s / 3.0 * master).toFloat()
                }
            }

            BiofeedbackSpo2Texture.CHOIR -> {
                // Vowel-like pad: detuned triad + slow formant wobble. As
                // SpO2 drops the upper voices fade (hollows out) and sink.
                val master = masterBase * swell.toFloat()
                val fullness = 0.15 + 0.85 * t
                val freqs = doubleArrayOf(196.0, 246.94, 293.66)
                val gains = doubleArrayOf(1.0, 0.7 * fullness, 0.55 * fullness * fullness)
                for (i in chunk.indices) {
                    val tt = i.toDouble() / SAMPLE_RATE
                    var s = 0.0
                    for (v in freqs.indices) {
                        val chorus = 1.0 + 0.003 * sin(2.0 * PI * (0.11 + 0.05 * v) * tt + v * 2.3)
                        s += sin(padPhase[v] * 2.0 * PI) * gains[v]
                        padPhase[v] = (padPhase[v] + freqs[v] * pitch * chorus / SAMPLE_RATE) % 1.0
                    }
                    chunk[i] = (s / 3.0 * master).toFloat()
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
