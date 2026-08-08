# Dual-Computation Divergence Audit — Findings

## Finding: ISF Fallback Divergence (affects predictionTrend)

**Quantity:** the insulin sensitivity factor (ISF) used to price insulin's glucose-lowering
effect for a given time window, when no manual per-meal-window override is set.

**Paths:**
- `service/GlucoseCalculationsService.java:241` — `resolveIsf(settings, time)`, falls back
  to hardcoded `DEFAULT_ISF = 1.0` mmol/L/U when no override applies. Feeds
  `calculatePredictionFactors` -> `factors.insulinContribution` -> `determineTrend` ->
  the `predictionTrend` field ("rising"/"falling"/"stable") still shipped in every response.
- `hovorka/HovorkaGlucosePredictionService.java:506` — `resolveIsf(settings, fallbackIsf,
  time)`, falls back to the caller-supplied `fallbackIsf`, which is always `pAdj.isf()` —
  the user's autotuned/calibrated ISF from `HovorkaParameterService`. Feeds the actual
  `predictionPath` array (the chart).

**Disagreement scenario:** a user with autotuned ISF far from 1.0 (per project memory,
the 1800-rule ISF commonly runs ~2x the model's calibrated ISF, so calibrated values well
above or below 1.0 are the norm, not the exception) and no manual per-meal-window override
set. The chart (Hovorka path) prices every unit of insulin at their real ISF; the
`predictionTrend` label is computed as if ISF were 1.0. For a user with e.g. calibrated
ISF=3.0, a 2u dose is priced as -6.0 mmol/L of effect on the chart but only -2.0 mmol/L in
the trend formula — the trend label can say "stable" or even "rising" while the chart the
label sits next to is falling sharply, or vice versa.

**Confidence:** confirmed-by-reading.
**Status:** open.
