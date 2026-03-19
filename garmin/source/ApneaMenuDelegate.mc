using Toybox.WatchUi;

//! Handles selection from the Apnea sub-menu (Free Hold | Settings).
class ApneaMenuDelegate extends WatchUi.Menu2InputDelegate {

    function initialize() {
        Menu2InputDelegate.initialize();
    }

    function onSelect(item) {
        var id = item.getId();
        if (id == :menuFreeHold) {
            var view = new FreeHoldView();
            WatchUi.pushView(view, new FreeHoldDelegate(view), WatchUi.SLIDE_LEFT);
        } else if (id == :menuSettings) {
            pushSettingsMenu();
        }
    }

    function pushSettingsMenu() {
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
        WatchUi.pushView(menu, new SettingsMenuDelegate(), WatchUi.SLIDE_LEFT);
    }

    function onBack() {
        WatchUi.popView(WatchUi.SLIDE_RIGHT);
    }
}
