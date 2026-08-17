# ADR: Section Shortcuts — Exported Trampoline Activities + Static Launcher Shortcuts

**Date:** 2026-08-17 (revised same day after external-launcher requirements)
**Status:** Accepted

## Context
Users need one-tap entry into each of the five top-level sections (Apnea, Morning Readiness, HRV Readiness, Meditation, Resonance Breathing) from two very different callers:

1. **External automation apps** (Tail's Add App picker, Tasker). Tail discovers entries via `PackageManager.getPackageInfo(pkg, GET_ACTIVITIES)` — it lists every **exported, enabled activity** — and launches them with a **bare explicit intent** (component only, no action, no extras, no data). Launcher-app shortcuts are NOT reliably visible to Tail (LauncherApps only serves the default launcher).
2. **Phone launchers** (long-press app icon), where static `<shortcut>` XML entries are the native mechanism.

## Decision
**Primary path — exported trampoline activities** (`com.example.wags.shortcuts`):
- `SectionShortcutActivity` is an abstract base: in `onCreate` it starts `MainActivity` with `ACTION_OPEN_SECTION` + a `section` extra (via `SectionShortcutBus` constants), then `finish()`es. Five concrete one-liner subclasses supply their section id. Fully self-contained — zero intent input required, which is what bare explicit launches demand.
- Manifest per activity: `exported="true"` (mandatory), distinct `label="@string/activity_label_*"` (Tail shows `ActivityInfo.loadLabel()`), `icon` (distinct row icons in pickers), `excludeFromRecents`, `noHistory`, `taskAffinity=""`, and the translucent `Theme.Wags.Shortcut` (no visual flash, no window animation).
- `MainActivity` (singleTask) receives the forwarded intent cold via `onCreate` (guarded by `savedInstanceState == null`) and warm via `onNewIntent`; `SectionShortcutBus` (replay-1 SharedFlow, mirrors `AudioImportBus`) bridges to the Compose NavHost, which navigates with `launchSingleTop`.
- Section ids are semantic (`apnea`, `morning_readiness`, `hrv_readiness`, `meditation`, `resonance_breathing`) and mapped to `WagsRoutes` constants only inside `SectionShortcutBus.routeFor` — manifest and activities stay decoupled from route strings.

**Secondary path — static launcher shortcuts** (`res/xml/shortcuts.xml`, bound via `android.app.shortcuts` meta-data on MainActivity): kept as a bonus for phone launchers and for ⚡ rows in Tail when it is the default launcher. They target MainActivity directly with the same action + extra.

## Verification (2026-08-17, SM-S918U1)
- `aapt2 dump xmltree` on the built APK: all five activities present with `exported=true` and distinct label resources.
- `adb shell am start -n com.example.wags/.shortcuts.MeditationShortcutActivity` (bare explicit intent, exactly Tail's launch mechanism): trampoline forwarded and `MainActivity` became the top resumed activity.

## Consequences
- Adding a section shortcut = icon drawable + strings + manifest `<activity>` + one subclass + one `routeFor` mapping line (+ optional `<shortcut>` entry).
- Trampolines are permission-free and MAIN/LAUNCHER-free, so they never shadow the app's main entry.
- CLI builds require `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` (Android Studio snap JBR is JDK 25, unsupported by Gradle 8.13).
