using Toybox.Application;
using Toybox.Communications;
using Toybox.WatchUi;
using Toybox.System;

//! Main application entry point for the WAGS Garmin Device App.
//! Displays a top-level menu: Apnea | Resonance.
//!
//! Also registers for incoming phone messages so the companion Android app
//! can request unsynced holds and acknowledge receipt.
class WagsApp extends Application.AppBase {

    function initialize() {
        AppBase.initialize();
    }

    function onStart(state) {
        // Register to receive messages from the companion phone app.
        // This allows the phone to send SYNC_REQUEST and ACK_HOLD commands.
        Communications.registerForPhoneAppMessages(method(:onPhoneMessage));
        System.println("[WAGS] Registered for phone app messages");
    }

    //! Return the initial view + delegate.
    function getInitialView() {
        return [new MainMenuView(), new MainMenuDelegate()];
    }

    //! Handle incoming messages from the companion phone app.
    //! Message format: Dictionary with "cmd" key.
    //!
    //! Commands:
    //!   SYNC_REQUEST  -> Phone wants all unsynced holds
    //!   ACK_HOLD      -> Phone confirms receipt of a hold (includes "holdId")
    //!   PING          -> Phone checking if watch app is alive
    function onPhoneMessage(msg as Communications.PhoneAppMessage) as Void {
        System.println("[WAGS] Phone message received!");

        if (msg == null) {
            System.println("[WAGS] Null message from phone");
            return;
        }

        // The message data is in msg.data
        var data = msg.data;
        if (data == null) {
            System.println("[WAGS] Null data in phone message");
            return;
        }

        System.println("[WAGS] Message data type: " + data);

        if (data instanceof Toybox.Lang.Dictionary) {
            handleCommand(data);
        } else {
            System.println("[WAGS] Unexpected message format: " + data);
        }
    }

    function handleCommand(data) {
        var cmd = data.get("cmd");
        System.println("[WAGS] Command: " + cmd);

        if (cmd == null) {
            System.println("[WAGS] No 'cmd' key in message");
            return;
        }

        if (cmd.equals("PING")) {
            handlePing();
        } else if (cmd.equals("SYNC_REQUEST")) {
            handleSyncRequest();
        } else if (cmd.equals("ACK_HOLD")) {
            handleAckHold(data);
        } else {
            System.println("[WAGS] Unknown command: " + cmd);
        }
    }

    //! Respond to PING with a PONG and unsynced count.
    function handlePing() {
        System.println("[WAGS] Handling PING");
        var unsyncedCount = HoldStorage.getUnsyncedCount();
        var response = {
            "type" => "PONG",
            "unsyncedCount" => unsyncedCount
        };
        Communications.transmit(response, null, new PingConnectionListener());
    }

    //! Send all unsynced holds to the phone, one at a time.
    function handleSyncRequest() {
        System.println("[WAGS] Handling SYNC_REQUEST");
        var unsynced = HoldStorage.getUnsyncedHolds();

        if (unsynced.size() == 0) {
            System.println("[WAGS] No unsynced holds to send");
            var response = {
                "type" => "SYNC_COMPLETE",
                "count" => 0
            };
            Communications.transmit(response, null, new SyncConnectionListener());
            return;
        }

        System.println("[WAGS] Sending " + unsynced.size() + " unsynced holds");

        // Send each hold as a separate message
        for (var i = 0; i < unsynced.size(); i++) {
            var hold = unsynced[i];
            // Add type field for the phone to identify
            hold.put("type", "FREE_HOLD_RESULT");

            // Check if we need to batch the samples
            var samples = hold.get("packedSamples");
            if (samples != null && samples.size() > 50) {
                // Send samples separately to avoid message size limits
                sendHoldBatched(hold);
            } else {
                System.println("[WAGS] Sending hold " + hold.get("id") + " (single message)");
                Communications.transmit(hold, null, new SyncConnectionListener());
            }
        }
    }

    //! Send a hold with large sample data in batches.
    function sendHoldBatched(hold) {
        var samples = hold.get("packedSamples");
        var totalSamples = samples.size();
        var batchSize = 50;
        var holdId = hold.get("id");

        System.println("[WAGS] Sending hold " + holdId + " batched (" + totalSamples + " samples)");

        var batchIndex = 0;
        var offset = 0;
        while (offset < totalSamples) {
            var end = offset + batchSize;
            if (end > totalSamples) {
                end = totalSamples;
            }

            var chunk = samples.slice(offset, end);
            var batchPayload = {
                "type"       => "TELEMETRY_BATCH",
                "holdId"     => holdId,
                "batchIndex" => batchIndex,
                "offset"     => offset,
                "samples"    => chunk
            };

            Communications.transmit(batchPayload, null, new SyncConnectionListener());
            offset = end;
            batchIndex++;
        }

        // Send the hold summary without the packed samples
        var summary = {
            "type"               => "FREE_HOLD_RESULT",
            "id"                 => holdId,
            "durationMs"         => hold.get("durationMs"),
            "lungVolume"         => hold.get("lungVolume"),
            "prepType"           => hold.get("prepType"),
            "timeOfDay"          => hold.get("timeOfDay"),
            "firstContractionMs" => hold.get("firstContractionMs"),
            "contractions"       => hold.get("contractions"),
            "sampleCount"        => hold.get("sampleCount"),
            "startEpochMs"       => hold.get("startEpochMs"),
            "endEpochMs"         => hold.get("endEpochMs"),
            "batchCount"         => batchIndex
        };
        Communications.transmit(summary, null, new SyncConnectionListener());
    }

    //! Mark a hold as synced after phone confirms receipt.
    function handleAckHold(data) {
        var holdId = data.get("holdId");
        System.println("[WAGS] Handling ACK_HOLD for id=" + holdId);
        if (holdId != null) {
            HoldStorage.markSynced(holdId);
        }
    }

    function onStop(state) {
    }
}

//! ConnectionListener for ping responses.
class PingConnectionListener extends Communications.ConnectionListener {
    function initialize() {
        ConnectionListener.initialize();
    }
    function onComplete() {
        System.println("[WAGS] PONG sent OK");
    }
    function onError() {
        System.println("[WAGS] PONG send failed");
    }
}

//! ConnectionListener for sync data transmissions.
class SyncConnectionListener extends Communications.ConnectionListener {
    function initialize() {
        ConnectionListener.initialize();
    }
    function onComplete() {
        System.println("[WAGS] Sync data sent OK");
    }
    function onError() {
        System.println("[WAGS] Sync data send FAILED");
    }
}
