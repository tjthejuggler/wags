# ADR: Local time semantics — freeze at save time, never re-derive history

**Date:** 2026-11-21
**Status:** Accepted & implemented (DB v47, deployed, backfilled on-device: 1320 rows, 0 tod-mismatches)

## Context
After travelling Turkey (UTC+3) → Malaysia (UTC+8), an apnea breath hold done in the 07:00 hour reported "never done one with these settings" despite abundant history. Root cause had two prongs:
1. **SQL path:** hour-bucket filters (`TimeBucketSql`) derived each record's hour via SQLite `strftime(..., 'unixepoch', 'localtime')`. Android app processes have no `TZ` env var, so SQLite 'localtime' resolves to **UTC** — a "constant global time" that never matched the app's local-time semantics.
2. **Kotlin path:** `TimeBuckets.hourOfTimestamp` / `Instant...atZone(ZoneId.systemDefault())` re-derived *stored* records' hour/day using the **current** device zone — silently re-bucketing all history on every timezone change. Same flaw affected day-level grouping in morning readiness, HRV readiness, meditation, resonance, RF assessments, rapid-HR, apnea history/trophy/time charts, and the Tail habit export (`HabitBackfillManager`).

214 of the last 264 days were spent outside the home zone, so mis-bucketing was extensive but unnoticed (both prongs agreed while at home in Turkey, UTC+3 year-round).

## Decision
1. **Freeze local time at save time.** Every session-ish table (`apnea_records`, `apnea_sessions`, `meditation_sessions`, `resonance_sessions`, `morning_readiness`, `daily_readings`, `rf_assessments`, `rapid_hr_sessions`) stores `zoneId` (IANA zone at save). `apnea_records` additionally stores `localHour` (0–23 of the session-start timestamp in that zone) because hour-bucket SQL needs a comparable column. Stamping happens at repository save choke points via `LocalTimeZones.currentZoneId()`.
2. **Read frozen values.** SQL hour filters use `COALESCE(NULLIF(localHour,-1), <UTC-hour>)` — no SQLite 'localtime' anywhere. Kotlin record-bucketing uses `TimeBuckets.fromRecord/hourOfRecord(ts, localHour)`. Day grouping / display timestamps use each row's own `zoneId` with the current zone only as fallback (`LocalTimeZones.zonedDateTimeOf`).
3. **"Now" stays current-zone.** Current-session bucket (`TimeBuckets.current()`), "today" boundaries, and window edges describe the present moment and keep using the current zone.
4. **Historic repair from Tail travel history.** `assets/local_time_zones.txt` (229 date→IANA-zone intervals, 2004→present) generated from the companion Tail app's daily GPS history (timezonefinder on per-day coords, gap days inherit nearest recorded day, default zone Europe/Istanbul). `LocalTimeBackfillRunner` (one-shot, SharedPreferences-guarded, registered in WagsApplication) stamped every pre-fix row; validated by cross-checking derived Morning/Day/Night against the historically-frozen `timeOfDay` column — 538/538 records agreed.
5. `TimeBuckets.hourOfTimestamp/fromTimestamp` remain for "now"-semantics only and are documented as such.

## Consequences
- The reported bug case (H07 + FULL + NO_PREP free holds) went from 3 → 13 matching records; PBs/trophies/forecasts are stable across timezone changes forever.
- Travel-day records are exact because Tail's location history is per-day precise; days missing from the seed default to the home zone.
- Future schema work: never derive bucket columns from `timestamp` + current zone; always read the frozen `zoneId`/`localHour`.
- Seed regeneration script (workspace-local, deleted after use) pulled Tail prefs via `run-as com.example.tail`; keep that technique for future seed refreshes after long trips.