using Toybox.Application;
using Toybox.Communications;
using Toybox.WatchUi;
using Toybox.System;
using Toybox.Timer;

//! Main application entry point for the WAGS Garmin Device App.
class WagsApp extends Application.AppBase {

    function initialize() {
        AppBase.initialize();
    }

    function onStart(state) {
        Communications.registerForPhoneAppMessages(method(:onPhoneMessage));
        System.println("[WAGS] Registered for phone app messages");
        SyncLog.add("App START, registered msgs");
    }

    function getInitialView() {
        return [new MainMenuView(), new MainMenuDelegate()];
    }

    function onPhoneMessage(msg as Communications.PhoneAppMessage) as Void {
        System.println("[WAGS] Phone message received!");
        SyncLog.add("Phone msg received");

        if (msg == null) {
            System.println("[WAGS] Null message from phone");
            SyncLog.add("Phone msg ERR null");
            return;
        }

        var data = msg.data;
        if (data == null) {
            System.println("[WAGS] Null data in phone message");
            return;
        }

        if (data instanceof Toybox.Lang.Dictionary) {
            handleCommand(data);
        } else {
            System.println("[WAGS] Unexpected message format: " + data);
        }
    }

    function handleCommand(data) {
        var cmd = data.get("cmd");
        System.println("[WAGS] Command: " + cmd);
        SyncLog.add("CMD: " + cmd);

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

    function handlePing() {
        System.println("[WAGS] Handling PING");
        SyncLog.add("PING rcvd");
        var unsyncedCount = HoldStorage.getUnsyncedCount();
        var response = {
            "type" => "PONG",
            "unsyncedCount" => unsyncedCount
        };
        MessageQueue.enqueue(response);
    }

    function buildSummary(hold, batchCount) {
        var summary = {
            "type"       => "FREE_HOLD_RESULT",
            "id"         => hold.get("id"),
            "durationMs" => hold.get("durationMs"),
            "lungVolume" => hold.get("lungVolume"),
            "prepType"   => hold.get("prepType"),
            "timeOfDay"  => hold.get("timeOfDay"),
            "sampleCount"=> hold.get("sampleCount")
        };

        var fc = hold.get("firstContractionMs");
        summary.put("firstContractionMs", (fc != null) ? fc : -1);

        var ct = hold.get("contractions");
        summary.put("contractions", (ct != null) ? ct : []);

        var startSec = hold.get("startEpochSec");
        if (startSec == null) {
            var startMs = hold.get("startEpochMs");
            startSec = (startMs != null) ? (startMs / 1000) : 0;
        }
        var endSec = hold.get("endEpochSec");
        if (endSec == null) {
            var endMs = hold.get("endEpochMs");
            endSec = (endMs != null) ? (endMs / 1000) : 0;
        }
        summary.put("startEpochSec", startSec);
        summary.put("endEpochSec", endSec);

        if (batchCount > 0) {
            summary.put("batchCount", batchCount);
        }

        return summary;
    }

    function handleSyncRequest() {
        System.println("[WAGS] Handling SYNC_REQUEST");
        SyncLog.add("SYNC_REQ from phone");
        var unsynced = HoldStorage.getUnsyncedHolds();

        if (unsynced.size() == 0) {
            System.println("[WAGS] No unsynced holds to send");
            SyncLog.add("SYNC: 0 holds to send");
            MessageQueue.enqueue({ "type" => "SYNC_COMPLETE", "count" => 0 });
            return;
        }

        System.println("[WAGS] Sending " + unsynced.size() + " unsynced holds");
        SyncLog.add("SEND " + unsynced.size() + " holds");

        for (var i = 0; i < unsynced.size(); i++) {
            var hold = unsynced[i];
            var samples = hold.get("packedSamples");
            var sampleCount = (samples != null) ? samples.size() : 0;

            if (sampleCount > 30) {
                queueHoldBatched(hold);
            } else {
                var summary = buildSummary(hold, 0);
                summary.put("packedSamples", (samples != null && sampleCount > 0) ? samples : []);
                SyncLog.add("Q hold #" + hold.get("id") + " " + sampleCount + "smp");
                MessageQueue.enqueue(summary);
            }
        }
    }

    function queueHoldBatched(hold) {
        var samples = hold.get("packedSamples");
        var totalSamples = samples.size();
        var batchSize = 30;
        var holdId = hold.get("id");

        SyncLog.add("Q hold #" + holdId + " batched " + totalSamples + "smp");

        var batchIndex = 0;
        var offset = 0;
        while (offset < totalSamples) {
            var end = offset + batchSize;
            if (end > totalSamples) { end = totalSamples; }

            var chunk = samples.slice(offset, end);
            MessageQueue.enqueue({
                "type"       => "TELEMETRY_BATCH",
                "holdId"     => holdId,
                "batchIndex" => batchIndex,
                "offset"     => offset,
                "samples"    => chunk
            });
            offset = end;
            batchIndex++;
        }

        var summary = buildSummary(hold, batchIndex);
        MessageQueue.enqueue(summary);
    }

    function handleAckHold(data) {
        var holdId = data.get("holdId");
        System.println("[WAGS] Handling ACK_HOLD for id=" + holdId);
        SyncLog.add("ACK hold #" + holdId);
        if (holdId != null) {
            HoldStorage.markSynced(holdId);
        }
    }

    function onStop(state) {
    }
}

//! Message queue for sequential transmit.
//! CIQ only supports ONE transmit() in flight at a time (max 3 in BLE queue).
//! This module queues messages and sends them one at a time, waiting for
//! onComplete/onError before sending the next.
//! CRITICAL: options parameter MUST be null (not {}) to avoid firmware crash on fenix6.
module MessageQueue {
    var _queue = [];
    var _sending = false;
    var _retryCount = 0;

