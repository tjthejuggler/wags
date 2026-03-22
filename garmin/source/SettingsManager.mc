using Toybox.Application;
using Toybox.Application.Storage;
using Toybox.System;
using Toybox.Time;

//! Persists and retrieves the three apnea settings:
//!   - Lung Volume  (FULL / EMPTY / PARTIAL)   — remembered across sessions
//!   - Prep Type    (NO_PREP / RESONANCE / HYPER) — remembered across sessions
//!   - Time of Day  (MORNING / DAY / NIGHT)     — auto-set from the clock
module SettingsManager {

    const KEY_LUNG_VOLUME = "lungVolume";
    const KEY_PREP_TYPE   = "prepType";

    //! Returns the stored lung volume, defaulting to "FULL".
    function getLungVolume() {
        var val = Storage.getValue(KEY_LUNG_VOLUME);
        if (val == null) {
            return "FULL";
        }
        return val;
    }

    //! Persists the lung volume setting.
    function setLungVolume(value) {
        Storage.setValue(KEY_LUNG_VOLUME, value);
    }

    //! Returns the stored prep type, defaulting to "NO_PREP".
    function getPrepType() {
        var val = Storage.getValue(KEY_PREP_TYPE);
        if (val == null) {
            return "NO_PREP";
        }
        return val;
    }

    //! Persists the prep type setting.
    function setPrepType(value) {
        Storage.setValue(KEY_PREP_TYPE, value);
    }

    //! Returns the current time-of-day bucket based on the watch clock.
    //!   Morning : 03:00 – 10:59
    //!   Day     : 11:00 – 17:59
    //!   Night   : 18:00 – 02:59
    function getTimeOfDay() {
        var clockTime = System.getClockTime();
        var hour = clockTime.hour;
        if (hour >= 3 && hour <= 10) {
            return "MORNING";
        } else if (hour >= 11 && hour <= 17) {
            return "DAY";
        } else {
            return "NIGHT";
        }
    }

    function lungVolumeLabel(value) {
        if (value.equals("FULL")) {
            return "Full";
        } else if (value.equals("EMPTY")) {
            return "Empty";
        } else if (value.equals("PARTIAL")) {
            return "Half";
        }
        return value;
    }

    function prepTypeLabel(value) {
        if (value.equals("NO_PREP")) {
            return "No Prep";
        } else if (value.equals("RESONANCE")) {
            return "Resonance";
        } else if (value.equals("HYPER")) {
            return "Hyper";
        }
        return value;
    }

    function timeOfDayLabel(value) {
        if (value.equals("MORNING")) {
            return "Morning";
        } else if (value.equals("DAY")) {
            return "Day";
        } else if (value.equals("NIGHT")) {
            return "Night";
        }
        return value;
    }
}
