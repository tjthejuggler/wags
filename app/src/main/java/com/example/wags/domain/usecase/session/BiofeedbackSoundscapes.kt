package com.example.wags.domain.usecase.session

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * One layer of an SpO2 "soundscape": a sound source that is audible while the
 * live SpO2 sits inside the band `(toSpo2, fromSpo2]`. Layers enter and leave
 * the mix at their band edges (with a short cross-fade handled by the
 * renderer), so as SpO2 falls the soundscape tells a little story — sounds
 * are layered on, and sometimes taken away again, exactly like scenes of a
 * journey from a bright summer day down into the deep.
 */
data class SoundscapeLayer(
    val kind: LayerKind,
    /** Highest SpO2 at which this layer is active (inclusive). */
    val fromSpo2: Int,
    /** Lowest SpO2 at which this layer is still active (exclusive bound). */
    val toSpo2: Int,
    /** Mix gain when fully faded in (0..1). */
    val gain: Float
) {
    fun isActive(spo2: Float): Boolean = spo2 <= fromSpo2 && spo2 > toSpo2
}

/**
 * Sound sources available to soundscapes. The four *_BED kinds are the
 * bundled field recordings loaded by [BiofeedbackSonificationEngine.loadSamples];
 * everything else is synthesised procedurally in [SoundscapeRenderer].
 */
enum class LayerKind {
    OCEAN_BED, WIND_BED, RAIN_BED, STREAM_BED,
    /** Rain drumming on a veranda/tin roof (recording). */
    RAIN_ROOF,
    /** Rushing river water, close-up (recording). */
    RIVER,
    /** Night frog chorus (recording). */
    FROGS,
    /** A single owl hooting (recording). */
    OWL,
    /** Lone wolf howl (recording). */
    WOLF,
    /** Cathedral church bells (recording). */
    BELLS,
    /** Struck Tibetan singing bowl (recording). */
    SINGING_BOWL,
    /** Gregorian-style choir voices (recording). */
    CHOIR_VOICES,
    /** Singing cicadas (recording). */
    CICADAS,
    /** Common loon yodel calls (recording). */
    LOON,
    /** Full dawn bird chorus (recording). */
    DAWN_CHORUS,
    /** Crackling fire (recording). */
    FIRE,
    /** Soft steady synthesized rain (light shower). */
    RAIN_LIGHT,
    /** Denser, busier rain (recording, synthesized fallback). */
    RAIN_HEAVY,
    /** Full storm: driving rain with wind (recording). */
    RAINSTORM,
    /** Gustier, moaning wind — noise with a slowly sweeping low-pass. */
    WIND_GUST,
    /** Random low rumble events with a sub-thump. */
    THUNDER,
    /** Random chirping songbirds (FM sine sweeps). */
    BIRDS,
    /** Playful pentatonic plinks + giggle-like descending chirps. */
    CHILDREN,
    /** Occasional descending gull cries. */
    GULLS,
    /** Evening cricket stridulation (fast AM on a high carrier). */
    CRICKETS,
    /** Slow gliding whale-song-like tones. */
    WHALE,
    /** Deep 40 Hz heartbeat-like sub throb. */
    SUB_PULSE,
    /** Bright airy pad chord (soprano register). */
    PAD_HIGH,
    /** Warm mid-register pad chord. */
    PAD_MID,
    /** Low rooted pad drone. */
    PAD_LOW,
    /** Sparse glittering high pings. */
    SPARKLE,
    /** Single water drips with a downward pitch bend. */
    DRIP,
    /** Two near-unison low sines that beat against each other (unease). */
    BEAT_DISSONANT,
    /** Slow in/out breathing noise (whisper of wind). */
    BREATH
}

/**
 * Renders one [BiofeedbackSpo2Texture] as a layered soundscape (see
 * [LayerKind]). There is **no pitch modulation driven by SpO2** — the story
 * is told purely by which layers are present and how loud they are. Each
 * layer fades in/out over roughly half a second so band transitions feel
 * like scenes gently changing rather than hard cuts.
 *
 * The renderer is single-threaded (owned by the audio render coroutine, or a
 * preview render pass) and keeps per-layer filter/phase/event state in
 * [LayerState] so layers resume seamlessly after being silent.
 */
