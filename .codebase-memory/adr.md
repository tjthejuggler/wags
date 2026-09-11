# ADR: Hour-proximity weighting for By-the-Hour record forecasts

**Date:** 2026-09-11
**Status:** Accepted

## Context
The apnea "chance to beat PB" forecast in By-the-Hour mode fits one OLS regression over ALL records with the hour encoded as sin/cos cyclical features. Breath-hold ability differs strongly between night and morning, so distant-hour records should inform the prediction less than near-hour records.

## Decision
- OlsRegression.fit() now accepts an optional per-observation weights vector (WLS): XᵀWX / XᵀWy / weighted RSS. Null weights preserve plain OLS.
- RecordForecastCalculator computes weights in By-the-Hour mode only: Gaussian kernel on the *circular* hour distance between each record's timestamp hour and the target "Hxx" bucket, sigma = 3.0 hours, floored at 0.05 so distant hours still contribute.
- PB threshold lookup (findBestForSubCombo) remains exact-hour matching; only the probability model is weighted.

## Consequences
- A 07:00 record dominates a 06:00 prediction; a 22:00 record contributes ~5% weight.
- Tunables live in RecordForecastCalculator: HOUR_WEIGHT_SIGMA, HOUR_WEIGHT_FLOOR.