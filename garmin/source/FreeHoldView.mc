using Toybox.WatchUi;
using Toybox.Graphics;
using Toybox.System;
using Toybox.Timer;
using Toybox.Sensor;
using Toybox.Attention;
using Toybox.Time;

//! Free Hold breath-hold view.
//!
//! States:
//!   READY   -> big "START" prompt; SELECT starts the hold
//!   HOLDING -> timer running, HR/SpO2 displayed; SELECT = contraction, BACK = stop
//!   DONE    -> summary shown; SELECT or BACK pops back to menu
class FreeHoldView extends WatchUi.View {

    // Hold state constants – use plain vars so they are accessible from delegate
    // 0 = READY, 1 = HOLDING, 2 = DONE
    var holdState = 0;  // STATE_READY

    // Timing
    var holdStartMs   = 0;
    var holdEndMs     = 0;
    var elapsedMs     = 0;

    // Contraction tracking
    var contractionTimesMs = [];

    // Sensor data buffers
    var hrSamples   = [];
    var spo2Samples = [];

    // Latest live readings
    var liveHr   = null;
    var liveSpo2 = null;

    // UI refresh timer
    var refreshTimer = null;

    function initialize() {
        View.initialize();
        refreshTimer = new Timer.Timer();
    }

    function onShow() {
        Sensor.setEnabledSensors([Sensor.SENSOR_HEARTRATE]);
        Sensor.enableSensorEvents(method(:onSensorEvent));
    }

    function onHide() {
        Sensor.enableSensorEvents(null);
        refreshTimer.stop();
    }

    //! Sensor callback – must accept a single Sensor.Info parameter and return nothing.
    function onSensorEvent(sensorInfo as Sensor.Info) as Void {
        if (sensorInfo.heartRate != null && sensorInfo.heartRate > 0) {
            liveHr = sensorInfo.heartRate;
        } else {
            liveHr = null;
        }

        if (sensorInfo has :oxygenSaturation && sensorInfo.oxygenSaturation != null) {
            liveSpo2 = sensorInfo.oxygenSaturation;
        } else {
            liveSpo2 = null;
        }

        // 1 = STATE_HOLDING
        if (holdState == 1) {
            hrSamples.add(liveHr);
            var sp = liveSpo2;
            spo2Samples.add(sp);
        }

        WatchUi.requestUpdate();
    }

    function onUpdate(dc) {
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();

        var w = dc.getWidth();
        var h = dc.getHeight();
        var cx = w / 2;
        var cy = h / 2;

        if (holdState == 0) {
            drawReadyState(dc, cx, cy, w, h);
        } else if (holdState == 1) {
            drawHoldingState(dc, cx, cy, w, h);
        } else {
            drawDoneState(dc, cx, cy, w, h);
        }
    }

