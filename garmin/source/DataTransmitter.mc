using Toybox.Communications;
using Toybox.System;

//! Handles transmitting session data from the watch to the companion phone app.
//! Uses the MessageQueue for sequential delivery (CIQ only supports one
//! transmit() in flight at a time).
module DataTransmitter {

    //! Transmit a hold payload to the phone via the message queue.
    //! Handles batching for large sample sets.
    function transmit(payload) {
        System.println("[WAGS] DataTransmitter.transmit() called");

        // Log payload details
        var pType = payload.get("type");
        var pDur = payload.get("durationMs");
        var pSamples = payload.get("packedSamples");
        var sampleCount = (pSamples != null) ? pSamples.size() : 0;
        System.println("[WAGS] Payload: type=" + pType + " duration=" + pDur + " samples=" + sampleCount);
        SyncLog.add("TX START dur=" + pDur + " smp=" + sampleCount);

        if (sampleCount > 30) {
            System.println("[WAGS] Queuing batched (" + sampleCount + " samples)");
            transmitBatched(payload);
        } else {
            // Build a clean single-message payload without null values
            var fc = payload.get("firstContractionMs");
            if (fc == null) { fc = -1; }
            var ct = payload.get("contractions");
            if (ct == null) { ct = []; }
            var startSec = payload.get("startEpochSec");
            if (startSec == null) { startSec = 0; }
            var endSec = payload.get("endEpochSec");
            if (endSec == null) { endSec = 0; }

            var cleanPayload = {
                "type"               => payload.get("type"),
                "id"                 => payload.get("id"),
                "durationMs"         => payload.get("durationMs"),
                "lungVolume"         => payload.get("lungVolume"),
                "prepType"           => payload.get("prepType"),
                "timeOfDay"          => payload.get("timeOfDay"),
                "firstContractionMs" => fc,
                "contractions"       => ct,
                "sampleCount"        => payload.get("sampleCount"),
                "startEpochSec"      => startSec,
                "endEpochSec"        => endSec,
                "packedSamples"      => (pSamples != null) ? pSamples : []
            };

            System.println("[WAGS] Queuing single message (samples=" + sampleCount + ")");
            MessageQueue.enqueue(cleanPayload);
        }
    }

    function transmitBatched(payload) {
        var samples = payload.get("packedSamples");
        var totalSamples = samples.size();
        var batchSize = 30;
        var holdId = payload.get("id");

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

            System.println("[WAGS] Queuing batch " + batchIndex + " (offset=" + offset + ", size=" + chunk.size() + ")");
            MessageQueue.enqueue(batchPayload);
            offset = end;
            batchIndex++;
        }

        // Build clean summary without null values
        var fc = payload.get("firstContractionMs");
        if (fc == null) { fc = -1; }
        var ct = payload.get("contractions");
        if (ct == null) { ct = []; }
        var startSec = payload.get("startEpochSec");
        if (startSec == null) { startSec = 0; }
        var endSec = payload.get("endEpochSec");
        if (endSec == null) { endSec = 0; }

        var summary = {
            "type"               => payload.get("type"),
            "id"                 => holdId,
            "durationMs"         => payload.get("durationMs"),
            "lungVolume"         => payload.get("lungVolume"),
            "prepType"           => payload.get("prepType"),
            "timeOfDay"          => payload.get("timeOfDay"),
            "firstContractionMs" => fc,
            "contractions"       => ct,
            "sampleCount"        => payload.get("sampleCount"),
            "startEpochSec"      => startSec,
            "endEpochSec"        => endSec,
            "batchCount"         => batchIndex
        };
        System.println("[WAGS] Queuing summary message (batchCount=" + batchIndex + ")");
        MessageQueue.enqueue(summary);
    }
}
