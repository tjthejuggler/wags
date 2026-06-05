ADR: Drill forecast must filter records by drillParamValue + ceilingMs for bounded drills

Date: 2026-06-01 (updated)

Context: The record-breaking forecast (RecordForecastCalculator) predicts P(next hold > PB) using OLS regression on historical records. For drill types (Min Breath, Progressive O₂), records from different drill parameter values (session duration / breath period) have fundamentally different duration distributions. A 5-minute Min Breath session naturally produces longer total hold times than a 2-minute session.

Additionally, Min Breath has a physical ceiling: total hold time cannot exceed the session duration. If a user achieves 100% hold time, the probability of beating that record is 0% — not "insufficient data".

Problem (Part 1): ApneaViewModel and ProgressiveO2ViewModel were passing ALL records of the drill type to RecordForecastCalculator without filtering by drillParamValue. This meant a 2-minute Min Breath session's forecast was computed against records from 5-minute sessions, making the PB threshold impossibly high and the probability absurdly low (e.g. 7% after a perfect session).

Problem (Part 2): After fixing Part 1, filtering by drillParamValue reduced the record count below MIN_TOTAL_RECORDS (5), causing "insufficient data" even when the user had achieved 100% hold time — which should definitively show 0%.

Decision: 
1. Filter records by drillParamValue before passing to RecordForecastCalculator, matching the pattern already used in MinBreathViewModel.
2. Add a `ceilingMs` parameter to RecordForecastCalculator.compute(). When the global best record equals or exceeds the ceiling, return a definitive 0% forecast via `ceilingReachedForecast()` — bypassing the MIN_TOTAL_RECORDS requirement. In the main probability loop, categories whose PB hits the ceiling also get 0%.
3. Min Breath callers pass `ceilingMs = sessionDurationSec * 1000L`. Free hold and Progressive O₂ pass no ceiling (null).

Consequences: Forecast probabilities for drill types now reflect only records with the same drill parameter. When 100% hold time is achieved, the forecast correctly shows 0% instead of "insufficient data".