class SoundscapeRenderer(
    private val samples: Map<LayerKind, FloatArray?>,
    private val sampleRate: Int = 44100
) {

    companion object {

        /**
         * The soundscape "story" for every texture. Each entry is a layer
         * with the SpO2 band in which it is audible. All bands stop at 40 %
         * (the engine's mapping floor).
         */
        val soundscapes: Map<BiofeedbackSpo2Texture, List<SoundscapeLayer>> = mapOf(

            // ── RAIN — the user's example story, fully built out ─────────────
            //  100-96  rain begins softly on the veranda roof
            //   97-93  open-air light rain joins the drumming
            //   93-89  the shower grows heavier
            //   89-85  a full storm: driving rain with wind
            //   85-80  thunder rolls overhead
            //   80-72  the storm passes: rain & thunder leave, wind remains
            //   72-64  wet night — frogs sing in the puddles
            //   64-58  deeper night — crickets take over
            //   58-50  first light — the dawn chorus erupts
            //   50-40  morning — laughing children come out to play
            BiofeedbackSpo2Texture.RAIN to listOf(
                SoundscapeLayer(LayerKind.RAIN_ROOF, 100, 96, 0.16f),
                SoundscapeLayer(LayerKind.RAIN_LIGHT, 97, 93, 0.16f),
                SoundscapeLayer(LayerKind.RAIN_HEAVY, 93, 89, 0.16f),
                SoundscapeLayer(LayerKind.RAINSTORM, 89, 85, 0.16f),
                SoundscapeLayer(LayerKind.THUNDER, 85, 80, 0.20f),
                SoundscapeLayer(LayerKind.WIND_BED, 80, 72, 0.17f),
                SoundscapeLayer(LayerKind.FROGS, 72, 64, 0.16f),
                SoundscapeLayer(LayerKind.CRICKETS, 64, 58, 0.13f),
                SoundscapeLayer(LayerKind.DAWN_CHORUS, 58, 50, 0.15f),
                SoundscapeLayer(LayerKind.CHILDREN, 50, 40, 0.18f)
            ),

            // ── OCEAN — from a sunny lake shore into the abyss ──────────────
            //  100-90  calm waves; gulls wheel overhead
            //   90-84  a loon calls across the water
            //   85-78  heavier surf swells and gusts
            //   78-62  we slip beneath: whale song rises from the deep
            //   65-40  deep sub pulse; 55-40 sparse sonar-like drips
            BiofeedbackSpo2Texture.OCEAN to listOf(
                SoundscapeLayer(LayerKind.OCEAN_BED, 100, 40, 0.15f),
                SoundscapeLayer(LayerKind.GULLS, 100, 90, 0.13f),
                SoundscapeLayer(LayerKind.LOON, 90, 84, 0.15f),
                SoundscapeLayer(LayerKind.WIND_GUST, 85, 78, 0.13f),
                SoundscapeLayer(LayerKind.WHALE, 78, 62, 0.16f),
                SoundscapeLayer(LayerKind.SUB_PULSE, 65, 40, 0.18f),
                SoundscapeLayer(LayerKind.DRIP, 55, 40, 0.15f)
            ),

            // ── WIND — dusk on the prairie into a wild, deepening night ─────
            //  100-92  gentle evening breeze; 96-88 cicadas shrill
            //   88-80  an owl calls;  82-72 gusts build
            //   74-66  thunder far away; 66-58 a wolf howls at the moon
            //   60-52  crickets;  50-40 everything stills to a low drone
            BiofeedbackSpo2Texture.WIND to listOf(
                SoundscapeLayer(LayerKind.WIND_BED, 100, 92, 0.14f),
                SoundscapeLayer(LayerKind.CICADAS, 96, 88, 0.11f),
                SoundscapeLayer(LayerKind.OWL, 88, 80, 0.14f),
                SoundscapeLayer(LayerKind.WIND_GUST, 82, 72, 0.16f),
                SoundscapeLayer(LayerKind.THUNDER, 74, 66, 0.14f),
                SoundscapeLayer(LayerKind.WOLF, 66, 58, 0.15f),
                SoundscapeLayer(LayerKind.CRICKETS, 60, 52, 0.12f),
                SoundscapeLayer(LayerKind.PAD_LOW, 50, 40, 0.14f)
            ),

            // ── STREAM — the brook swells, sinks underground, goes deep ─────
            //  100-88  burbling brook with birds
            //   88-78  the brook swells into a rushing river
            //   80-72  the flow thins to drips in a cave
            //   72-62  a deep current glides below
            //   64-56  a loon calls, far away and muffled
            //   62-50  sub throb;  55-40 glittering particles drift past
            BiofeedbackSpo2Texture.STREAM to listOf(
                SoundscapeLayer(LayerKind.STREAM_BED, 100, 88, 0.16f),
                SoundscapeLayer(LayerKind.BIRDS, 96, 88, 0.12f),
                SoundscapeLayer(LayerKind.RIVER, 88, 78, 0.16f),
                SoundscapeLayer(LayerKind.DRIP, 80, 72, 0.15f),
                SoundscapeLayer(LayerKind.WHALE, 72, 62, 0.13f),
                SoundscapeLayer(LayerKind.LOON, 64, 56, 0.12f),
                SoundscapeLayer(LayerKind.SUB_PULSE, 62, 50, 0.16f),
                SoundscapeLayer(LayerKind.SPARKLE, 55, 40, 0.13f)
            ),

            // ── WARM PAD — a temple evening: the instruments leave one by one
            //  100-88  full bright triad with sparkle
            //   95-80  a singing bowl rings out and slowly decays
            //  100-75  mid voice;  92-45 low root remains long
            //   75-62  human choir voices hum beneath
            //   70-58  temple bells; 62-52 an uneasy beating interval creeps in
            //   55-40  only a deep sub pulse is left
            BiofeedbackSpo2Texture.WARM_PAD to listOf(
                SoundscapeLayer(LayerKind.PAD_HIGH, 100, 88, 0.13f),
                SoundscapeLayer(LayerKind.SPARKLE, 98, 88, 0.10f),
                SoundscapeLayer(LayerKind.SINGING_BOWL, 95, 80, 0.14f),
                SoundscapeLayer(LayerKind.PAD_MID, 100, 75, 0.14f),
                SoundscapeLayer(LayerKind.PAD_LOW, 92, 45, 0.16f),
                SoundscapeLayer(LayerKind.CHOIR_VOICES, 75, 62, 0.13f),
                SoundscapeLayer(LayerKind.BELLS, 70, 58, 0.12f),
                SoundscapeLayer(LayerKind.BEAT_DISSONANT, 62, 52, 0.13f),
                SoundscapeLayer(LayerKind.SUB_PULSE, 55, 40, 0.17f)
            ),

            // ── DEEP DRONE — a firelit cave far underground ─────────────────
            //  100-40  low root drone throughout
            //   92-78  a fire crackles in the dark
            //   80-66  slow breath;  75-58 octave sub throb
            //   68-55  a singing bowl resonates from the stone
            //   60-48  near-unison beating (tension)
            //   52-44  a wolf howls somewhere above
            BiofeedbackSpo2Texture.DEEP_DRONE to listOf(
                SoundscapeLayer(LayerKind.PAD_LOW, 100, 40, 0.17f),
                SoundscapeLayer(LayerKind.FIRE, 92, 78, 0.14f),
                SoundscapeLayer(LayerKind.BREATH, 80, 66, 0.12f),
                SoundscapeLayer(LayerKind.SUB_PULSE, 75, 58, 0.16f),
                SoundscapeLayer(LayerKind.SINGING_BOWL, 68, 55, 0.12f),
                SoundscapeLayer(LayerKind.BEAT_DISSONANT, 60, 48, 0.14f),
                SoundscapeLayer(LayerKind.WOLF, 52, 44, 0.12f)
            ),

            // ── CHOIR — a cathedral service that empties into the crypt ─────
            //  100-85  full choir of real voices over soprano pad
            //   92-80  the great bells swing above the singing
            //  100-70  mid voice;  95-55 low voice lingers longest
            //   80-65  a singing bowl;  70-55 breath replaces the singing
            //   60-45  one lone gliding whale-voice in the crypt
            //   55-40  deep sub pulse
            BiofeedbackSpo2Texture.CHOIR to listOf(
                SoundscapeLayer(LayerKind.CHOIR_VOICES, 100, 85, 0.13f),
                SoundscapeLayer(LayerKind.PAD_HIGH, 100, 85, 0.11f),
                SoundscapeLayer(LayerKind.BELLS, 92, 80, 0.12f),
                SoundscapeLayer(LayerKind.PAD_MID, 100, 70, 0.14f),
                SoundscapeLayer(LayerKind.SINGING_BOWL, 80, 65, 0.12f),
                SoundscapeLayer(LayerKind.PAD_LOW, 95, 55, 0.15f),
                SoundscapeLayer(LayerKind.BREATH, 70, 55, 0.12f),
                SoundscapeLayer(LayerKind.WHALE, 60, 45, 0.13f),
                SoundscapeLayer(LayerKind.SUB_PULSE, 55, 40, 0.16f)
            )
        )

        /** Cross-fade speed — fraction of the way to the target gain per chunk. */
        private const val GAIN_SMOOTHING = 0.05f

        /** Below this smoothed gain a layer is skipped entirely (CPU save). */
        private const val GAIN_EPSILON = 0.0015f
    }

    /** Per-layer persistent state (filters, phases, event scheduling). */
    private class LayerState {
        var gain = 0f
        // WAV playback
        var pos = 0.0
        var lp = 0f
        // Procedural voices
        var phase = 0.0
        var phase2 = 0.0
        var lp1 = 0f
        var lp2 = 0f
        // Event scheduling (sample-clock based)
        var nextEventAt = -1L
        var eventStart = 0L
        var eventEnd = 0L
        var evFreq = 0.0
        var evFreq2 = 0.0
        /** Per-voice phases for multi-voice pads (allocated lazily by renderPad). */
        var padPhases: DoubleArray = DoubleArray(0)
    }

    private val states = HashMap<LayerKind, LayerState>()
    private var totalSamples = 0L
    private var lfoPhase = 0.0
    private var rngState = 987654321L

    private fun rnd(): Double {
        rngState = rngState * 6364136223846793005L + 1442695040888963407L
        return ((rngState ushr 11) and 0xFFFFFFFFL).toDouble() / 0xFFFFFFFFL
    }

    private fun state(kind: LayerKind): LayerState = states.getOrPut(kind) { LayerState() }

    /**
     * Renders one chunk of the soundscape for [texture] at the current SpO2.
     * [chunk] is filled (not mixed) with the layered result.
     */
    fun renderChunk(chunk: FloatArray, texture: BiofeedbackSpo2Texture, spo2: Float) {
        chunk.fill(0f)
        val layers = soundscapes[texture] ?: return

        // Gentle master swell (breathing-speed) so the bed never feels static.
        val chunkSec = chunk.size.toDouble() / sampleRate
        lfoPhase = (lfoPhase + 2.0 * PI * 0.09 * chunkSec) % (2.0 * PI)
        val swell = (0.85 + 0.15 * sin(lfoPhase)).toFloat()

        for (layer in layers) {
            val st = state(layer.kind)
            val target = if (layer.isActive(spo2)) layer.gain else 0f
            st.gain += (target - st.gain) * GAIN_SMOOTHING
            if (st.gain < GAIN_EPSILON) continue
            renderLayer(chunk, layer.kind, st, st.gain * swell)
        }
        totalSamples += chunk.size
    }

    // ── Layer renderers — each ADDS its voice into [chunk] ──────────────────

    private fun renderLayer(chunk: FloatArray, kind: LayerKind, st: LayerState, gain: Float) {
        // Layer kinds with a real field-recording asset play the recording;
        // the procedural renderer below is only a fallback when the asset is
        // missing (null in [samples]).
        fun bedOr(lpCoef: Float, fallback: () -> Unit) {
            if (samples[kind] != null) renderBed(chunk, kind, st, gain, lpCoef) else fallback()
        }
        when (kind) {
            LayerKind.OCEAN_BED -> renderBed(chunk, LayerKind.OCEAN_BED, st, gain, lpCoef = 0.20f)
            LayerKind.RAIN_ROOF -> bedOr(lpCoef = 0.35f) { renderRain(chunk, st, gain, heavy = false) }
            LayerKind.RIVER -> bedOr(lpCoef = 0.35f) { renderRain(chunk, st, gain, heavy = true) }
            LayerKind.FROGS -> bedOr(lpCoef = 0.45f) { renderCrickets(chunk, st, gain) }
            LayerKind.OWL -> bedOr(lpCoef = 0.55f) { renderBirds(chunk, st, gain) }
            LayerKind.WOLF -> bedOr(lpCoef = 0.60f) { renderWhale(chunk, st, gain) }
            LayerKind.BELLS -> bedOr(lpCoef = 0.55f) { renderSparkle(chunk, st, gain) }
            LayerKind.SINGING_BOWL -> bedOr(lpCoef = 0.55f) { renderPad(chunk, st, gain, freqs = doubleArrayOf(196.0, 293.66, 392.0), brightness = 0.7) }
            LayerKind.CHOIR_VOICES -> bedOr(lpCoef = 0.60f) { renderPad(chunk, st, gain, freqs = doubleArrayOf(220.0, 277.18, 329.63), brightness = 0.8) }
            LayerKind.CICADAS -> bedOr(lpCoef = 0.50f) { renderCrickets(chunk, st, gain) }
            LayerKind.LOON -> bedOr(lpCoef = 0.60f) { renderGulls(chunk, st, gain) }
            LayerKind.DAWN_CHORUS -> bedOr(lpCoef = 0.60f) { renderBirds(chunk, st, gain) }
            LayerKind.FIRE -> bedOr(lpCoef = 0.45f) { renderRain(chunk, st, gain, heavy = false) }
            LayerKind.WIND_BED -> renderBed(chunk, LayerKind.WIND_BED, st, gain, lpCoef = 0.10f)
            LayerKind.RAIN_BED -> renderBed(chunk, LayerKind.RAIN_BED, st, gain, lpCoef = 0.30f)
            LayerKind.STREAM_BED -> renderBed(chunk, LayerKind.STREAM_BED, st, gain, lpCoef = 0.35f)
            LayerKind.RAIN_LIGHT -> bedOr(lpCoef = 0.30f) { renderRain(chunk, st, gain, heavy = false) }
            LayerKind.RAIN_HEAVY -> bedOr(lpCoef = 0.40f) { renderRain(chunk, st, gain, heavy = true) }
            LayerKind.RAINSTORM -> bedOr(lpCoef = 0.40f) { renderRain(chunk, st, gain, heavy = true) }
            LayerKind.WIND_GUST -> bedOr(lpCoef = 0.18f) { renderWindGust(chunk, st, gain) }
            LayerKind.THUNDER -> bedOr(lpCoef = 0.30f) { renderThunder(chunk, st, gain) }
            LayerKind.BIRDS -> bedOr(lpCoef = 0.60f) { renderBirds(chunk, st, gain) }
            LayerKind.CHILDREN -> bedOr(lpCoef = 0.60f) { renderChildren(chunk, st, gain) }
            LayerKind.GULLS -> bedOr(lpCoef = 0.60f) { renderGulls(chunk, st, gain) }
            LayerKind.CRICKETS -> bedOr(lpCoef = 0.50f) { renderCrickets(chunk, st, gain) }
            LayerKind.WHALE -> bedOr(lpCoef = 0.60f) { renderWhale(chunk, st, gain) }
            LayerKind.SUB_PULSE -> renderSubPulse(chunk, st, gain)
            LayerKind.PAD_HIGH -> renderPad(chunk, st, gain, freqs = doubleArrayOf(329.63, 415.30, 493.88), brightness = 1.0)
            LayerKind.PAD_MID -> renderPad(chunk, st, gain, freqs = doubleArrayOf(220.0, 277.18, 329.63), brightness = 0.8)
            LayerKind.PAD_LOW -> renderPad(chunk, st, gain, freqs = doubleArrayOf(110.0, 164.81, 220.0), brightness = 0.55)
            LayerKind.SPARKLE -> renderSparkle(chunk, st, gain)
            LayerKind.DRIP -> bedOr(lpCoef = 0.60f) { renderDrip(chunk, st, gain) }
            LayerKind.BEAT_DISSONANT -> renderBeating(chunk, st, gain)
            LayerKind.BREATH -> renderBreath(chunk, st, gain)
        }
    }

    /** Looping field-recording bed at fixed pitch/brightness — gain only. */
    private fun renderBed(chunk: FloatArray, kind: LayerKind, st: LayerState, gain: Float, lpCoef: Float) {
        val sample = samples[kind]
        if (sample == null || sample.size < 4) return
        val srcRate = 22050 // bundled WAVs are 22.05 kHz
        val step = srcRate.toDouble() / sampleRate
        val n = sample.size
        var pos = st.pos
        for (i in chunk.indices) {
            val i0 = pos.toInt()
            val frac = (pos - i0).toFloat()
            val s0 = sample[i0]
            val s1 = sample[(i0 + 1) % n]
            st.lp += (s0 + (s1 - s0) * frac - st.lp) * lpCoef
            chunk[i] += st.lp * gain
            pos += step
            if (pos >= n) pos -= n
        }
        st.pos = pos
    }

    /** Filtered white noise rainfall — `heavy` widens the spectrum and adds a slow density wobble. */
    private fun renderRain(chunk: FloatArray, st: LayerState, gain: Float, heavy: Boolean) {
        val baseCoef = if (heavy) 0.45f else 0.22f
        for (i in chunk.indices) {
            val t = (totalSamples + i).toDouble() / sampleRate
            val density = if (heavy) (0.8 + 0.2 * sin(2.0 * PI * 0.13 * t)).toFloat() else 1f
            val noise = (rnd() * 2.0 - 1.0).toFloat()
            st.lp1 += (noise - st.lp1) * baseCoef
            st.lp2 += (st.lp1 - st.lp2) * baseCoef
            chunk[i] += st.lp2 * gain * density
        }
    }

    /** Gusts: noise through a low-pass whose cutoff slowly sweeps up and down. */
    private fun renderWindGust(chunk: FloatArray, st: LayerState, gain: Float) {
        for (i in chunk.indices) {
            val t = (totalSamples + i).toDouble() / sampleRate
            val gust = 0.5 + 0.5 * sin(2.0 * PI * 0.07 * t + sin(2.0 * PI * 0.023 * t) * 2.0)
            val coef = (0.04 + 0.16 * gust).toFloat()
            val noise = (rnd() * 2.0 - 1.0).toFloat()
            st.lp1 += (noise - st.lp1) * coef
            chunk[i] += st.lp1 * gain * (0.55f + 0.45f * gust.toFloat())
        }
    }

    /**
     * Thunder: random rumble events (every 2-6 s) — heavily low-passed noise
     * with a slow bloom/decay envelope plus a 45 Hz sub thump at onset.
     */
    private fun renderThunder(chunk: FloatArray, st: LayerState, gain: Float) {
        val eventDur = 3.5 * sampleRate
        if (st.nextEventAt < 0) st.nextEventAt = totalSamples + (rnd() * sampleRate).toLong()
        for (i in chunk.indices) {
            val abs = totalSamples + i
            if (abs >= st.nextEventAt && st.eventEnd <= abs) {
                st.eventStart = abs
                st.eventEnd = abs + eventDur.toLong()
                st.nextEventAt = st.eventEnd + (rnd() * 4.0 * sampleRate).toLong()
            }
            if (abs in st.eventStart until st.eventEnd) {
                val t = (abs - st.eventStart).toDouble() / sampleRate
                val dur = eventDur / sampleRate.toDouble()
                // Bloom in ~0.3 s, decay over the rest
                val env = exp(-t / (dur * 0.5)) * (1.0 - exp(-t / 0.12))
                val noise = (rnd() * 2.0 - 1.0).toFloat()
                st.lp1 += (noise - st.lp1) * 0.012f
                st.lp2 += (st.lp1 - st.lp2) * 0.02f
                val rumble = st.lp2.toDouble() * env
                val thump = sin(2.0 * PI * 45.0 * t) * exp(-t / 0.35) * 0.8
                chunk[i] += (rumble + thump).toFloat() * gain
            }
        }
    }

    /**
     * Songbirds: every 0.6-2.5 s a short chirp phrase — a fast FM sweep that
     * rises then falls, 1-3 notes.
     */
    private fun renderBirds(chunk: FloatArray, st: LayerState, gain: Float) {
        if (st.nextEventAt < 0) st.nextEventAt = totalSamples + (rnd() * sampleRate).toLong()
        for (i in chunk.indices) {
            val abs = totalSamples + i
            if (abs >= st.nextEventAt && st.eventEnd <= abs) {
                st.eventStart = abs
                st.eventEnd = abs + ((0.1 + rnd() * 0.18) * sampleRate).toLong() // 0.1-0.28 s phrase
                st.evFreq = 2600.0 + rnd() * 1600.0   // base freq 2.6-4.2 kHz
                st.evFreq2 = 3 + (rnd() * 12.0)        // sweep speed
                st.nextEventAt = st.eventEnd + (rnd() * 2.0 * sampleRate).toLong()
            }
            if (abs in st.eventStart until st.eventEnd) {
                val t = (abs - st.eventStart).toDouble() / sampleRate
                val dur = (st.eventEnd - st.eventStart).toDouble() / sampleRate
                val env = sin(PI * t / dur).pow(0.7)
                val sweep = st.evFreq * (1.0 + 0.28 * sin(2.0 * PI * st.evFreq2 * t))
                st.phase += 2.0 * PI * sweep / sampleRate
                chunk[i] += (sin(st.phase) * env * 0.7).toFloat() * gain
            } else {
                st.phase = 0.0
            }
        }
    }

    /**
     * Children playing: sparse playful pentatonic plinks plus occasional
     * descending two-note "giggles".
     */
    private fun renderChildren(chunk: FloatArray, st: LayerState, gain: Float) {
        val pentatonic = doubleArrayOf(523.25, 587.33, 659.26, 783.99, 880.0, 1046.5)
        if (st.nextEventAt < 0) st.nextEventAt = totalSamples + (rnd() * sampleRate).toLong()
        for (i in chunk.indices) {
            val abs = totalSamples + i
            if (abs >= st.nextEventAt && st.eventEnd <= abs) {
                st.eventStart = abs
                st.eventEnd = abs + ((0.12 + rnd() * 0.10) * sampleRate).toLong()
                st.evFreq = if (rnd() < 0.75) {
                    pentatonic[(rnd() * pentatonic.size).toInt()]          // playful plink
                } else {
                    pentatonic[(rnd() * 3).toInt()] * 2.0                  // giggle start (higher)
                }
                st.evFreq2 = if (st.evFreq > 1000.0 && rnd() < 0.35) 0.55 else 1.0 // giggle: drop almost an octave
                st.nextEventAt = st.eventEnd + ((0.25 + rnd() * 0.8) * sampleRate).toLong()
            }
            if (abs in st.eventStart until st.eventEnd) {
                val t = (abs - st.eventStart).toDouble() / sampleRate
                val dur = (st.eventEnd - st.eventStart).toDouble() / sampleRate
                val env = exp(-t / (dur * 0.6)) * (1.0 - exp(-t / 0.004))
                val f = st.evFreq * st.evFreq2
                st.phase += 2.0 * PI * f / sampleRate
                // second soft partial gives it a wooden-toy character
                st.phase2 += 2.0 * PI * f * 2.76 / sampleRate
                val s = sin(st.phase) * 0.8 + sin(st.phase2) * 0.2
                chunk[i] += (s * env).toFloat() * gain
            }
        }
    }

    /** Gulls: rare long descending cries (1.3 kHz → 650 Hz over ~0.4 s). */
    private fun renderGulls(chunk: FloatArray, st: LayerState, gain: Float) {
        if (st.nextEventAt < 0) st.nextEventAt = totalSamples + (rnd() * 2 * sampleRate).toLong()
        for (i in chunk.indices) {
            val abs = totalSamples + i
            if (abs >= st.nextEventAt && st.eventEnd <= abs) {
                st.eventStart = abs
                st.eventEnd = abs + (0.35 * sampleRate).toLong()
                st.evFreq = 1200.0 + rnd() * 300.0
                st.nextEventAt = st.eventEnd + ((1.5 + rnd() * 3.5) * sampleRate).toLong()
            }
            if (abs in st.eventStart until st.eventEnd) {
                val frac = (abs - st.eventStart).toDouble() / (st.eventEnd - st.eventStart)
                val env = sin(PI * frac).pow(0.8)
                val f = st.evFreq * (1.0 - 0.5 * frac)
                st.phase += 2.0 * PI * f / sampleRate
                chunk[i] += (sin(st.phase) * env * 0.6).toFloat() * gain
            }
        }
    }

    /** Crickets: gated fast amplitude modulation on a ~4.3 kHz carrier. */
    private fun renderCrickets(chunk: FloatArray, st: LayerState, gain: Float) {
        for (i in chunk.indices) {
            val t = (totalSamples + i).toDouble() / sampleRate
            val burstGate = if ((t % 2.1) < 1.4) 1.0 else 0.0      // sing/rest cycle
            val am = 0.5 + 0.5 * sin(2.0 * PI * 27.0 * t)
            st.phase += 2.0 * PI * 4300.0 / sampleRate
            val carrier = sin(st.phase) * 0.5 + sin(st.phase * 1.002) * 0.5
            chunk[i] += (carrier * am * burstGate).toFloat() * gain * 0.5f
        }
    }

    /** Whale song: a tone gliding slowly between ~170 and 320 Hz with vibrato, 9 s phrase cycle. */
    private fun renderWhale(chunk: FloatArray, st: LayerState, gain: Float) {
        for (i in chunk.indices) {
            val t = (totalSamples + i).toDouble() / sampleRate
            val phrase = (t % 9.0) / 9.0
            val env = sin(PI * phrase).pow(1.5)
            val glide = 170.0 + 150.0 * (0.5 + 0.5 * sin(2.0 * PI * 0.09 * t + 1.2))
            val vibrato = 1.0 + 0.008 * sin(2.0 * PI * 5.2 * t)
            val f = glide * vibrato
            st.phase += 2.0 * PI * f / sampleRate
            st.phase2 += 2.0 * PI * f * 2.01 / sampleRate
            val s = sin(st.phase) * 0.8 + sin(st.phase2) * 0.2
            chunk[i] += (s * env).toFloat() * gain
        }
    }

    /** Deep sub throb: 40 Hz sine pulsing every ~3.3 s like a distant heartbeat. */
    private fun renderSubPulse(chunk: FloatArray, st: LayerState, gain: Float) {
        for (i in chunk.indices) {
            val t = (totalSamples + i).toDouble() / sampleRate
            val cycle = t % 3.3
            val env = if (cycle < 0.9) exp(-cycle / 0.45) * (1.0 - exp(-cycle / 0.02)) else 0.0
            st.phase += 2.0 * PI * 40.0 / sampleRate
            chunk[i] += (sin(st.phase) * env * 0.9).toFloat() * gain
        }
    }

    /** Detuned sine-stack pad; `brightness` scales the upper voices. */
    private fun renderPad(chunk: FloatArray, st: LayerState, gain: Float, freqs: DoubleArray, brightness: Double) {
        if (st.padPhases.size != freqs.size) st.padPhases = DoubleArray(freqs.size)
        for (i in chunk.indices) {
            var s = 0.0
            for (v in freqs.indices) {
                val wobble = 1.0 + 0.0015 * sin(2.0 * PI * (0.05 + 0.02 * v) * (totalSamples + i) / sampleRate + v * 1.7)
                st.padPhases[v] = (st.padPhases[v] + freqs[v] * wobble / sampleRate) % 1.0
                val voiceGain = when (v) {
                    0 -> 1.0
                    1 -> 0.55
                    else -> 0.38 * brightness
                }
                s += sin(st.padPhases[v] * 2.0 * PI) * voiceGain
            }
            chunk[i] += (s / freqs.size).toFloat() * gain
        }
    }

    /** Sparkle: sparse very short high pings (2-5 kHz), like light on water. */
    private fun renderSparkle(chunk: FloatArray, st: LayerState, gain: Float) {
        if (st.nextEventAt < 0) st.nextEventAt = totalSamples + (rnd() * sampleRate).toLong()
        for (i in chunk.indices) {
            val abs = totalSamples + i
            if (abs >= st.nextEventAt && st.eventEnd <= abs) {
                st.eventStart = abs
                st.eventEnd = abs + (0.08 * sampleRate).toLong()
                st.evFreq = 2000.0 + rnd() * 3000.0
                st.nextEventAt = st.eventEnd + ((0.3 + rnd() * 1.2) * sampleRate).toLong()
            }
            if (abs in st.eventStart until st.eventEnd) {
                val t = (abs - st.eventStart).toDouble() / sampleRate
                val env = exp(-t / 0.025)
                st.phase += 2.0 * PI * st.evFreq / sampleRate
                chunk[i] += (sin(st.phase) * env * 0.5).toFloat() * gain
            }
        }
    }

    /** Drip: single water drops — short sine with a downward pitch bend. */
    private fun renderDrip(chunk: FloatArray, st: LayerState, gain: Float) {
        if (st.nextEventAt < 0) st.nextEventAt = totalSamples + (rnd() * sampleRate).toLong()
        for (i in chunk.indices) {
            val abs = totalSamples + i
            if (abs >= st.nextEventAt && st.eventEnd <= abs) {
                st.eventStart = abs
                st.eventEnd = abs + (0.09 * sampleRate).toLong()
                st.evFreq = 800.0 + rnd() * 600.0
                st.nextEventAt = st.eventEnd + ((0.7 + rnd() * 1.8) * sampleRate).toLong()
            }
            if (abs in st.eventStart until st.eventEnd) {
                val frac = (abs - st.eventStart).toDouble() / (st.eventEnd - st.eventStart)
                val env = exp(-frac * 5.0)
                val f = st.evFreq * (1.0 - 0.35 * frac)
                st.phase += 2.0 * PI * f / sampleRate
                chunk[i] += (sin(st.phase) * env).toFloat() * gain
            }
        }
    }

    /** Two near-unison low sines (55 & 55.8 Hz) that beat ~0.8 times/s — quiet unease. */
    private fun renderBeating(chunk: FloatArray, st: LayerState, gain: Float) {
        for (i in chunk.indices) {
            st.phase = (st.phase + 55.0 / sampleRate) % 1.0
            st.phase2 = (st.phase2 + 55.8 / sampleRate) % 1.0
            val s = sin(st.phase * 2.0 * PI) * 0.6 + sin(st.phase2 * 2.0 * PI) * 0.6
            chunk[i] += (s * 0.5).toFloat() * gain
        }
    }

    /** Breath: slow in/out band-passed noise, ~5.5 s per cycle. */
    private fun renderBreath(chunk: FloatArray, st: LayerState, gain: Float) {
        for (i in chunk.indices) {
            val t = (totalSamples + i).toDouble() / sampleRate
            val cycle = (t % 5.5) / 5.5
            val env = sin(PI * cycle).pow(2.0) * 0.8
            val noise = (rnd() * 2.0 - 1.0).toFloat()
            st.lp1 += (noise - st.lp1) * 0.06f
            st.lp2 += (st.lp1 - st.lp2) * 0.10f
            chunk[i] += st.lp2 * gain * env.toFloat()
        }
    }
}
