package com.example.wags.domain.usecase.apnea

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides TTS announcements and haptic feedback for apnea session events.
 * Uses Android TTS for audio and VibrationEffect for haptics.
 * Supports API 26+ with VibratorManager (API 31+) fallback to Vibrator.
 */
@Singleton
class ApneaAudioHapticEngine @Inject constructor(
    @ApplicationContext private val context: Context
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

    fun announceBreath() = speak("Breath", TextToSpeech.QUEUE_FLUSH)

    fun announceHoldBegin() = speak("Hold", TextToSpeech.QUEUE_FLUSH)

    fun announceRoundComplete(round: Int, total: Int) =
        speak("Round $round of $total complete", TextToSpeech.QUEUE_FLUSH)

    fun announceSessionComplete() =
        speak("Session complete. Well done.", TextToSpeech.QUEUE_FLUSH)

    // ── Haptic Events ────────────────────────────────────────────────────────

    /** Single short 80ms low-intensity pulse when a contraction is logged. */
    fun vibrateContractionLogged() {
        vibrator.vibrate(VibrationEffect.createOneShot(80L, AMPLITUDE_LOW))
    }

    /** 3 short pulses for the final 10 seconds of rest. */
    fun vibrateFinalCountdown() {
        val timings = longArrayOf(0L, 80L, 80L, 80L, 80L, 80L)
        val amplitudes = intArrayOf(0, AMPLITUDE_MEDIUM, 0, AMPLITUDE_MEDIUM, 0, AMPLITUDE_MEDIUM)
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }

    /** Continuous 500ms high-intensity pulse at end of breath-hold. */
    fun vibrateHoldEnd() {
        vibrator.vibrate(VibrationEffect.createOneShot(500L, AMPLITUDE_HIGH))
    }

    /** 3 long 300ms pulses with 100ms gaps for abort/safety. */
    fun vibrateAbort() {
        val timings = longArrayOf(0L, 300L, 100L, 300L, 100L, 300L)
        val amplitudes = intArrayOf(0, AMPLITUDE_HIGH, 0, AMPLITUDE_HIGH, 0, AMPLITUDE_HIGH)
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }

    fun announceAbort() {
        speak("Warning! Low oxygen. Stop now.", TextToSpeech.QUEUE_FLUSH)
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        if (!ttsReady) return
        tts?.speak(text, queueMode, null, null)
    }

    companion object {
        private const val AMPLITUDE_LOW = 80
        private const val AMPLITUDE_MEDIUM = 150
        private const val AMPLITUDE_HIGH = 255
    }
}
