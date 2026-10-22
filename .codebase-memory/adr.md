# ADR: HRV Readiness Duration Timer Fix + 1/2/4-min Options (2026-10-22)

## Status
Accepted (supersedes part of the pipeline-v2 ADR's "low coverage" hypothesis)

## Context
Follow-up to pipeline v2: the real reason captures had only ~57 beats in a "2-minute" test was not the sensor. The recording loop in ReadinessViewModel polled every 500 ms but decremented `remaining` by 1 s per poll — every test ran at HALF its advertised duration ("2 min" = 60 s actual; "3 min" would have been 90 s; "5 min" 150 s).

## Decision
1. Countdown is now wall-clock based (`SystemClock.elapsedRealtime()`); the 500 ms poll only refreshes the UI. Durations are now exact.
2. Duration options changed to 60 / 120 / 240 s (1/2/4 min) — SHORT is now the true 1-minute test.
3. `daily_readings.sessionDurationSeconds` added (Room v46). ALL legacy rows default to 60 s (every legacy capture used the halved timer, and the user only ever tapped the "2 min" option), so history is treated as if it had been a 1-minute test — keeping old and new data comparable.
4. The quality-gate expected-beats calculation uses the actual duration, so a 1-min test at ~60 BPM expects ~60 beats, not 120.

## Verified
- MorningReadinessTimer uses delay(1000)/-1s — correct, no halving bug there.
- Unit tests still pass; installed on device.
