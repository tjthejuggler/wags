package com.example.wags.ui.common.pip

import android.app.PendingIntent
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi

/**
 * Describes one button that appears in the OS Picture-in-Picture overlay.
 *
 * @param id       Unique string identifier — echoed back via [PipController.actionFlow] when tapped.
 * @param title    Accessibility label shown by the OS.
 * @param iconRes  Vector drawable resource for the button icon.
 */
data class PipAction(
    val id: String,
    val title: String,
    @DrawableRes val iconRes: Int
)

/** Well-known action IDs used across all apnea PiP composables. */
object PipActionIds {
    const val START             = "pip_start"
    const val FIRST_CONTRACTION = "pip_first_contraction"
    const val STOP              = "pip_stop"
    const val BREATH            = "pip_breath"
    const val HOLD              = "pip_hold"
    const val AGAIN             = "pip_again"
}

/** Broadcast intent action sent by the OS when a PiP RemoteAction button is tapped. */
const val PIP_ACTION_BROADCAST = "com.example.wags.PIP_ACTION"

/** Intent extra key carrying the [PipAction.id] string. */
const val PIP_EXTRA_ACTION_ID = "pip_action_id"

/**
 * BroadcastReceiver that receives taps on PiP overlay buttons and forwards
 * the action id to [PipController.actionFlow].
 *
 * Registered/unregistered in MainActivity on resume/pause.
 */
class PipActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PIP_ACTION_BROADCAST) return
        val actionId = intent.getStringExtra(PIP_EXTRA_ACTION_ID) ?: return
        PipController.emitAction(actionId)
    }
}

/** Builds an Android [RemoteAction] from a [PipAction] for API 26+. */
@RequiresApi(Build.VERSION_CODES.O)
internal fun PipAction.toRemoteAction(context: Context, requestCode: Int): RemoteAction {
    val intent = Intent(PIP_ACTION_BROADCAST).apply {
        putExtra(PIP_EXTRA_ACTION_ID, id)
        setPackage(context.packageName)
    }
    val pendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val icon = Icon.createWithResource(context, iconRes)
    return RemoteAction(icon, title, title, pendingIntent)
}
