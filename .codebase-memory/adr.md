# Fix: Min Breath Forecast 100% Bug

## Context
The "chance to beat" percentage in the Min Breath screen was incorrectly showing 100% even when a record already existed for the current settings combination.

## Root Cause Analysis

### Issue 1: Insufficient Data Path
In [`RecordForecastCalculator.insufficientDataForecast()`](app/src/main/java/com/example/wags/domain/usecase/apnea/forecast/RecordForecastCalculator.kt:284-346), when there were fewer than `MIN_TOTAL_RECORDS` total records (insufficient data for regression), ALL categories were hardcoded to 100% probability regardless of whether a record existed for that category.

### Issue 2: Regression Path (Main Bug)
In [`RecordForecastCalculator.compute()`](app/src/main/java/com/example/wags/domain/usecase/apnea/forecast/RecordForecastCalculator.kt:61-195), for parameterized drills like Min Breath:
- **`regressionRecords`** included ALL Min Breath records (across different session durations)
- **`pbRecords`** was filtered to only include records with the SAME session duration

The regression model was trained on mixed-duration data but compared against same-duration records. When the model predicted better performance (because it learned from longer sessions), the probability calculation `P(X > record)` approached 100%.

## Decision

### Fix 1: Insufficient Data Path
Changed probability calculation in `insufficientDataForecast()` to:
- 100% when no record exists for the category (any hold sets a new PB)
- 50% when a record exists (neutral estimate, since we can't compute a proper probability without sufficient data)

### Fix 2: Regression Path
Changed the record filtering logic to use only same-drillParam records for both regression training and PB lookups when `drillParam != null`:

```kotlin
// For parameterized drills, only use same-param records for both regression
// and PB lookups. Training on mixed-param data makes predictions unreliable
// when compared against same-param PB thresholds.
val sameParamRecords = if (drillParam != null) {
    records.filter { it.drillParamValue == drillParam }
} else {
    records
}

val regressionRecords = sameParamRecords.filter { it.durationMs >= MIN_DURATION_MS }
val pbRecords = regressionRecords
```

This ensures the regression model is trained on the same subset of data that PB comparisons are made against, making predictions meaningful.

## Impact
- Min Breath screen now shows accurate "chance to beat" percentages
- Users with existing records will no longer see misleading 100% forecasts
- The change affects both the "insufficient data" and regression code paths
- Free Hold (which passes `drillParam = null`) continues to work correctly as before
- Progressive O₂ (also parameterized) benefits from the same fix

## Date
2025-06-18