# ADR: Launcher App Shortcuts for Top-Level Sections

**Date:** 2026-08-17
**Status:** Accepted

## Context
Users need one-tap access from the launcher to each of the five top-level sections: Apnea, Morning Readiness, HRV Readiness, Meditation, and Resonance Breathing. Navigation is a single-Activity Compose NavHost (`WagsNavGraph`) with `MainActivity` in `singleTask` launch mode.

## Decision
- Use **static shortcuts** declared in `res/xml/shortcuts.xml` (bound via `android.app.shortcuts` meta-data on `MainActivity`), one per section. Each shortcut intent uses action `com.example.wags.action.OPEN_SECTION` with a semantic `section` extra (`apnea`, `morning_readiness`, `hrv_readiness`, `meditation`, `resonance_breathing`).
- Route intents through a new `SectionShortcutBus` (ui/navigation) that mirrors the existing `AudioImportBus` pattern: `MutableSharedFlow(replay = 1)` bridges Activity intent handling to the nav graph, guaranteeing delivery on cold starts before composition collects.
- `MainActivity.handleSectionShortcut()` is called from `onCreate` (guarded by `savedInstanceState == null` to avoid re-triggering on recreation) and from `onNewIntent` (warm starts). The bus maps section ids to `WagsRoutes` constants (`APNEA_FREE`, `MORNING_READINESS`, `READINESS`, `MEDITATION`, `BREATHING`) so manifest shortcut ids stay decoupled from route strings.
- Navigation uses `launchSingleTop = true` to avoid duplicate destinations.

## Consequences
- Adding a new section shortcut requires: drawable icon + strings + `<shortcut>` entry in shortcuts.xml + one mapping line in `SectionShortcutBus.routeFor`.
- Shortcut ids in the manifest are semantic; route renames only touch Kotlin.
