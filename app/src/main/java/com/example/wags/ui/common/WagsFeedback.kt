package com.example.wags.ui.common

import android.content.Context
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.wags.R

/**
 * Lightweight haptic + audio feedback helpers used across the app.
 *
 * All functions are fire-and-forget: they catch any exception internally so
 * a missing permission or unavailable audio resource never crashes the caller.
 *
 * Usage (from a Composable LaunchedEffect):
 *   WagsFeedback.sessionEnd(context)
 *   WagsFeedback.standUp(context)
 */
object WagsFeedback {

    // ── Vibration patterns ────────────────────────────────────────────────────

    /**
     * "Session / reading / assessment complete" pattern.
     * Two firm pulses: 300 ms · pause 150 ms · 500 ms.
     */
    private val PATTERN_SESSION_END = longArrayOf(0, 300, 150, 500)

    /**
     * "Stand up now" pattern — mirrors the existing stand-alert behaviour.
     * Three escalating pulses: 300 · 100 · 300 · 100 · 500 ms.
     */
    private val PATTERN_STAND_UP = longArrayOf(0, 300, 100, 300, 100, 500)

    /**
     * Short single pulse for breath phase transitions (inhale ↔ exhale).
     * 80 ms at medium amplitude — noticeable but not intrusive.
     */
    private const val BREATH_TRANSITION_MS = 80L
    private const val BREATH_TRANSITION_AMPLITUDE = 120 // 0–255

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Play the end-of-session chime and fire the session-end vibration pattern.
     * Safe to call from any thread / coroutine.
     */
    fun sessionEnd(context: Context) {
        playSound(context, R.raw.chime_end)
        vibrate(context, PATTERN_SESSION_END)
    }

    /**
     * Play the stand-up sound and fire the stand-up vibration pattern.
     * Used when the morning readiness test transitions to the orthostatic phase.
     */
    fun standUp(context: Context) {
        playSound(context, R.raw.stand)
        vibrate(context, PATTERN_STAND_UP)
    }

    /**
     * Fire a short haptic pulse to signal a breath phase transition
     * (inhale → exhale or exhale → inhale). Only vibrates — no sound.
     */
    fun breathTransition(context: Context) {
        vibrateSingle(context, BREATH_TRANSITION_MS, BREATH_TRANSITION_AMPLITUDE)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun playSound(context: Context, resId: Int) {
        try {
            val mp = MediaPlayer.create(context.applicationContext, resId)
            if (mp != null) {
                mp.setOnCompletionListener { it.release() }
                mp.start()
            }
        } catch (_: Exception) { /* non-fatal */ }
    }

    private fun vibrate(context: Context, pattern: LongArray) {
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) { /* non-fatal */ }
    }

    private fun vibrateSingle(context: Context, durationMs: Long, amplitude: Int) {
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
            vibrator?.vibrate(
                VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255))
            )
        } catch (_: Exception) { /* non-fatal */ }
    }
}
