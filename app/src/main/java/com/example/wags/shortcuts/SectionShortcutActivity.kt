package com.example.wags.shortcuts

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.example.wags.MainActivity
import com.example.wags.ui.navigation.SectionShortcutBus

/**
 * Base class for the exported section-shortcut activities.
 *
 * External automation apps (Tail, Tasker) discover these via PackageManager
 * (`GET_ACTIVITIES`) and launch them with a bare explicit intent — no action,
 * no extras, no data — so each subclass must be fully self-contained: it
 * knows its [section] and forwards it to [MainActivity], which routes to the
 * matching nav destination via [SectionShortcutBus].
 *
 * Manifest flags that matter for external launchers:
 *  - `exported="true"` (mandatory — non-exported throws SecurityException)
 *  - `label` (shown as the row label in Tail's activity picker)
 *  - `noHistory` + `excludeFromRecents` (pure trampoline, finish()es at once)
 *  - `taskAffinity=""` (own task, snappy return to the caller)
 *  - translucent [Theme.Wags.Shortcut][com.example.wags.R.style] (no flash)
 */
abstract class SectionShortcutActivity : ComponentActivity() {

    /** Section id understood by [SectionShortcutBus.routeFor]. */
    protected abstract val section: String

    final override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(SectionShortcutBus.ACTION_OPEN_SECTION)
                .putExtra(SectionShortcutBus.EXTRA_SECTION, section)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }
}

/** Opens the Apnea section. */
class ApneaShortcutActivity : SectionShortcutActivity() {
    override val section = "apnea"
}

/** Opens the Morning Readiness section. */
class MorningReadinessShortcutActivity : SectionShortcutActivity() {
    override val section = "morning_readiness"
}

/** Opens the HRV Readiness section. */
class HrvReadinessShortcutActivity : SectionShortcutActivity() {
    override val section = "hrv_readiness"
}

/** Opens the Meditation section. */
class MeditationShortcutActivity : SectionShortcutActivity() {
    override val section = "meditation"
}

/** Opens the Resonance Breathing section. */
class ResonanceShortcutActivity : SectionShortcutActivity() {
    override val section = "resonance_breathing"
}
