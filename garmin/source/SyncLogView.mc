using Toybox.WatchUi;
using Toybox.Graphics;
using Toybox.System;

//! Scrollable view that displays the SyncLog entries on the watch.
//! Shows newest entries first. UP/DOWN scrolls, BACK exits.
class SyncLogView extends WatchUi.View {

    var scrollOffset = 0;
    var entries = [];
    var linesPerPage = 6;

    function initialize() {
        View.initialize();
    }

    function onShow() {
        // Reload entries each time the view is shown
        entries = SyncLog.getEntries();
        // Reverse so newest is first
        var reversed = [];
        for (var i = entries.size() - 1; i >= 0; i--) {
            reversed.add(entries[i]);
        }
        entries = reversed;
        scrollOffset = 0;
    }

    function onUpdate(dc) {
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();

        var w = dc.getWidth();
        var h = dc.getHeight();
        var cx = w / 2;

        // Title bar
        dc.setColor(0x00AAFF, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, 5, Graphics.FONT_XTINY, "Sync Log (" + entries.size() + ")", Graphics.TEXT_JUSTIFY_CENTER);

        if (entries.size() == 0) {
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx, h / 2 - 10, Graphics.FONT_SMALL, "No entries", Graphics.TEXT_JUSTIFY_CENTER);
            dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx, h / 2 + 15, Graphics.FONT_XTINY, "Run a hold to see logs", Graphics.TEXT_JUSTIFY_CENTER);
            return;
        }

        // Draw log entries
        var yStart = 25;
        var lineHeight = 22;
        var maxLines = (h - yStart - 20) / lineHeight;
        if (maxLines < 1) { maxLines = 1; }
        linesPerPage = maxLines;

        for (var i = 0; i < maxLines; i++) {
            var idx = scrollOffset + i;
            if (idx >= entries.size()) {
                break;
            }

            var entry = entries[idx];
            var y = yStart + (i * lineHeight);

            // Color code: green for success, red for fail, orange for action, gray for info
            var color = Graphics.COLOR_LT_GRAY;
            if (entry.find("OK") != null || entry.find("SAVED") != null || entry.find("SYNCED") != null) {
                color = 0x00CC66;
            } else if (entry.find("FAIL") != null || entry.find("ERR") != null) {
                color = 0xFF4444;
            } else if (entry.find("SEND") != null || entry.find("TX") != null || entry.find("START") != null) {
                color = 0xFFAA00;
            }

            dc.setColor(color, Graphics.COLOR_TRANSPARENT);

            // Truncate long entries to fit screen
            var maxChars = w / 6;  // rough estimate for XTINY font
            if (entry.length() > maxChars) {
                entry = entry.substring(0, maxChars);
            }

            dc.drawText(5, y, Graphics.FONT_XTINY, entry, Graphics.TEXT_JUSTIFY_LEFT);
        }

        // Scroll indicator
        if (entries.size() > maxLines) {
            var totalPages = (entries.size() + maxLines - 1) / maxLines;
            var currentPage = (scrollOffset / maxLines) + 1;
            dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx, h - 18, Graphics.FONT_XTINY,
                currentPage + "/" + totalPages, Graphics.TEXT_JUSTIFY_CENTER);
        }
    }

    function scrollDown() {
        if (scrollOffset + linesPerPage < entries.size()) {
            scrollOffset += linesPerPage;
            WatchUi.requestUpdate();
        }
    }

    function scrollUp() {
        if (scrollOffset > 0) {
            scrollOffset -= linesPerPage;
            if (scrollOffset < 0) { scrollOffset = 0; }
            WatchUi.requestUpdate();
        }
    }
}

//! Delegate for the SyncLogView — handles scrolling and back.
class SyncLogDelegate extends WatchUi.BehaviorDelegate {

    var _view;

    function initialize(view) {
        BehaviorDelegate.initialize();
        _view = view;
    }

    function onNextPage() {
        _view.scrollDown();
        return true;
    }

    function onPreviousPage() {
        _view.scrollUp();
        return true;
    }

    function onSelect() {
        // SELECT on the log view = clear log
        SyncLog.clear();
        _view.onShow();  // reload
        WatchUi.requestUpdate();
        return true;
    }

    function onBack() {
        WatchUi.popView(WatchUi.SLIDE_RIGHT);
        return true;
    }
}
