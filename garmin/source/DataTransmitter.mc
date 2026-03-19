using Toybox.Communications;
using Toybox.System;

//! ConnectionListener for batch transmissions.
class BatchConnectionListener extends Communications.ConnectionListener {
    function initialize() {
        ConnectionListener.initialize();
    }

    function onComplete() {
        System.println("[WAGS] Batch transmit OK");
    }

    function onError() {
        System.println("[WAGS] Batch transmit FAILED");
    }
}

//! ConnectionListener for the main/summary transmission.
class MainConnectionListener extends Communications.ConnectionListener {
    function initialize() {
        ConnectionListener.initialize();
    }

    function onComplete() {
        System.println("[WAGS] Main transmit OK — data sent to phone!");
        DataTransmitter._pendingPayload = null;
        DataTransmitter._retryCount = 0;
    }

    function onError() {
        System.println("[WAGS] Main transmit FAILED");
        DataTransmitter._retryCount++;
        if (DataTransmitter._retryCount < DataTransmitter.MAX_RETRIES) {
            System.println("[WAGS] Retrying... attempt " + DataTransmitter._retryCount);
            DataTransmitter.doTransmit();
        } else {
            System.println("[WAGS] Max retries reached, giving up.");
            DataTransmitter._pendingPayload = null;
            DataTransmitter._retryCount = 0;
        }
    }
}

//! Handles transmitting session data from the watch to the companion phone app.
module DataTransmitter {

    var _retryCount = 0;
    var _pendingPayload = null;
    const MAX_RETRIES = 3;

    function transmit(payload) {
        System.println("[WAGS] DataTransmitter.transmit() called");
        _pendingPayload = payload;
        _retryCount = 0;

        // Log payload details
        var pType = payload.get("type");
        var pDur = payload.get("durationMs");
        var pSamples = payload.get("packedSamples");
        var sampleCount = (pSamples != null) ? pSamples.size() : 0;
        System.println("[WAGS] Payload: type=" + pType + " duration=" + pDur + " samples=" + sampleCount);

        doTransmit();
    }

    function doTransmit() {
        if (_pendingPayload == null) {
            System.println("[WAGS] doTransmit: no pending payload");
            return;
        }

        var payload = _pendingPayload;
        var samples = payload.get("packedSamples");

        if (samples != null && samples.size() > 60) {
            System.println("[WAGS] Sending batched (" + samples.size() + " samples)");
            transmitBatched(payload);
        } else {
            System.println("[WAGS] Sending single message (samples=" +
                ((samples != null) ? samples.size() : 0) + ")");
            Communications.transmit(payload, null, new MainConnectionListener());
        }
    }

    function transmitBatched(payload) {
        var samples = payload.get("packedSamples");
        var totalSamples = samples.size();
        var batchSize = 60;

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
                "batchIndex" => batchIndex,
                "offset"     => offset,
                "samples"    => chunk
            };

            System.println("[WAGS] Sending batch " + batchIndex + " (offset=" + offset + ", size=" + chunk.size() + ")");
            Communications.transmit(batchPayload, null, new BatchConnectionListener());
            offset = end;
            batchIndex++;
        }

        var summary = {
            "type"               => payload.get("type"),
            "id"                 => payload.get("id"),
            "durationMs"         => payload.get("durationMs"),
            "lungVolume"         => payload.get("lungVolume"),
            "prepType"           => payload.get("prepType"),
            "timeOfDay"          => payload.get("timeOfDay"),
            "firstContractionMs" => payload.get("firstContractionMs"),
            "contractions"       => payload.get("contractions"),
            "sampleCount"        => payload.get("sampleCount"),
            "startEpochMs"       => payload.get("startEpochMs"),
            "endEpochMs"         => payload.get("endEpochMs"),
            "batchCount"         => batchIndex
        };
        System.println("[WAGS] Sending summary message (batchCount=" + batchIndex + ")");
        Communications.transmit(summary, null, new MainConnectionListener());
    }
}
