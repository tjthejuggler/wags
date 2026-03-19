using Toybox.Application;
using Toybox.Application.Storage;
using Toybox.System;
using Toybox.Time;

//! Persistent storage for completed free-hold sessions.
//!
//! Holds are stored in Application.Storage as a list of dictionaries.
//! Each hold has a unique ID and a "synced" flag.
//! The phone app requests unsynced holds, and once confirmed received,
//! the watch marks them as synced.
//!
//! Storage keys:
//!   "holds"     -> Array of hold dictionaries
//!   "nextId"    -> Next hold ID counter
//!
//! Each hold dictionary:
//!   "id"                  -> Integer unique ID
//!   "synced"              -> Boolean
//!   "durationMs"          -> Integer
//!   "lungVolume"          -> String
//!   "prepType"            -> String
//!   "timeOfDay"           -> String
//!   "firstContractionMs"  -> Integer or null
//!   "contractions"        -> Array of Integer (elapsed ms)
//!   "packedSamples"       -> Array of Integer (HR<<8 | SpO2)
//!   "sampleCount"         -> Integer
//!   "startEpochMs"        -> Long
//!   "endEpochMs"          -> Long
module HoldStorage {

    const KEY_HOLDS   = "holds";
    const KEY_NEXT_ID = "nextId";
    const MAX_HOLDS   = 20;

    //! Save a completed hold to persistent storage.
    //! Returns the hold ID.
    function saveHold(holdData) {
        var holds = getHolds();
        var nextId = Storage.getValue(KEY_NEXT_ID);
        if (nextId == null) {
            nextId = 1;
        }

        holdData.put("id", nextId);
        holdData.put("synced", false);

        holds.add(holdData);

        // Cap storage — remove oldest synced holds first, then oldest unsynced
        while (holds.size() > MAX_HOLDS) {
            var removedSynced = false;
            for (var i = 0; i < holds.size(); i++) {
                if (holds[i].get("synced") == true) {
                    holds = removeAt(holds, i);
                    removedSynced = true;
                    break;
                }
            }
            if (!removedSynced) {
                // Remove oldest unsynced
                holds = removeAt(holds, 0);
            }
        }

        Storage.setValue(KEY_HOLDS, holds);
        Storage.setValue(KEY_NEXT_ID, nextId + 1);

        System.println("[WAGS] Hold saved: id=" + nextId + " total=" + holds.size());
        return nextId;
    }

    //! Get all stored holds.
    function getHolds() {
        var holds = Storage.getValue(KEY_HOLDS);
        if (holds == null) {
            return [];
        }
        return holds;
    }

    //! Get all unsynced holds.
    function getUnsyncedHolds() {
        var holds = getHolds();
        var unsynced = [];
        for (var i = 0; i < holds.size(); i++) {
            if (holds[i].get("synced") != true) {
                unsynced.add(holds[i]);
            }
        }
        System.println("[WAGS] Unsynced holds: " + unsynced.size() + " of " + holds.size());
        return unsynced;
    }

    //! Get count of unsynced holds.
    function getUnsyncedCount() {
        var holds = getHolds();
        var count = 0;
        for (var i = 0; i < holds.size(); i++) {
            if (holds[i].get("synced") != true) {
                count++;
            }
        }
        return count;
    }

    //! Mark a hold as synced by ID.
    function markSynced(holdId) {
        var holds = getHolds();
        for (var i = 0; i < holds.size(); i++) {
            if (holds[i].get("id") == holdId) {
                holds[i].put("synced", true);
                Storage.setValue(KEY_HOLDS, holds);
                System.println("[WAGS] Hold " + holdId + " marked as synced");
                return true;
            }
        }
        System.println("[WAGS] Hold " + holdId + " not found for sync marking");
        return false;
    }

    //! Remove element at index from array (returns new array).
    function removeAt(arr, idx) {
        var result = [];
        for (var i = 0; i < arr.size(); i++) {
            if (i != idx) {
                result.add(arr[i]);
            }
        }
        return result;
    }

    //! Clear all synced holds to free storage space.
    function clearSynced() {
        var holds = getHolds();
        var remaining = [];
        for (var i = 0; i < holds.size(); i++) {
            if (holds[i].get("synced") != true) {
                remaining.add(holds[i]);
            }
        }
        Storage.setValue(KEY_HOLDS, remaining);
        System.println("[WAGS] Cleared synced holds. Remaining: " + remaining.size());
    }
}
