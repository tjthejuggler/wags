# ADR: Settings screen reorganized into collapsible category cards

## Context
The wags SettingsScreen had grown into a 1,588-line monolith: a flat LazyColumn of ~12 unrelated cards (device, Garmin, apnea, scan results, meditation dir, Spotify, Tail habits, backup, crash logs, debug, advice, about) with no grouping, titled "Device Settings" although it contained far more.

## Decision
Reorganize the settings screen into collapsible category cards, inspired by the tail project's SettingsCategory pattern but styled with the wags design language (SurfaceDark + 1.dp CardBorder outline cards, titleMedium SemiBold headers, KeyboardArrowUp/Down chevron, AnimatedVisibility expand/shrink — same visual grammar as the apnea DrillCard/CollapsibleSectionHeader and the dashboard NavigationCard).

Six categories, collapsed by default (content only composed while expanded, rememberSaveable state):
- 📡 Sensors & Devices (connected sensor, Garmin watch, nearby-scan; header summary shows live connection state)
- 🫁 Apnea (hyper cooldown stepper + existing ApneaVibrationSettingsSection)
- 🔗 Integrations (Spotify, Tail habits, meditation audio folder)
- 💾 Data & Backup (export/import)
- 💬 Advice (per-section advice rows)
- 🐛 Developer (debug bubble, crash logs)
About stays a footer button. Screen retitled "Settings".

## File layout (split from the monolith, all in ui/settings/)
- SettingsCategoryCard.kt — SettingsCategoryCard + SettingsSubSectionDivider + SettingsSubSectionLabel
- SettingsDeviceSections.kt — ConnectedDeviceSection, GarminWatchSection, NearbySensorsSection
- SettingsIntegrationsSections.kt — MeditationAudioDirectorySection, SpotifySection
- SettingsTailSection.kt — TailAppIntegrationSection + habit picker dialog
- SettingsDataSections.kt — DataExportImportSection, CrashLogsSection, DebugModeSection, AdviceSettingsSection
- SettingsScreen.kt — screen scaffold, launchers, dialogs, category composition

Sub-sections are plain Columns/Rows inside the category card (no nested Cards), separated by thin SurfaceVariant dividers. SettingsViewModel, all routes, and the SettingsScreen public signature are unchanged; behavior is feature-identical to before.

## Consequences
- New settings get added as a sub-section inside the matching category file instead of growing the screen file.
- The collapsed-by-default layout keeps the screen scannable; live sensor status is visible in the category summary without expanding.
