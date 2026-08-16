package com.example.wags.domain.usecase.apnea

import android.content.SharedPreferences
import com.example.wags.data.repository.ApneaRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.math.ceil

/**
 * Time-based lock on the HYPER prep type.
 *
 * After any session (free hold, tables, drills — anything that saves an
 * [com.example.wags.data.db.entity.ApneaRecordEntity]) is done with HYPER,
 * the prep type stays locked for a configurable number of days
 * (default [DEFAULT_LOCK_DAYS]). The lock is derived from the record DB —
 * no separate "last used" bookkeeping is needed.
 */
@Singleton
class HyperLockManager @Inject constructor(
    @Named("apnea_prefs") private val prefs: SharedPreferences,
    private val apneaRepository: ApneaRepository
) {
    companion object {
        const val PREF_LOCK_DAYS = "hyper_lock_days"
        const val DEFAULT_LOCK_DAYS = 7
        const val MAX_LOCK_DAYS = 365
        const val MS_PER_DAY = 24 * 60 * 60 * 1000L

        /**
         * Whole days left until HYPER unlocks. 0 means unlocked (or never used).
         * Uses ceil so "6.5 days left" shows as 7 until the full day completes.
         */
        fun remainingLockDays(lastUseMs: Long?, lockDays: Int, nowMs: Long): Int {
            if (lastUseMs == null || lastUseMs <= 0L || lockDays <= 0) return 0
            val remainingMs = lastUseMs + lockDays * MS_PER_DAY - nowMs
            if (remainingMs <= 0L) return 0
            return ceil(remainingMs.toDouble() / MS_PER_DAY).toInt()
        }

        /**
         * Complete days elapsed since the given setting value was last used in
         * any session. Null when it has never been used.
         */
        fun daysSinceUsed(lastUsedMs: Long?, nowMs: Long): Int? {
            if (lastUsedMs == null || lastUsedMs <= 0L) return null
            return ((nowMs - lastUsedMs) / MS_PER_DAY).toInt()
        }
    }

    /** Configured number of days required between HYPER sessions. */
    val lockDays: Int
        get() = prefs.getInt(PREF_LOCK_DAYS, DEFAULT_LOCK_DAYS).coerceIn(0, MAX_LOCK_DAYS)

    fun setLockDays(days: Int) {
        prefs.edit().putInt(PREF_LOCK_DAYS, days.coerceIn(0, MAX_LOCK_DAYS)).apply()
    }

    /** Live flow of the most recent HYPER-use timestamp (epoch ms). Null when never used. */
    fun observeLastHyperUse(): Flow<Long?> = apneaRepository.observeLastHyperUse()

    /** One-shot check whether HYPER is currently locked (queries the record DB). */
    suspend fun isLocked(nowMs: Long = System.currentTimeMillis()): Boolean {
        val lastUse = apneaRepository.getLastHyperUseOnce() ?: return false
        return remainingLockDays(lastUse, lockDays, nowMs) > 0
    }
}
