package com.example.wags.domain.usecase.apnea

import com.example.wags.data.repository.ResonanceSessionRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Staleness gate on the RESONANCE prep type.
 *
 * A hold/drill only counts as resonance-prepped when a resonance breathing
 * session ended shortly before the apnea activity started — the user has up
 * to [PREP_WINDOW_MS] (≈5 minutes) after the resonance session finished to
 * begin the hold. Once that window elapses the RESONANCE prep option is
 * locked (🔒) until a new resonance breathing session is completed.
 *
 * Unlike [HyperLockManager] this lock never "expires on its own": it clears
 * the moment a fresh resonance session is saved (the DB flow re-emits).
 */
@Singleton
class ResonancePrepGate @Inject constructor(
    private val resonanceSessionRepository: ResonanceSessionRepository
) {
    companion object {
        /** Grace period between the resonance session ending and the apnea activity starting. */
        const val PREP_WINDOW_MS: Long = 5 * 60 * 1000L

        /**
         * Locked = no resonance session ever completed, or the most recent one
         * ended more than [PREP_WINDOW_MS] ago.
         */
        fun isLockedAt(lastEndMs: Long?, nowMs: Long): Boolean =
            lastEndMs == null || lastEndMs <= 0L || nowMs - lastEndMs > PREP_WINDOW_MS
    }

    /**
     * Live lock state. Re-emits every few seconds so the badge disappears
     * exactly when the 5-minute window elapses while a screen is open, and
     * immediately when a new resonance session is saved.
     */
    val isLocked: Flow<Boolean> = combine(
        resonanceSessionRepository.observeLatestEnd(),
        ticker()
    ) { lastEnd, now -> isLockedAt(lastEnd, now) }

    /** One-shot check (DB query) — authoritative guard used at selection time. */
    suspend fun isLockedNow(): Boolean =
        isLockedAt(resonanceSessionRepository.getLatestEndOnce(), System.currentTimeMillis())

    private fun ticker(): Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(2_000L)
        }
    }
}
