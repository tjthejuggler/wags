using Toybox.Application.Storage;
using Toybox.System;
using Toybox.Time;
using Toybox.Time.Gregorian;

//! Persistent log for debugging sync and data-save operations.
//!
//! Stores a circular buffer of log entries in Application.Storage.
//! Each entry is a string with a timestamp prefix.
//!
//! Storage keys:
//!   "syncLog"      -> Array of String log entries
//!   "syncLogCount" -> Integer total entries ever written (for display)
module SyncLog {

    const KEY_LOG   = "syncLog";
    const KEY_COUNT = "syncLogCount";
    const MAX_ENTRIES = 40;

    //! Add a log entry with automatic timestamp.
    function add(message) {
        var entries = getEntries();
        var ts = _timestamp();
        var entry = ts + " " + message;

        entries.add(entry);

        // Keep only the most recent MAX_ENTRIES
        while (entries.size() > MAX_ENTRIES) {
            entries = _removeFirst(entries);
        }

        Storage.setValue(KEY_LOG, entries);

        var count = Storage.getValue(KEY_COUNT);
        if (count == null) { count = 0; }
        Storage.setValue(KEY_COUNT, count + 1);

        System.println("[WAGS-LOG] " + entry);
    }

    //! Get all log entries (oldest first).
    function getEntries() {
        var entries = Storage.getValue(KEY_LOG);
        if (entries == null) {
            return [];
        }
        return entries;
    }

    //! Get the total number of entries ever written.
    function getTotalCount() {
        var count = Storage.getValue(KEY_COUNT);
        if (count == null) { return 0; }
        return count;
    }

    //! Get the number of currently stored entries.
    function getStoredCount() {
        return getEntries().size();
    }

    //! Clear all log entries.
    function clear() {
        Storage.setValue(KEY_LOG, []);
        Storage.setValue(KEY_COUNT, 0);
        System.println("[WAGS-LOG] Log cleared");
    }

    //! Generate a short timestamp string like "14:32:05".
    function _timestamp() {
        var now = Gregorian.info(Time.now(), Time.FORMAT_SHORT);
        return now.hour.format("%02d") + ":" +
               now.min.format("%02d") + ":" +
               now.sec.format("%02d");
    }

    //! Remove the first element from an array (returns new array).
    function _removeFirst(arr) {
        var result = [];
        for (var i = 1; i < arr.size(); i++) {
            result.add(arr[i]);
        }
        return result;
    }
}
