using Toybox.WatchUi;
using Toybox.Graphics;

//! Handles selection from the Settings sub-menu.
class SettingsMenuDelegate extends WatchUi.Menu2InputDelegate {

    function initialize() {
        Menu2InputDelegate.initialize();
    }

    function onSelect(item) {
        var id = item.getId();
        if (id == :menuLungVolume) {
            var menu = new WatchUi.Menu2({:title => "Lung Volume"});
            var current = SettingsManager.getLungVolume();
            menu.addItem(new WatchUi.MenuItem("Full",    current.equals("FULL")    ? ">" : "", :lvFull,    null));
            menu.addItem(new WatchUi.MenuItem("Empty",   current.equals("EMPTY")   ? ">" : "", :lvEmpty,   null));
            menu.addItem(new WatchUi.MenuItem("Half", current.equals("PARTIAL") ? ">" : "", :lvPartial, null));
            WatchUi.pushView(menu, new LungVolumeDelegate(), WatchUi.SLIDE_LEFT);
        } else if (id == :menuPrepType) {
            var menu = new WatchUi.Menu2({:title => "Prep Type"});
            var current = SettingsManager.getPrepType();
            menu.addItem(new WatchUi.MenuItem("No Prep",   current.equals("NO_PREP")   ? ">" : "", :ptNoPrep,    null));
            menu.addItem(new WatchUi.MenuItem("Resonance", current.equals("RESONANCE") ? ">" : "", :ptResonance, null));
            menu.addItem(new WatchUi.MenuItem("Hyper",     current.equals("HYPER")     ? ">" : "", :ptHyper,     null));
            WatchUi.pushView(menu, new PrepTypeDelegate(), WatchUi.SLIDE_LEFT);
        } else if (id == :menuTimeOfDay) {
            WatchUi.pushView(
                new TimeOfDayInfoView(),
                new TimeOfDayInfoDelegate(),
                WatchUi.SLIDE_LEFT
            );
        }
    }

    function onBack() {
        WatchUi.popView(WatchUi.SLIDE_RIGHT);
    }
}

class LungVolumeDelegate extends WatchUi.Menu2InputDelegate {
    function initialize() {
        Menu2InputDelegate.initialize();
    }

    function onSelect(item) {
        var id = item.getId();
        if (id == :lvFull) {
            SettingsManager.setLungVolume("FULL");
        } else if (id == :lvEmpty) {
            SettingsManager.setLungVolume("EMPTY");
        } else if (id == :lvPartial) {
            SettingsManager.setLungVolume("PARTIAL");
        }
        // Pop the lung volume picker, then pop the stale settings menu
        // and push a fresh one so subtitles reflect the new value.
        WatchUi.popView(WatchUi.SLIDE_RIGHT);
        WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
        _pushFreshSettingsMenu();
    }

    function onBack() {
        WatchUi.popView(WatchUi.SLIDE_RIGHT);
    }
}

class PrepTypeDelegate extends WatchUi.Menu2InputDelegate {
    function initialize() {
        Menu2InputDelegate.initialize();
    }

    function onSelect(item) {
        var id = item.getId();
        if (id == :ptNoPrep) {
            SettingsManager.setPrepType("NO_PREP");
        } else if (id == :ptResonance) {
            SettingsManager.setPrepType("RESONANCE");
        } else if (id == :ptHyper) {
            SettingsManager.setPrepType("HYPER");
        }
        // Pop the prep type picker, then pop the stale settings menu
        // and push a fresh one so subtitles reflect the new value.
        WatchUi.popView(WatchUi.SLIDE_RIGHT);
        WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
        _pushFreshSettingsMenu();
    }

    function onBack() {
        WatchUi.popView(WatchUi.SLIDE_RIGHT);
    }
}

//! Helper to push a fresh Settings menu with up-to-date subtitles.
//! Called after changing a setting so the parent menu reflects the change.
function _pushFreshSettingsMenu() {
    var menu = new WatchUi.Menu2({:title => "Settings"});
    menu.addItem(new WatchUi.MenuItem(
        "Lung Volume",
        SettingsManager.lungVolumeLabel(SettingsManager.getLungVolume()),
        :menuLungVolume,
        null
    ));
    menu.addItem(new WatchUi.MenuItem(
        "Prep Type",
        SettingsManager.prepTypeLabel(SettingsManager.getPrepType()),
        :menuPrepType,
        null
    ));
    menu.addItem(new WatchUi.MenuItem(
        "Time of Day",
        SettingsManager.timeOfDayLabel(SettingsManager.getTimeOfDay()) + " (auto)",
        :menuTimeOfDay,
        null
    ));
    WatchUi.pushView(menu, new SettingsMenuDelegate(), WatchUi.SLIDE_IMMEDIATE);
}

class TimeOfDayInfoView extends WatchUi.View {
    function initialize() {
        View.initialize();
    }

    function onUpdate(dc) {
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();

        var cx = dc.getWidth() / 2;
        var cy = dc.getHeight() / 2;
        var tod = SettingsManager.getTimeOfDay();

        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, cy - 40, Graphics.FONT_SMALL, "Time of Day", Graphics.TEXT_JUSTIFY_CENTER);

        dc.setColor(0x00AAFF, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, cy - 10, Graphics.FONT_MEDIUM, SettingsManager.timeOfDayLabel(tod), Graphics.TEXT_JUSTIFY_CENTER);

        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, cy + 25, Graphics.FONT_XTINY, "Auto-detected from", Graphics.TEXT_JUSTIFY_CENTER);
        dc.drawText(cx, cy + 45, Graphics.FONT_XTINY, "watch clock", Graphics.TEXT_JUSTIFY_CENTER);
    }
}

class TimeOfDayInfoDelegate extends WatchUi.BehaviorDelegate {
    function initialize() {
        BehaviorDelegate.initialize();
    }

    function onBack() {
        WatchUi.popView(WatchUi.SLIDE_RIGHT);
        return true;
    }
}