    function enqueue(payload) {
        _queue.add(payload);
        System.println("[WAGS] MQ enqueue, qSize=" + _queue.size() + " sending=" + _sending);
        if (!_sending) {
            _doSend();
        }
    }

    function _doSend() {
        if (_queue.size() == 0) {
            _sending = false;
            SyncLog.add("MQ done q=0");
            System.println("[WAGS] MQ done, queue empty");
            return;
        }

        _sending = true;
        var payload = _queue[0];
        var pType = "?";
        if (payload instanceof Toybox.Lang.Dictionary) {
            var t = payload.get("type");
            if (t != null) { pType = t; }
        }
        SyncLog.add("MQ TX " + pType + " q=" + _queue.size());
        System.println("[WAGS] MQ TX " + pType + " q=" + _queue.size());

        // CRITICAL: pass null for options, NOT {} — empty dict crashes fenix6 firmware
        Communications.transmit(payload, null, new QueueListener());
    }

    function onOK() {
        SyncLog.add("MQ TX OK");
        System.println("[WAGS] MQ TX OK");
        _retryCount = 0;
        if (_queue.size() > 0) {
            _queue = _queue.slice(1, null);
        }
        // Small delay before next send to let BLE stack breathe
        MQTimer.start(200, false);
    }

    function onFail() {
        _retryCount = _retryCount + 1;
        SyncLog.add("MQ FAIL #" + _retryCount);
        System.println("[WAGS] MQ FAIL #" + _retryCount);

        if (_retryCount <= 3) {
            // Retry with longer backoff (5s * attempt) to avoid flooding BLE queue.
            // Short retries (500ms) can overwhelm the firmware buffer and cause crashes.
            var delay = 5000 * _retryCount;
            SyncLog.add("MQ retry in " + delay + "ms");
            MQTimer.start(delay, true);
        } else {
            SyncLog.add("MQ SKIP after " + _retryCount + " fails");
            System.println("[WAGS] MQ SKIP after " + _retryCount + " retries");
            _retryCount = 0;
            if (_queue.size() > 0) {
                _queue = _queue.slice(1, null);
            }
            _sending = false;
            // Wait before trying next item in queue
            if (_queue.size() > 0) {
                MQTimer.start(2000, false);
            }
        }
    }
}

//! Helper class that owns a Timer and provides a proper method(:callback) for MessageQueue.
//! Modules can't use method() references, so we use this class instead.
class MQTimer {
    static var _instance = null;
    var _timer;
    var _isRetry = false;

    function initialize() {
        _timer = new Timer.Timer();
        _isRetry = false;
    }

    static function start(ms, isRetry) {
        if (_instance == null) {
            _instance = new MQTimer();
        }
        _instance._isRetry = isRetry;
        _instance._timer.start(_instance.method(:onTimer), ms, false);
    }

    function onTimer() {
        if (_isRetry && MessageQueue._queue.size() > 0) {
            // Retry same message — pass null for options
            var payload = MessageQueue._queue[0];
            SyncLog.add("MQ RETRY TX");
            System.println("[WAGS] MQ RETRY TX");
            Communications.transmit(payload, null, new QueueListener());
        } else {
            MessageQueue._doSend();
        }
    }
}

//! ConnectionListener for the message queue.
class QueueListener extends Communications.ConnectionListener {
    function initialize() {
        ConnectionListener.initialize();
    }
    function onComplete() {
        System.println("[WAGS] QueueListener.onComplete()");
        MessageQueue.onOK();
    }
    function onError() {
        System.println("[WAGS] QueueListener.onError()");
        MessageQueue.onFail();
    }
}
