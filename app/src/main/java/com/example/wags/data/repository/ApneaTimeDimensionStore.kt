package com.example.wags.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.wags.domain.model.TimeBuckets
import com.example.wags.domain.model.TimeDimension
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/**
 * Holds the user's choice of apnea time dimension (Time of Day vs By the Hour).
 *
 * The choice is global for the whole apnea section: it decides whether
 * records / personal bests / trophies / stats / recommended settings / record
 * forecasts are bucketed by the classic Morning-Day-Night column or by the
 * hour-of-day derived from each record's timestamp. See [TimeDimension].
 */
@Singleton
class ApneaTimeDimensionStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("apnea_time_dimension", Context.MODE_PRIVATE)

    private val _dimension = MutableStateFlow(
        TimeDimension.fromName(prefs.getString(KEY_DIMENSION, null))
    )

    /** Hot flow of the current dimension — collect to react to mode switches. */
    val dimension: StateFlow<TimeDimension> = _dimension.asStateFlow()

    val current: TimeDimension get() = _dimension.value

    val isByHour: Boolean get() = _dimension.value == TimeDimension.BY_HOUR

    fun set(dimension: TimeDimension) {
        prefs.edit().putString(KEY_DIMENSION, dimension.name).apply()
        _dimension.value = dimension
    }

    /**
     * Emits immediately, then again at every local-hour boundary. Combined
     * into [effectiveTod] so By-the-Hour consumers refresh when the
     * wall-clock hour rolls over — otherwise the bucket stays frozen at the
     * hour it was computed at (e.g. a screen opened at 12:xx keeps showing
     * "H12" all through hour 13).
     */
    private val hourTick: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            val now = Calendar.getInstance()
            val nextTopOfHour = (now.clone() as Calendar).apply {
                set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                add(Calendar.HOUR_OF_DAY, 1)
            }
            delay((nextTopOfHour.timeInMillis - now.timeInMillis).coerceAtLeast(1_000L))
        }
    }

    /**
     * Effective time-bucket flow for a user-selected time-of-day source
     * (legacy "MORNING"/"DAY"/"NIGHT" names): the name passes through in
     * TIME_OF_DAY mode, while in BY_HOUR mode any legacy name is replaced
     * with the automatic current-hour bucket ("H14") — see
     * [TimeBuckets.normalizeSessionBucket]. Re-emits on selection/dimension
     * changes AND on every hour rollover (via the internal hour tick), so
     * long-lived collectors never act on or display a stale hour bucket.
     */
    fun effectiveTod(selectedTod: Flow<String>): Flow<String> =
        combine(selectedTod, dimension, hourTick) { tod, dim, _ ->
            TimeBuckets.normalizeSessionBucket(tod, dim)
        }.distinctUntilChanged()

    private companion object {
        const val KEY_DIMENSION = "dimension"
    }
}