    function drawReadyState(dc, cx, cy, w, h) {
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        var lv = SettingsManager.lungVolumeLabel(SettingsManager.getLungVolume());
        var pt = SettingsManager.prepTypeLabel(SettingsManager.getPrepType());
        var tod = SettingsManager.timeOfDayLabel(SettingsManager.getTimeOfDay());
        dc.drawText(cx, h / 6, Graphics.FONT_XTINY, lv + " | " + pt + " | " + tod, Graphics.TEXT_JUSTIFY_CENTER);

        if (liveHr != null) {
            dc.setColor(Graphics.COLOR_RED, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx, h / 4 + 5, Graphics.FONT_SMALL, "HR " + liveHr.toString(), Graphics.TEXT_JUSTIFY_CENTER);
        }

        dc.setColor(0x00CC66, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, cy - 10, Graphics.FONT_LARGE, "START", Graphics.TEXT_JUSTIFY_CENTER);

        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, cy + 30, Graphics.FONT_XTINY, "Press SELECT", Graphics.TEXT_JUSTIFY_CENTER);
    }

    function drawHoldingState(dc, cx, cy, w, h) {
        var now = System.getTimer();
        elapsedMs = now - holdStartMs;
        var totalSec = elapsedMs / 1000;
        var min = totalSec / 60;
        var sec = totalSec % 60;

        dc.setColor(0x00AAFF, Graphics.COLOR_TRANSPARENT);
        var timeStr;
        if (min > 0) {
            timeStr = min.format("%d") + ":" + sec.format("%02d");
        } else {
            timeStr = sec.format("%d") + "s";
        }
        dc.drawText(cx, cy - 35, Graphics.FONT_NUMBER_HOT, timeStr, Graphics.TEXT_JUSTIFY_CENTER);

        var sensorY = cy + 25;
        if (liveHr != null) {
            dc.setColor(Graphics.COLOR_RED, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx - 30, sensorY, Graphics.FONT_SMALL, "HR" + liveHr.toString(), Graphics.TEXT_JUSTIFY_CENTER);
        }
        if (liveSpo2 != null) {
            dc.setColor(0x00CC66, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx + 35, sensorY, Graphics.FONT_SMALL, liveSpo2.toString() + "%", Graphics.TEXT_JUSTIFY_CENTER);
        }

        var cCount = contractionTimesMs.size();
        if (cCount > 0) {
            dc.setColor(0xFFAA00, Graphics.COLOR_TRANSPARENT);
            var firstC = contractionTimesMs[0];
            var fcSec = firstC / 1000;
            dc.drawText(cx, sensorY + 25, Graphics.FONT_XTINY,
                "Contraction @ " + fcSec.toString() + "s", Graphics.TEXT_JUSTIFY_CENTER);
        }

        dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_TRANSPARENT);
        if (cCount > 0) {
            dc.drawText(cx, h - 35, Graphics.FONT_XTINY, "SEL=Stop  BACK=Stop", Graphics.TEXT_JUSTIFY_CENTER);
        } else {
            dc.drawText(cx, h - 35, Graphics.FONT_XTINY, "SEL=Contract BACK=Stop", Graphics.TEXT_JUSTIFY_CENTER);
        }
    }

    function drawDoneState(dc, cx, cy, w, h) {
        var durationMs = holdEndMs - holdStartMs;
        var totalSec = durationMs / 1000;
        var min = totalSec / 60;
        var sec = totalSec % 60;

        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, h / 6, Graphics.FONT_SMALL, "Hold Complete", Graphics.TEXT_JUSTIFY_CENTER);

        dc.setColor(0x00AAFF, Graphics.COLOR_TRANSPARENT);
        var timeStr;
        if (min > 0) {
            timeStr = min.format("%d") + "m " + sec.format("%d") + "s";
        } else {
            timeStr = sec.format("%d") + "s";
        }
        dc.drawText(cx, cy - 25, Graphics.FONT_MEDIUM, timeStr, Graphics.TEXT_JUSTIFY_CENTER);

        var cCount = contractionTimesMs.size();
        dc.setColor(0xFFAA00, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, cy + 5, Graphics.FONT_SMALL,
            "Contractions: " + cCount.toString(), Graphics.TEXT_JUSTIFY_CENTER);

        if (cCount > 0) {
            var firstC = contractionTimesMs[0];
            var fcSec = firstC / 1000;
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx, cy + 30, Graphics.FONT_XTINY,
                "1st @ " + fcSec.toString() + "s", Graphics.TEXT_JUSTIFY_CENTER);
        }

        dc.setColor(0x00CC66, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, h * 3 / 4 + 10, Graphics.FONT_XTINY, "Saved & syncing to phone", Graphics.TEXT_JUSTIFY_CENTER);
        dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, h - 25, Graphics.FONT_XTINY, "BACK to return", Graphics.TEXT_JUSTIFY_CENTER);
    }

    // ── Hold control ────────────────────────────────────────────────────────

    function startHold() {
        holdState = 1; // STATE_HOLDING
        holdStartMs = System.getTimer();
        holdEndMs = 0;
        elapsedMs = 0;
        contractionTimesMs = [];
        hrSamples = [];
        spo2Samples = [];
        SyncLog.add("Hold START");

        if (Attention has :vibrate) {
            Attention.vibrate([new Attention.VibeProfile(100, 200)]);
        }

        refreshTimer.start(method(:onRefreshTimer), 200, true);
        WatchUi.requestUpdate();
    }

    function recordContraction() {
        if (holdState != 1) { // STATE_HOLDING
            return;
        }
        var elapsed = System.getTimer() - holdStartMs;
        contractionTimesMs.add(elapsed);

        if (Attention has :vibrate) {
            Attention.vibrate([new Attention.VibeProfile(50, 100)]);
        }

        WatchUi.requestUpdate();
    }

    function stopHold() {
        if (holdState != 1) { // STATE_HOLDING
            return;
        }
        holdEndMs = System.getTimer();
        holdState = 2; // STATE_DONE
        refreshTimer.stop();
        var durSec = (holdEndMs - holdStartMs) / 1000;
        SyncLog.add("Hold STOP " + durSec + "s hr=" + hrSamples.size() + " smp");

        if (Attention has :vibrate) {
            Attention.vibrate([
                new Attention.VibeProfile(100, 200),
                new Attention.VibeProfile(0, 100),
                new Attention.VibeProfile(100, 200)
            ]);
        }

        saveSessionData();
        WatchUi.requestUpdate();
    }

    function onRefreshTimer() {
        WatchUi.requestUpdate();
    }

    // ── Data storage & transmission ─────────────────────────────────────────

    //! Save the completed hold to persistent storage AND transmit to phone.
    //! Dual approach: save locally (in case phone isn't listening) and also
    //! transmit immediately (in case phone IS listening right now).
    function saveSessionData() {
        var durationMs = holdEndMs - holdStartMs;

        var contractions = [];
        for (var i = 0; i < contractionTimesMs.size(); i++) {
            contractions.add(contractionTimesMs[i]);
        }

        // Use -1 instead of null to avoid CIQ serialization issues
        var firstContractionMs = -1;
        if (contractionTimesMs.size() > 0) {
            firstContractionMs = contractionTimesMs[0];
        }

        // Pack HR/SpO2 samples: (HR << 8) | SpO2
        // HR=0 means null, SpO2=255 means null
        var packedSamples = [];
        for (var i = 0; i < hrSamples.size(); i++) {
            var hr = hrSamples[i];
            var sp = spo2Samples[i];
            var hrVal = (hr != null) ? hr : 0;
            var spVal = (sp != null) ? sp : 255;
            packedSamples.add((hrVal << 8) | spVal);
        }

        // Store epoch as seconds (not ms) to avoid Long overflow in CIQ
        var nowEpochSec = Time.now().value();
        var durationSec = durationMs / 1000;

        var holdData = {
            "durationMs"         => durationMs,
            "lungVolume"         => SettingsManager.getLungVolume(),
            "prepType"           => SettingsManager.getPrepType(),
            "timeOfDay"          => SettingsManager.getTimeOfDay(),
            "firstContractionMs" => firstContractionMs,
            "contractions"       => contractions,
            "packedSamples"      => packedSamples,
            "sampleCount"        => hrSamples.size(),
            "startEpochSec"      => nowEpochSec - durationSec,
            "endEpochSec"        => nowEpochSec
        };

        // 1. Save locally for persistence
        var holdId = HoldStorage.saveHold(holdData);

        // 2. Also transmit immediately to phone (best-effort)
        //    Add type and id fields for the phone to identify
        holdData.put("type", "FREE_HOLD_RESULT");
        holdData.put("id", holdId);
        System.println("[WAGS] Transmitting hold " + holdId + " to phone...");
        DataTransmitter.transmit(holdData);
    }
}

//! Input delegate for the Free Hold view.
class FreeHoldDelegate extends WatchUi.BehaviorDelegate {

    var _view;

    function initialize(view) {
        BehaviorDelegate.initialize();
        _view = view;
    }

    function onSelect() {
        if (_view.holdState == 0) { // STATE_READY
            _view.startHold();
        } else if (_view.holdState == 1) { // STATE_HOLDING
            if (_view.contractionTimesMs.size() > 0) {
                // Already recorded a contraction — SELECT now stops the hold
                _view.stopHold();
            } else {
                _view.recordContraction();
            }
        } else {
            WatchUi.popView(WatchUi.SLIDE_RIGHT);
        }
        return true;
    }

    function onBack() {
        if (_view.holdState == 1) { // STATE_HOLDING
            _view.stopHold();
        } else {
            WatchUi.popView(WatchUi.SLIDE_RIGHT);
        }
        return true;
    }
}
