## ADR: Debug Bubble Feature

**Date:** 2026-04-22
**Status:** Implemented

### Decision
Added a floating debug bubble overlay that allows users to annotate bugs, features, or notes on any screen. The bubble is draggable, shows a badge indicating notes on the current screen, and persists notes to `debug_wags.json` with source file context for programmer LLMs.

### Architecture
- **DebugPreferences** (`data/debug/`) — SharedPreferences-backed toggle + file path setting, exposed as StateFlow
- **DebugNoteRepository** (`data/debug/`) — In-memory note tracking + JSON file I/O using org.json (no extra deps)
- **ScreenContextMapper** (`data/debug/`) — Maps navigation routes to source file + function hints
- **DebugBubbleOverlay** (`ui/debug/`) — Draggable Compose overlay with badge indicator
- **DebugNoteDialog** (`ui/debug/`) — Note entry dialog with Bug/Feature/Note type selector
- **DebugModeCard** in SettingsScreen — Toggle + file path configuration

### Key Design Choices
- Used Compose overlay (not system overlay window) to avoid SYSTEM_ALERT_WINDOW permission
- Route-to-source mapping is static (ScreenContextMapper) — no runtime reflection needed
- JSON output uses org.json (already on Android) instead of kotlinx.serialization (not in project)
- Bubble visibility is driven by DebugPreferences StateFlow — reactive, no service lifecycle needed