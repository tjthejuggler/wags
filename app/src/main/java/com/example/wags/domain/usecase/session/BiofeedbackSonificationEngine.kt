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
    ),
    NONE(
        "None",
        "No heartbeat sound — only the SpO2 soundscape plays"
    )
}

/**
 * Peaceful background *soundscape* that follows the live SpO2 percentage by
 * telling a little story: each setting is a set of layered sounds, and as
 * SpO2 falls through its bands (95 / 90 / 85 / 80 … 40 %) new layers are
 * added — and sometimes taken away again — so the descent is heard as
 * changing scenes rather than a single sound bending in pitch (there is NO
 * pitch modulation from SpO2). See [BiofeedbackSoundscapes] for the exact
 * layer progression of each setting.
 *
 * The nature textures (Ocean, Wind, Rain, Stream) combine real field
 * recordings bundled as WAV resources with procedurally synthesised layers
 * (birds, thunder, crickets, whale-song, …).
 */
enum class BiofeedbackSpo2Texture(
    val displayName: String,
    val description: String
) {
    WARM_PAD(
        "Warm Pad",
        "A chord that empties voice by voice and grounds into a deep pulse"
    ),
    OCEAN(
        "Ocean",
        "Sunny shore with gulls, growing surf, whale song, then the deep"
    ),
    WIND(
        "Wind",
        "Evening breeze with crickets builds to a distant storm, then dawn birds"
    ),
    RAIN(
        "Rain",
        "Calm rain grows to storm with wind and thunder, then birds and playing children"
    ),
    STREAM(
        "Stream",
        "A brook with birds thins to drips and sinks into the deep current"
    ),
    DEEP_DRONE(
        "Deep Drone",
        "A low root that gains a fifth, a throb, and beating tension"
    ),
    CHOIR(
        "Choir Pad",
        "A choir that loses its voices one register at a time"
    ),
    NONE(
        "None",
        "No SpO2 soundscape — only the heartbeat instrument plays"
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
        val secondThumpOffsetMs: Int = 0,
        /**
         * Minimum spacing between strikes (ms). Sustained/melodic instruments
         * (marimba, kalimba, xylophone, tubular bells, …) sound cluttered and
         * rushed when re-struck on every single heartbeat, especially at high
         * HR — their ring-out needs room to breathe. 0 = strike every beat.
         */
        val minRestrikeMs: Int = 0
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
            // Boosted after user feedback that it was barely audible — the
            // sampled recording is now also peak-normalised on load.
            baseFreqHz = 58.0,
            partials = listOf(Partial(1.00, 1.00f, 0.10)),
            amplitude = 0.85f,
            attackMs = 2,
            secondThumpOffsetMs = 110
        )
        BiofeedbackHrSound.MARIMBA -> StrikeRecipe(
            // Warm wooden bar: the sampled vibraphone was too busy/rushed;
            // this synth recipe rings longer and is spaced out per beat.
            baseFreqHz = 196.0,
            partials = listOf(
                Partial(1.00, 1.00f, 0.90),
                Partial(3.93, 0.18f, 0.35),
                Partial(9.20, 0.05f, 0.12)
            ),
            amplitude = 0.30f,
            attackMs = 2,
            minRestrikeMs = 900
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
            // Thumb piano: the bundled recording was effectively inaudible,
            // so this now uses the synth path — soft pluck, gentle spacing.
            baseFreqHz = 392.0,
            partials = listOf(
                Partial(1.00, 1.00f, 0.50),
                Partial(2.51, 0.20f, 0.20),
                Partial(5.10, 0.06f, 0.08)
            ),
            amplitude = 0.32f,
            attackMs = 3,
            minRestrikeMs = 600
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
            // Dry wood click: the sampled recording was harsh/aggressive, so
            // this synth version is much softer, lower-pitched and quieter.
            baseFreqHz = 620.0,
            partials = listOf(
                Partial(1.00, 1.00f, 0.06),
                Partial(2.57, 0.30f, 0.035)
            ),
            amplitude = 0.22f,
            attackMs = 2
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
            // Bright xylophone bar: softened + spaced out (was too fast and
            // too dense at high HR — the strikes piled up on each other).
            baseFreqHz = 523.25,
            partials = listOf(
                Partial(1.00, 1.00f, 0.40),
                Partial(3.01, 0.35f, 0.18),
                Partial(6.40, 0.08f, 0.07)
            ),
            amplitude = 0.26f,
            attackMs = 2,
            minRestrikeMs = 800
        )
        BiofeedbackHrSound.TUBULAR -> StrikeRecipe(
            // Orchestral chime tube: long ring-out that needs space between
            // strikes, otherwise it becomes an inharmonic wall of sound.
            baseFreqHz = 293.66,
            partials = listOf(
                Partial(1.00, 1.00f, 3.2),
                Partial(2.00, 0.40f, 2.2),
                Partial(2.76, 0.25f, 1.4),
                Partial(5.40, 0.08f, 0.7)
            ),
            amplitude = 0.26f,
            attackMs = 8,
            minRestrikeMs = 2600
        )
        BiofeedbackHrSound.NONE -> StrikeRecipe(
            // Never scheduled — renderChunk skips strikes for NONE.
            baseFreqHz = 440.0,
            partials = listOf(Partial(1.00, 0.0f, 0.01)),
            amplitude = 0f
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

    // User-adjustable layer volumes (0..1), persisted by the caller. They are
    // per-layer (HR instrument vs SpO2 soundscape) and applied to the live mix
    // and to the picker previews alike.
    @Volatile private var hrVolume = 1f
    @Volatile private var spo2Volume = 1f

    // Render-loop state (owned by the IO coroutine only)
    private val beatTail = FloatArray(TAIL_SAMPLES)
    private var totalSamples = 0L
    private var nextBeatSample = 0L
    private var lastStrikeSample = Long.MIN_VALUE / 2

    // ── Real-recording SpO2 textures ────────────────────────────────────────────
    // Field recordings (mono 22.05 kHz PCM16 WAV in res/raw) played back in a
    // loop with live-modulated pitch (resampling), brightness (one-pole LP)
    // and volume, driven by the SpO2 mapping.
    //
    // Sources (Wikimedia Commons; layers without an asset fall back to
    // procedural synthesis in SoundscapeRenderer):
    //  - bf_ocean.wav      "Waves.ogg" by Dsw4 — Public Domain
    //  - bf_wind.wav       "Breeze birds and geese.ogg" by ezwa (PDSounds) — Public Domain
    //  - bf_rain.wav       "Rain against the window.ogg" by cori (PDSounds) — Public Domain
    //  - bf_stream.wav     "Brook sound.ogg" by TwoWings — CC BY 3.0
    //  - bf_wind_gust.wav  "Wind in Swedish pine forest at 25 mps.ogg" — CC BY 4.0 (ArildV)
    //  - bf_rain_heavy.wav "Rain (1).ogg" — Commons free licence
    //  - bf_thunder.wav    "Storm thunderbolts.ogg" (PDSounds) — Public Domain
    //  - bf_birds.wav      "Birds singing in garden.ogg" (PDSounds) — Public Domain
    //  - bf_gulls.wav      "Gull 1.ogg" — Commons free licence
    //  - bf_crickets.wav   "Cricket.ogg" — Commons free licence
    //  - bf_whale.wav      "Humpback whale moo.ogg" — Commons free licence
    //  - bf_children.wav   "Douzen kids on playground.ogg" — Commons free licence
    //  - bf_rain_light.wav "Sound of light rainfall.ogg" — Commons free licence
    //  - bf_drip.wav       "Water drops dripping.ogg" — Commons free licence
    //  - bf_rain_roof.wav  "Rain on a veranda and t.ogg" — Public Domain
    //  - bf_river.wav      "433589 jackthemurray stream-river-water-up-close.wav" — CC0
    //  - bf_frogs.wav      "Frogs croak calling chorus at night.ogg" — CC BY-SA 4.0
    //  - bf_owl.wav        "Maghreb owl hooting.wav" — CC BY-SA 4.0
    //  - bf_wolf.wav       "Wolf howls.ogg" — Public Domain
    //  - bf_bells.wav      "Cathedral Fribourg bells ringing 01.ogg" — CC BY-SA 4.0
    //  - bf_singing_bowl.wav "Tibetan Singing Bowl hit 11inch.flac" — CC BY-SA 4.0
    //  - bf_choir.wav      "Rorate Caeli ~ Gregorian Chant.ogg" — CC BY-SA 4.0
    //  - bf_cicadas.wav    "Cicada orni (Singing).ogg" — CC BY-SA 2.5
    //  - bf_loon.wav       "Common loon yodels.ogg" — CC BY-SA 2.5
    //  - bf_dawn_chorus.wav "Dawn Chorus 2020-05-06 0500.mp3" — CC BY-SA 4.0
    //  - bf_fire.wav       "Bones breaking wood fire ice crackling.ogg" — Public Domain
    @Volatile private var samplesByKind: Map<LayerKind, FloatArray?> = emptyMap()

    // One-shot field recordings for the HR instruments (mono 22.05 kHz PCM16
    // WAV in res/raw). When present, a strike plays the real recording,
    // resampled so its pitch tracks the live HR exactly like the synth path;
    // the additive-synthesis recipes remain as fallback.
    //
    // Sources (Wikimedia Commons):
    //  - bfhr_gong.wav       "Gong55.ogg" — CC0
    //  - bfhr_heartbeat.wav  "Emily's heartbeat.wav" — CC BY-SA 4.0
    //  - bfhr_vibraphone.wav "F scale on vibraphone.oga" (first note) — CC BY-SA 4.0
    //  - bfhr_triangle.wav   "LatinTriangle.ogg" — Public Domain
    //  - bfhr_piano.wav      "68448 pinkyfinger Piano G.ogg" — CC BY 2.5
    //  - bfhr_kalimba.wav    "Kalimba de coco (notas sueltas) 01.wav" — CC BY-SA 4.0
    //  - bfhr_woodblock.wav  "Blok music.ogg" — CC BY-SA 4.0
    //  - bfhr_tom.wav        "Tom drum 8 inch.ogg" — CC BY-SA 3.0
    //  - bfhr_xylophone.wav  "Xylophone jingle.wav" (first note) — CC BY 3.0
    //  - bfhr_tubular.wav    "Röhrenglocken (Windspiel).ogg" — CC BY-SA 3.0 DE
    //  - BELL reuses the Tibetan singing bowl recording (bf_singing_bowl.wav).
    @Volatile private var hrSamples: Map<BiofeedbackHrSound, FloatArray?> = emptyMap()
    private var samplesLoaded = false
    /** Live soundscape renderer — lazily rebuilt after samples load (immutable once created). */
    @Volatile private var soundscapeRenderer: SoundscapeRenderer? = null

    private fun renderer(): SoundscapeRenderer =
        soundscapeRenderer ?: synchronized(this) {
            soundscapeRenderer ?: SoundscapeRenderer(samplesByKind, SAMPLE_RATE).also {
                soundscapeRenderer = it
            }
        }

    /** Loads the bundled field recordings. Idempotent; call once at app/session start. */
    fun loadSamples(context: Context) {
        if (samplesLoaded) return
        synchronized(this) {
            if (samplesLoaded) return
            samplesByKind = mapOf(
                LayerKind.OCEAN_BED to loadWav(context, R.raw.bf_ocean),
                LayerKind.WIND_BED to loadWav(context, R.raw.bf_wind),
                LayerKind.RAIN_BED to loadWav(context, R.raw.bf_rain),
                LayerKind.STREAM_BED to loadWav(context, R.raw.bf_stream),
                LayerKind.WIND_GUST to loadWav(context, R.raw.bf_wind_gust),
                LayerKind.RAIN_HEAVY to loadWav(context, R.raw.bf_rain_heavy),
                LayerKind.RAINSTORM to loadWav(context, R.raw.bf_rainstorm),
                LayerKind.THUNDER to loadWav(context, R.raw.bf_thunder),
                LayerKind.BIRDS to loadWav(context, R.raw.bf_birds),
                LayerKind.GULLS to loadWav(context, R.raw.bf_gulls),
                LayerKind.CRICKETS to loadWav(context, R.raw.bf_crickets),
                LayerKind.WHALE to loadWav(context, R.raw.bf_whale),
                LayerKind.CHILDREN to loadWav(context, R.raw.bf_children),
                LayerKind.RAIN_LIGHT to loadWav(context, R.raw.bf_rain_light),
                LayerKind.DRIP to loadWav(context, R.raw.bf_drip),
                LayerKind.RAIN_ROOF to loadWav(context, R.raw.bf_rain_roof),
                LayerKind.RIVER to loadWav(context, R.raw.bf_river),
                LayerKind.FROGS to loadWav(context, R.raw.bf_frogs),
                LayerKind.OWL to loadWav(context, R.raw.bf_owl),
                LayerKind.WOLF to loadWav(context, R.raw.bf_wolf),
                LayerKind.BELLS to loadWav(context, R.raw.bf_bells),
                LayerKind.SINGING_BOWL to loadWav(context, R.raw.bf_singing_bowl),
                LayerKind.CHOIR_VOICES to loadWav(context, R.raw.bf_choir),
                LayerKind.CICADAS to loadWav(context, R.raw.bf_cicadas),
                LayerKind.LOON to loadWav(context, R.raw.bf_loon),
                LayerKind.DAWN_CHORUS to loadWav(context, R.raw.bf_dawn_chorus),
                LayerKind.FIRE to loadWav(context, R.raw.bf_fire)
            )
            // Only the recordings that survived tuning are kept — each is
            // peak-normalised so quiet recordings (e.g. the heartbeat) sit
            // at a consistent audible level. MARIMBA / KALIMBA / WOODBLOCK /
            // XYLOPHONE / TUBULAR fall through to their (reworked) synth
            // recipes: the recordings were too harsh, too dense, rushed or
            // inaudible.
            hrSamples = mapOf(
                BiofeedbackHrSound.GONG to normalize(loadWav(context, R.raw.bfhr_gong)),
                BiofeedbackHrSound.BELL to normalize(loadWav(context, R.raw.bf_singing_bowl)),
                BiofeedbackHrSound.HEARTBEAT to normalize(loadWav(context, R.raw.bfhr_heartbeat)),
                BiofeedbackHrSound.CHIME to normalize(loadWav(context, R.raw.bfhr_triangle)),
                BiofeedbackHrSound.PIANO to normalize(loadWav(context, R.raw.bfhr_piano)),
                BiofeedbackHrSound.TOM to normalize(loadWav(context, R.raw.bfhr_tom))
            )
            soundscapeRenderer = null // rebuild with the loaded samples
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

    /** Peak-normalises a sample buffer to 0.95 so quiet recordings play loud enough. */
    private fun normalize(s: FloatArray?): FloatArray? {
        if (s == null || s.isEmpty()) return s
        var peak = 0f
        for (v in s) if (v > peak) peak = v else if (-v > peak) peak = -v
        if (peak < 1e-4f) return s
        val scale = 0.95f / peak
        for (i in s.indices) s[i] *= scale
        return s
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
        lastStrikeSample = Long.MIN_VALUE / 2

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

    /** Set the HR (heartbeat instrument) layer volume, 0..1. Thread-safe. */
    fun setHrVolume(volume: Float) { hrVolume = volume.coerceIn(0f, 1f) }

    /** Set the SpO2 (soundscape) layer volume, 0..1. Thread-safe. */
    fun setSpo2Volume(volume: Float) { spo2Volume = volume.coerceIn(0f, 1f) }

    // ── Picker previews ─────────────────────────────────────────────────────────

    private val previewScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var previewJob: Job? = null
    private var previewTrack: AudioTrack? = null

    /**
     * Plays a demo of the given heartbeat instrument: a simulated HR sweep
     * (55 → 110 bpm solo, 55 → 85 bpm when mixed) so the user hears both the
     * tempo AND the pitch rise of that instrument. When [withTexture] is not
     * NONE, the currently-selected SpO2 soundscape is rendered underneath at
     * [spo2Vol] so the two layers can be auditioned together at their
     * relative volumes. Replaces any preview in flight.
     */
    fun previewHrSound(
        sound: BiofeedbackHrSound,
        withTexture: BiofeedbackSpo2Texture = BiofeedbackSpo2Texture.NONE,
        spo2Vol: Float = 1f,
        hrVol: Float = 1f
    ) {
        stopPreview()
        val combined = withTexture != BiofeedbackSpo2Texture.NONE
        if (sound == BiofeedbackHrSound.NONE && !combined) return // silence is its own preview
        val durSamples = SAMPLE_RATE * (if (combined) 8 else 3)
        val out = FloatArray(durSamples + TAIL_SAMPLES)
        if (sound != BiofeedbackHrSound.NONE) {
            renderPreviewStrikes(out, sound, durSamples, hrVol,
                fromBpm = 55.0, toBpm = if (combined) 85.0 else 110.0)
        }
        if (combined) renderPreviewTexture(out, withTexture, durSamples, spo2Vol, fromSpo2 = 98.0, toSpo2 = 98.0)
        // Soft-limit the preview mix.
        for (i in 0 until durSamples) out[i] = out[i].coerceIn(-MASTER_LIMIT, MASTER_LIMIT)
        playPreview(out, durSamples)
    }

    /**
     * Plays a demo of the given SpO2 soundscape rendered only at the highest
     * SpO2 scene (98 %) — the first sound the user will hear when a hold
     * starts. When [withSound] is not NONE, a gentle heartbeat instrument
     * sweep is layered on top at [hrVol] so the combination can be auditioned
     * at the relative volumes. Replaces any preview in flight.
     */
    fun previewSpo2Texture(
        tex: BiofeedbackSpo2Texture,
        withSound: BiofeedbackHrSound = BiofeedbackHrSound.NONE,
        spo2Vol: Float = 1f,
        hrVol: Float = 1f
    ) {
        stopPreview()
        val combined = withSound != BiofeedbackHrSound.NONE
        if (tex == BiofeedbackSpo2Texture.NONE && !combined) return
        val durSamples = SAMPLE_RATE * (if (combined) 8 else 14)
        val out = FloatArray(durSamples + TAIL_SAMPLES)
        if (tex != BiofeedbackSpo2Texture.NONE) {
            renderPreviewTexture(out, tex, durSamples, spo2Vol,
                fromSpo2 = 98.0, toSpo2 = 98.0)
        }
        if (combined) renderPreviewStrikes(out, withSound, durSamples, hrVol, fromBpm = 55.0, toBpm = 85.0)
        // Soft-limit the preview mix.
        for (i in 0 until durSamples) out[i] = out[i].coerceIn(-MASTER_LIMIT, MASTER_LIMIT)
        playPreview(out, durSamples)
    }

    /** Renders a swept-bpm series of instrument strikes into [out] (preview only). */
    private fun renderPreviewStrikes(
        out: FloatArray,
        sound: BiofeedbackHrSound,
        durSamples: Int,
        gain: Float,
        fromBpm: Double,
        toBpm: Double
    ) {
        val recipe0 = recipeFor(sound)
        val minGapSamples = recipe0.minRestrikeMs * SAMPLE_RATE / 1000
        var nextBeat = 0L
        var lastStrike = Long.MIN_VALUE / 2
        while (nextBeat < durSamples) {
            val frac = nextBeat.toDouble() / durSamples
            val bpm = fromBpm + (toBpm - fromBpm) * frac
            if (recipe0.minRestrikeMs <= 0 || nextBeat - lastStrike >= minGapSamples) {
                val pitch = 2.0.pow((bpm - DEFAULT_HR_BPM) / HR_PITCH_OCTAVE_BPM)
                val recipe = recipe0.copy(baseFreqHz = recipe0.baseFreqHz * pitch)
                strike(out, nextBeat.toInt(), recipe, out.size, sound = sound, rate = pitch.toFloat(), gain = gain)
                lastStrike = nextBeat
            }
            nextBeat += (SAMPLE_RATE * 60.0 / bpm).toLong()
        }
    }

    /** Renders a swept-SpO2 soundscape additively into [out] (preview only). */
    private fun renderPreviewTexture(
        out: FloatArray,
        tex: BiofeedbackSpo2Texture,
        durSamples: Int,
        vol: Float,
        fromSpo2: Double,
        toSpo2: Double
    ) {
        val previewRenderer = SoundscapeRenderer(samplesByKind, SAMPLE_RATE)
        val chunk = FloatArray(CHUNK_SAMPLES)
        var written = 0
        while (written < durSamples) {
            val frac = written.toDouble() / durSamples
            val spo2 = fromSpo2 + (toSpo2 - fromSpo2) * frac
            previewRenderer.renderChunk(chunk, tex, spo2.toFloat())
            val n = minOf(CHUNK_SAMPLES, durSamples - written)
            if (vol == 1f) {
                for (i in 0 until n) out[written + i] += chunk[i]
            } else {
                for (i in 0 until n) out[written + i] += chunk[i] * vol
            }
            written += n
        }
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

    private fun renderChunk(chunk: FloatArray) {
        // Smooth the live feeds — slow drift, never jarring.
        smoothedHr += (targetHr - smoothedHr) * HR_SMOOTHING
        smoothedSpo2T += (targetSpo2T - smoothedSpo2T) * SPO2_SMOOTHING

        // 1) Background texture layer (layered soundscape — no SpO2 pitch bend)
        renderTexture(chunk)

        // 2) Schedule heartbeat strikes on the sample clock (jitter-free tempo).
        //    Pitch scales with the live HR so rising HR = rising pitch.
        if (hrSound == BiofeedbackHrSound.NONE) return
        val chunkEnd = totalSamples + CHUNK_SAMPLES
        val recipe = recipeFor(hrSound)
        val hrPitch = 2.0.pow((smoothedHr.toDouble() - DEFAULT_HR_BPM) / HR_PITCH_OCTAVE_BPM)
        val pitched = recipe.copy(baseFreqHz = recipe.baseFreqHz * hrPitch)
        val minGapSamples = recipe.minRestrikeMs * SAMPLE_RATE / 1000
        while (nextBeatSample < chunkEnd) {
            val offsetInChunk = (nextBeatSample - totalSamples).toInt()
            if (recipe.minRestrikeMs <= 0 || nextBeatSample - lastStrikeSample >= minGapSamples) {
                strike(beatTail, offsetInChunk, pitched, sound = hrSound, rate = hrPitch.toFloat(), gain = hrVolume)
                lastStrikeSample = nextBeatSample
            }
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

    /**
     * Adds one instrument strike into [tail] starting [offset] samples in.
     * If a real one-shot recording is loaded for [sound], the recording is
     * played back (resampled by [rate] so pitch tracks HR); otherwise the
     * additive-synthesis recipe renders the strike.
     */
    private fun strike(tail: FloatArray, offset: Int, recipe: StrikeRecipe, limit: Int = TAIL_SAMPLES, sound: BiofeedbackHrSound? = null, rate: Float = 1f, gain: Float = 1f) {
        val sample = sound?.let { hrSamples[it] }
        if (sample != null && sample.size > 4) {
            // Clamp the resample rate so pitch-tracking can never turn a
            // recording shrill/aggressive at high HR.
            val clampedRate = rate.coerceIn(0.7f, 1.5f)
            strikeSampled(tail, offset, sample, recipe.amplitude * 1.35f * gain, clampedRate, limit)
            return
        }
        strikeOnce(tail, offset, recipe, recipe.amplitude * gain, limit)
        if (recipe.secondThumpOffsetMs > 0) {
            strikeOnce(
                tail,
                offset + recipe.secondThumpOffsetMs * SAMPLE_RATE / 1000,
                recipe,
                recipe.amplitude * 0.7f * gain,
                limit
            )
        }
    }

    /** Plays a real one-shot recording into [tail] at playback [rate] (1 = original pitch). */
    private fun strikeSampled(tail: FloatArray, offset: Int, sample: FloatArray, amplitude: Float, rate: Float, limit: Int) {
        val srcRate = 22050 // bundled WAVs are 22.05 kHz
        val step = (srcRate.toDouble() / SAMPLE_RATE) * rate
        var pos = 0.0
        var i = 0
        val n = sample.size - 1
        while (i + offset < limit && pos < n) {
            val i0 = pos.toInt()
            val frac = (pos - i0).toFloat()
            tail[offset + i] += (sample[i0] + (sample[i0 + 1] - sample[i0]) * frac) * amplitude
            pos += step
            i++
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
     * Renders the SpO2-mapped background soundscape for this chunk. The
     * smoothed SpO2 drives which layers of the soundscape are audible —
     * layers cross-fade in/out as their bands are entered and left (see
     * [BiofeedbackSoundscapes]). No pitch modulation from SpO2.
     */
    private fun renderTexture(chunk: FloatArray) {
        if (texture == BiofeedbackSpo2Texture.NONE) { chunk.fill(0f); return }
        val spo2 = SPO2_FLOOR + smoothedSpo2T * (SPO2_CEIL - SPO2_FLOOR)
        renderer().renderChunk(chunk, texture, spo2)
        if (spo2Volume != 1f) {
            for (i in chunk.indices) chunk[i] *= spo2Volume
        }
    }
}
