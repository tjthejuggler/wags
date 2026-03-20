using Toybox.WatchUi;
using Toybox.Graphics;
using Toybox.System;

//! Top-level menu view: Apnea | Resonance
class MainMenuView extends WatchUi.View {

    function initialize() {
        View.initialize();
    }

    function onLayout(dc) {
    }

    function onUpdate(dc) {
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();

        var w = dc.getWidth();
        var h = dc.getHeight();
        var cx = w / 2;

        // Title
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, h / 6, Graphics.FONT_MEDIUM, "WAGS", Graphics.TEXT_JUSTIFY_CENTER);

        // Divider line
        dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawLine(cx - 60, h / 4 + 5, cx + 60, h / 4 + 5);

        // Menu items
        dc.setColor(0x00AAFF, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, h * 2 / 5, Graphics.FONT_MEDIUM, "Apnea", Graphics.TEXT_JUSTIFY_CENTER);

        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, h * 3 / 5, Graphics.FONT_MEDIUM, "Resonance", Graphics.TEXT_JUSTIFY_CENTER);

        // Hint
        dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, h * 4 / 5, Graphics.FONT_XTINY, "SELECT to choose", Graphics.TEXT_JUSTIFY_CENTER);
    }
}

//! Delegate for the main menu — pushes the appropriate sub-menu.
class MainMenuDelegate extends WatchUi.BehaviorDelegate {

    function initialize() {
        BehaviorDelegate.initialize();
    }

    function onSelect() {
        var menu = new WatchUi.Menu2({:title => "WAGS"});
        menu.addItem(new WatchUi.MenuItem("Apnea", "Free Hold & Tables", :menuApnea, null));
        menu.addItem(new WatchUi.MenuItem("Resonance", "Coming Soon", :menuResonance, null));
        var logCount = SyncLog.getStoredCount();
        var unsyncedCount = HoldStorage.getUnsyncedCount();
        menu.addItem(new WatchUi.MenuItem(
            "Sync Log",
            logCount + " entries | " + unsyncedCount + " unsynced",
            :menuSyncLog,
            null
        ));
        WatchUi.pushView(menu, new MainMenuItemDelegate(), WatchUi.SLIDE_LEFT);
        return true;
    }

    function onBack() {
        System.exit();
        return true;
    }
}

//! Handles selection from the top-level menu items.
class MainMenuItemDelegate extends WatchUi.Menu2InputDelegate {

    function initialize() {
        Menu2InputDelegate.initialize();
    }

    function onSelect(item) {
        var id = item.getId();
        if (id == :menuApnea) {
            var menu = new WatchUi.Menu2({:title => "Apnea"});
            menu.addItem(new WatchUi.MenuItem("Free Hold", "Start a breath hold", :menuFreeHold, null));
            menu.addItem(new WatchUi.MenuItem(
                "Settings",
                SettingsManager.lungVolumeLabel(SettingsManager.getLungVolume()) + " | " +
                SettingsManager.prepTypeLabel(SettingsManager.getPrepType()) + " | " +
                SettingsManager.timeOfDayLabel(SettingsManager.getTimeOfDay()),
                :menuSettings,
                null
            ));
            WatchUi.pushView(menu, new ApneaMenuDelegate(), WatchUi.SLIDE_LEFT);
        } else if (id == :menuResonance) {
            WatchUi.pushView(
                new ResonancePlaceholderView(),
                new ResonancePlaceholderDelegate(),
                WatchUi.SLIDE_LEFT
            );
        } else if (id == :menuSyncLog) {
            var view = new SyncLogView();
            WatchUi.pushView(view, new SyncLogDelegate(view), WatchUi.SLIDE_LEFT);
        }
    }

    function onBack() {
        WatchUi.popView(WatchUi.SLIDE_RIGHT);
    }
}
