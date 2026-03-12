package com.example.wags.domain.model

import java.util.Calendar

enum class TimeOfDay {
    MORNING,
    DAY,
    NIGHT;

    fun displayName(): String = when (this) {
        MORNING -> "Morning"
        DAY     -> "Day"
        NIGHT   -> "Night"
    }

    companion object {
        /**
         * Returns the TimeOfDay bucket that matches the current local hour:
         *   Morning : 03:00 – 10:59
         *   Day     : 11:00 – 17:59
         *   Night   : 18:00 – 02:59
         */
        fun fromCurrentTime(): TimeOfDay {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            return when (hour) {
                in 3..10  -> MORNING
                in 11..17 -> DAY
                else      -> NIGHT   // 18-23 and 0-2
            }
        }
    }
}
