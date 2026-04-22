package com.example.wags.ui.common.pip

import android.app.Activity
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Log
import android.util.Rational
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton coordinator for Android Picture-in-Picture.
 *
 * Usage pattern (any session screen):
 * 1. Call [setPipEligible] with `true` when the session screen is active.
 * 2. Call [setActions] whenever the set of available buttons changes.
 * 3. Observe [isInPipMode] to switch between full-screen and PiP layouts.
 * 4. Observe [actionFlow] to react to OS overlay button taps.
 *
 * MainActivity calls:
 * - [requestEnterPip] from `onUserLeaveHint()`
 * - [notifyPipModeChanged] from `onPictureInPictureModeChanged()`
 * - [emitAction] from [PipActionReceiver.onReceive]
 */
object PipController {

    // ── PiP mode state ───────────────────────────────────────────────────────

    private val _isInPipMode = MutableStateFlow(false)
    /** True while the app is displayed in the OS PiP window. */
    val isInPipMode: StateFlow<Boolean> = _isInPipMode.asStateFlow()

    // ── Eligibility ──────────────────────────────────────────────────────────

    /** True when at least one session screen has registered itself as PiP-eligible. */
    private var pipEligible: Boolean = false

    /**
     * Called by session screens to register/unregister PiP eligibility.
     * Also updates the OS PiP params immediately so auto-enter (API 31+) works.
     *
     * @param activity  The current Activity — needed to call [Activity.setPictureInPictureParams].
     * @param eligible  True when PiP should be available.
     */
    fun setPipEligible(activity: Activity, eligible: Boolean) {
        Log.d("PipController", "setPipEligible: $eligible")
        pipEligible = eligible
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Always push params so the OS knows the current eligibility + auto-enter flag.
            activity.setPictureInPictureParams(buildParams(activity))
        }
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    private var currentActions: List<PipAction> = emptyList()

    /**
     * Update the list of buttons shown in the OS PiP overlay.
     * Call this whenever the session state changes (e.g. contraction logged → only Stop remains).
     * Pass an empty list to show no overlay buttons.
     *
     * @param activity  The current Activity — needed to call [Activity.setPictureInPictureParams].
     * @param actions   Up to 3 [PipAction] items (OS limit).
     */
    fun setActions(activity: Activity, actions: List<PipAction>) {
        currentActions = actions.take(3)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Always update params — needed for auto-enter (API 31+) to pick up latest actions.
            activity.setPictureInPictureParams(buildParams(activity))
        }
    }

    // ── Action events ────────────────────────────────────────────────────────

    private val _actionFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /**
     * Emits the [PipAction.id] of each OS overlay button tap.
     * Collect this in your PiP composable to react to button presses.
     */
    val actionFlow: SharedFlow<String> = _actionFlow.asSharedFlow()

    /** Called by [PipActionReceiver] — do not call directly. */
    fun emitAction(actionId: String) {
        _actionFlow.tryEmit(actionId)
    }

    // ── Enter PiP ────────────────────────────────────────────────────────────

    /**
     * Attempt to enter PiP mode.
     * No-op if [pipEligible] is false or the device is below API 26.
     */
    fun requestEnterPip(activity: Activity) {
        Log.d("PipController", "requestEnterPip: pipEligible=$pipEligible, sdk=${Build.VERSION.SDK_INT}")
        if (!pipEligible) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            activity.enterPictureInPictureMode(buildParams(activity))
        } catch (e: Exception) {
            Log.w("PipController", "enterPictureInPictureMode failed: $e")
        }
    }

    /** Called by MainActivity.onPictureInPictureModeChanged. */
    fun notifyPipModeChanged(isInPip: Boolean) {
        Log.d("PipController", "notifyPipModeChanged: $isInPip")
        _isInPipMode.value = isInPip
        // Do NOT clear pipEligible here — the DisposableEffect in PipSessionHost
        // handles cleanup when the composable leaves composition.
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun buildParams(activity: Activity): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))

        if (currentActions.isNotEmpty()) {
            val remoteActions = currentActions.mapIndexed { index, action ->
                action.toRemoteAction(activity, requestCode = index + 100)
            }
            builder.setActions(remoteActions)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Auto-enter PiP when the Activity goes to background (API 31+).
            // This is the reliable way to trigger PiP on gesture-nav devices.
            builder.setAutoEnterEnabled(pipEligible)
            builder.setSeamlessResizeEnabled(false)
        }

        return builder.build()
    }
}
