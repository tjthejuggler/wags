package com.example.wags.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.wags.domain.model.TimeDimension
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private companion object {
        const val KEY_DIMENSION = "dimension"
    }
}
