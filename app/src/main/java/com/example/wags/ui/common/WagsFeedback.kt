package com.example.wags.ui.common

import android.content.Context
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.wags.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
     * Single vibration for start-of-inhale (= end of exhale).
     * Called when isInhaling transitions to true.
     * User wants: **double vibration** at end of exhale.
     * Two 80ms pulses with a brief pause between them.
     */
    fun breathInhale(context: Context) {
        vibrateSingle(context, 80L, 200)
        CoroutineScope(Dispatchers.Main).launch {
            delay(160L)
            vibrateSingle(context, 80L, 200)
        }
    }

    /**
     * Vibration for start-of-exhale (= end of inhale).
     * Called when isInhaling transitions to false.
     * User wants: **single vibration** at end of inhale.
     * One 80ms pulse.
     */
    fun breathExhale(context: Context) {
        vibrateSingle(context, 80L, 200)
    }

    /**
     * Longer vibration to signal "breathe naturally" phase start (assessments).
     * 300ms at medium amplitude.
     */
    fun breathNaturallyStart(context: Context) {
        vibrateSingle(context, 300L, 150)
    }

    /**
     * @deprecated Use [breathInhale] or [breathExhale] instead.
     */
    fun breathTransition(context: Context) {
        breathInhale(context)
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
