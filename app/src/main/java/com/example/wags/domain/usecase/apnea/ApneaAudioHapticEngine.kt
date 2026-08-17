package com.example.wags.domain.usecase.apnea

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import com.example.wags.R
import com.example.wags.domain.model.PersonalBestCategory
import com.example.wags.domain.model.trophyCount
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Provides TTS announcements and haptic feedback for apnea session events.
 * Uses Android TTS for audio and VibrationEffect for haptics.
 * Supports API 26+ with VibratorManager (API 31+) fallback to Vibrator.
 *
 * Voice and vibration can be independently toggled via [voiceEnabled] / [vibrationEnabled].
 * Settings are persisted in SharedPreferences so they survive across screens and sessions.
 */
@Singleton
class ApneaAudioHapticEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("apnea_prefs") private val prefs: SharedPreferences
) {
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                .defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    // ── Persisted toggle settings ─────────────────────────────────────────────

    var voiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_VOICE_ENABLED, value).apply() }

    var vibrationEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIBRATION_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_VIBRATION_ENABLED, value).apply() }

    /** Master toggle for real-time PB indication during free holds. */
    var pbIndicationEnabled: Boolean
        get() = prefs.getBoolean(KEY_PB_INDICATION_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_PB_INDICATION_ENABLED, value).apply() }

    /** Whether to play sound when a PB threshold is crossed during a hold. */
    var pbIndicationSound: Boolean
        get() = prefs.getBoolean(KEY_PB_INDICATION_SOUND, true)
        set(value) { prefs.edit().putBoolean(KEY_PB_INDICATION_SOUND, value).apply() }

    /** Whether to vibrate when a PB threshold is crossed during a hold. */
    var pbIndicationVibration: Boolean
        get() = prefs.getBoolean(KEY_PB_INDICATION_VIBRATION, true)
        set(value) { prefs.edit().putBoolean(KEY_PB_INDICATION_VIBRATION, value).apply() }

    // ── Configurable vibration warnings (hold / breath ending) ────────────────

    /** Vibration warning played during the final seconds of a hold. */
    var holdWarning: ApneaVibrationWarningConfig
        get() = loadWarningConfig(KEY_HOLD_WARNING_PREFIX, ApneaVibrationWarningConfig.HOLD_DEFAULT)
        set(value) = saveWarningConfig(KEY_HOLD_WARNING_PREFIX, value)

    /**
     * Vibration warning played during the final seconds of a breathe phase.
     * When [breathSameAsHold] is true this value is ignored and the hold
     * warning is used for both.
     */
    var breathWarning: ApneaVibrationWarningConfig
        get() = loadWarningConfig(KEY_BREATH_WARNING_PREFIX, ApneaVibrationWarningConfig.BREATH_DEFAULT)
        set(value) = saveWarningConfig(KEY_BREATH_WARNING_PREFIX, value)

    /** When true, breath and hold warnings share the [holdWarning] config. */
    var breathSameAsHold: Boolean
        get() = prefs.getBoolean(KEY_BREATH_SAME_AS_HOLD, false)
        set(value) { prefs.edit().putBoolean(KEY_BREATH_SAME_AS_HOLD, value).apply() }

    val effectiveHoldWarning: ApneaVibrationWarningConfig get() = holdWarning
    val effectiveBreathWarning: ApneaVibrationWarningConfig
        get() = if (breathSameAsHold) holdWarning else breathWarning

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.ENGLISH
                ttsReady = true
            }
        }
    }

    // ── TTS Announcements ────────────────────────────────────────────────────

    fun announceTimeRemaining(secondsLeft: Int) {
        if (!voiceEnabled) return
        val text = when (secondsLeft) {
            120 -> "Two minutes"
            60  -> "One minute"
            30  -> "Thirty seconds"
            10  -> "Ten"
            9   -> "Nine"
            8   -> "Eight"
            7   -> "Seven"
            6   -> "Six"
            5   -> "Five"
            4   -> "Four"
            3   -> "Three"
            2   -> "Two"
            1   -> "One"
            else -> return
        }
        val queueMode = if (secondsLeft <= 10) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        speak(text, queueMode)
    }

    fun announceBreath() {
        if (!voiceEnabled) return
        speakWithSilencePrefix("Breathe")
    }

    fun announceHoldBegin() {
        if (!voiceEnabled) return
        speakWithSilencePrefix("Hold")
    }

    fun announceRoundComplete(round: Int, total: Int) {
        if (!voiceEnabled) return
        speak("Round $round of $total complete", TextToSpeech.QUEUE_FLUSH)
    }

    fun announceSessionComplete() {
        if (!voiceEnabled) return
        speak("Session complete. Well done.", TextToSpeech.QUEUE_FLUSH)
    }

    // ── Haptic Events ────────────────────────────────────────────────────────

    /** Single short 80ms low-intensity pulse when a contraction is logged. */
    fun vibrateContractionLogged() {
        if (!vibrationEnabled) return
        vibrator.vibrate(VibrationEffect.createOneShot(80L, AMPLITUDE_LOW))
    }

    /**
     * Plays the full breath-ending warning waveform (beats + optional final
     * pulse). Call exactly once when the breathe-phase countdown enters the
     * configured warning window — the waveform then runs autonomously and is
     * aligned to end with the phase.
     */
    fun playBreathWarning() = playWarning(effectiveBreathWarning)

    /**
     * Plays the full hold-ending warning waveform (beats + optional final
     * pulse). Call exactly once when the hold countdown enters the configured
     * warning window.
     */
    fun playHoldWarning() = playWarning(effectiveHoldWarning)

    /** Stops any in-flight warning waveform (phase changed / session stopped). */
    fun cancelWarningVibrations() {
        try { vibrator.cancel() } catch (_: Exception) {}
    }

    /**
     * True when the hold-ending countdown's final pulse already covers the
     * moment the hold ends — callers can then skip the generic hold-end buzz.
     */
    fun holdEndCoveredByWarning(): Boolean =
        vibrationEnabled && effectiveHoldWarning.enabled && effectiveHoldWarning.finalPulseEnabled

    /**
     * Single longer 500ms pulse to signal the end of a hold (stop holding).
     *
     * @param countdownCovered pass true when this transition follows a
     *   countdown-driven hold whose final-second pulse (when enabled) already
     *   covers the end moment — the generic buzz is then skipped.
     */
    fun vibrateHoldEnd(countdownCovered: Boolean = false) {
        if (!vibrationEnabled) return
        if (countdownCovered && holdEndCoveredByWarning()) return
        vibrator.vibrate(VibrationEffect.createOneShot(500L, AMPLITUDE_HIGH))
    }

    /** 3 long 300ms pulses with 100ms gaps for abort/safety. Always fires regardless of settings. */
    fun vibrateAbort() {
        cancelWarningVibrations()
        val timings = longArrayOf(0L, 300L, 100L, 300L, 100L, 300L)
        val amplitudes = intArrayOf(0, AMPLITUDE_HIGH, 0, AMPLITUDE_HIGH, 0, AMPLITUDE_HIGH)
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }

    /** Safety announcement — always fires regardless of voice setting. */
    fun announceAbort() {
        speak("Warning! Low oxygen. Stop now.", TextToSpeech.QUEUE_FLUSH)
    }

    // ── Real-time PB Indication (during free hold) ─────────────────────────

    /**
     * Plays the PB celebration sound for [category] — same sounds as the
     * end-of-hold trophy dialog.  Used for real-time PB indication while
     * a free hold is in progress.
     */
    fun playPbIndicationSound(category: PersonalBestCategory) {
        if (!pbIndicationSound) return
        try {
            val mp = MediaPlayer.create(context, category.soundResId()) ?: return
            activePbPlayers += mp
            mp.setOnCompletionListener { player ->
                activePbPlayers -= player
                player.release()
            }
            mp.setOnErrorListener { player, _, _ ->
                activePbPlayers -= player
                player.release()
                true
            }
            mp.start()
        } catch (_: Exception) {
            // Silently swallow — a missing sound must never crash the hold.
        }
    }

    /**
     * Vibrates to indicate a PB threshold was crossed during a hold.
     * Vibration duration scales with trophy count: longer = broader record.
     *
     *   1 trophy (EXACT)          → 100ms
     *   2 trophies (FOUR_SETTINGS) → 200ms
     *   3 trophies (THREE_SETTINGS)→ 300ms
     *   4 trophies (TWO_SETTINGS)  → 400ms
     *   5 trophies (ONE_SETTING)   → 500ms
     *   6 trophies (GLOBAL)        → 600ms
     */
    fun vibratePbIndication(category: PersonalBestCategory) {
        if (!pbIndicationVibration) return
        val durationMs = category.trophyCount() * 100L
        vibrator.vibrate(VibrationEffect.createOneShot(durationMs, AMPLITUDE_HIGH))
    }

    /** Release any in-flight PB indication players. Called when a hold ends. */
    fun releasePbIndicationPlayers() {
        activePbPlayers.forEach { player ->
            try { player.release() } catch (_: Exception) {}
        }
        activePbPlayers.clear()
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        releasePbIndicationPlayers()
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        if (!ttsReady) return
        tts?.speak(text, queueMode, null, null)
    }

    /**
     * Builds and plays a warning waveform from [cfg]:
     * beat pulses at [ApneaVibrationWarningConfig.intervalSec] intervals for
     * the whole window minus the final pulse, then (optionally) one long
     * final pulse ending exactly when the phase ends.
     */
    private fun playWarning(cfg: ApneaVibrationWarningConfig) {
        if (!vibrationEnabled || !cfg.enabled || cfg.windowSec <= 0) return

        val windowMs = cfg.windowMs
        val finalMs = if (cfg.finalPulseEnabled)
            cfg.finalPulseMs.toLong().coerceIn(0L, windowMs * 4 / 5) else 0L
        val beatWindowMs = (windowMs - finalMs).coerceAtLeast(0L)
        val intervalMs = cfg.intervalMs.toLong().coerceIn(250L, 2000L)
        val pulseMs = (intervalMs * 2 / 5).coerceIn(40L, 200L)
            .coerceAtMost(intervalMs - 100L).coerceAtLeast(20L)

        val timings = mutableListOf<Long>()
        val amplitudes = mutableListOf<Int>()
        val beatAmp = cfg.beatAmplitude.coerceAtLeast(1)
        var cursor = 0L          // end of the last scheduled segment
        var nextBeatStart = 0L   // where the next beat pulse begins

        while (nextBeatStart + pulseMs <= beatWindowMs) {
            if (nextBeatStart > cursor) {              // silent gap before beat
                timings += nextBeatStart - cursor
                amplitudes += 0
            }
            timings += pulseMs
            amplitudes += beatAmp
            cursor = nextBeatStart + pulseMs
            nextBeatStart += intervalMs
        }
        if (finalMs > 0L) {
            if (beatWindowMs > cursor) {               // silence before final
                timings += beatWindowMs - cursor
                amplitudes += 0
            }
            timings += finalMs
            amplitudes += cfg.finalAmplitude.coerceAtLeast(1)
        }
        if (timings.isEmpty()) return

        vibrator.vibrate(VibrationEffect.createWaveform(timings.toLongArray(), amplitudes.toIntArray(), -1))
    }

    private fun loadWarningConfig(
        prefix: String,
        default: ApneaVibrationWarningConfig
    ): ApneaVibrationWarningConfig = ApneaVibrationWarningConfig(
        enabled = prefs.getBoolean("${prefix}enabled", default.enabled),
        windowSec = prefs.getInt("${prefix}window_sec", default.windowSec),
        intensityPct = prefs.getInt("${prefix}intensity", default.intensityPct),
        intervalMs = prefs.getInt("${prefix}interval_ms", default.intervalMs),
        finalPulseEnabled = prefs.getBoolean("${prefix}final_enabled", default.finalPulseEnabled),
        finalPulseMs = prefs.getInt("${prefix}final_ms", default.finalPulseMs),
        finalIntensityPct = prefs.getInt("${prefix}final_intensity", default.finalIntensityPct)
    )

    private fun saveWarningConfig(prefix: String, cfg: ApneaVibrationWarningConfig) {
        prefs.edit()
            .putBoolean("${prefix}enabled", cfg.enabled)
            .putInt("${prefix}window_sec", cfg.windowSec)
            .putInt("${prefix}intensity", cfg.intensityPct)
            .putInt("${prefix}interval_ms", cfg.intervalMs)
            .putBoolean("${prefix}final_enabled", cfg.finalPulseEnabled)
            .putInt("${prefix}final_ms", cfg.finalPulseMs)
            .putInt("${prefix}final_intensity", cfg.finalIntensityPct)
            .apply()
    }

    /**
     * Speaks [text] with a 500ms silence prefix so the beginning of the word
     * is not clipped by the audio system waking up.
     */
    private fun speakWithSilencePrefix(text: String) {
        if (!ttsReady) return
        val engine = tts ?: return
        // Queue a short silence first, then the actual text
        val silenceParams = Bundle()
        engine.playSilentUtterance(500L, TextToSpeech.QUEUE_FLUSH, "silence_prefix")
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, "voice_${text.lowercase()}")
    }

    /**
     * Maps a [PersonalBestCategory] to its corresponding raw MP3 resource.
     * Same mapping as [ApneaPbSoundPlayer].
     */
    private fun PersonalBestCategory.soundResId(): Int = when (this) {
        PersonalBestCategory.EXACT           -> R.raw.apnea_pb1
        PersonalBestCategory.FOUR_SETTINGS   -> R.raw.apnea_pb2
        PersonalBestCategory.THREE_SETTINGS  -> R.raw.apnea_pb3
        PersonalBestCategory.TWO_SETTINGS    -> R.raw.apnea_pb4
        PersonalBestCategory.ONE_SETTING     -> R.raw.apnea_pb5
        PersonalBestCategory.GLOBAL          -> R.raw.apnea_pb6
    }

    companion object {
        private const val AMPLITUDE_LOW = 80
        private const val AMPLITUDE_MEDIUM = 150
        private const val AMPLITUDE_HIGH = 255

        const val KEY_VOICE_ENABLED = "apnea_voice_enabled"
        const val KEY_VIBRATION_ENABLED = "apnea_vibration_enabled"
        const val KEY_PB_INDICATION_ENABLED = "pb_indication_enabled"
        const val KEY_PB_INDICATION_SOUND = "pb_indication_sound"
        const val KEY_PB_INDICATION_VIBRATION = "pb_indication_vibration"
        private const val KEY_HOLD_WARNING_PREFIX = "apnea_vib_hold_"
        private const val KEY_BREATH_WARNING_PREFIX = "apnea_vib_breath_"
        private const val KEY_BREATH_SAME_AS_HOLD = "apnea_vib_breath_same_as_hold"

        /** Strong references to in-flight PB indication players. */
        private val activePbPlayers = mutableSetOf<MediaPlayer>()
    }
}
