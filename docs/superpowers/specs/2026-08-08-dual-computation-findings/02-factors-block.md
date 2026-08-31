## Finding: Four implementations of "predicted glucose over the horizon"

**Quantity:** the predicted glucose delta from now to the horizon, and the path of points the
chart renders.

**Paths:** the plan expected two. There are four.

- **A — the analytical `factors` model.** `service/GlucoseCalculationsService.java:362`
  (`calculatePredictionFactors`) computes `carbContribution = ((COB − futureCOB)/10) × carbRatio`
  and `insulinContribution = −((IOB − futureIOB) × ISF)`. `baselineContribution` is hardcoded
  `0.0` (:380) and `trendContribution` is `DEFAULT_GLUCOSE_TREND × hours` with
  `DEFAULT_GLUCOSE_TREND = 0.0` (:40), so two of its five terms are structurally zero and the
  model reduces to carb + insulin + pre-bolus timing. Serialized whole at `:214`.
- **B — the OpenAPS exponential path.** `GlucoseCalculationsService:294-329`, the else-branch of
  the private `buildPredictionPath`, labelled in-code as the "OpenAPS exponential model
  (default)". A complete second implementation that emits `predictionPath` itself, priced with
  `userCarbRatio` (:295) and a velocity-blend term absent from the ODE.
- **C — the Hovorka ODE.** `hovorka/HovorkaGlucosePredictionService.buildPredictionPath`, taken
  when `featureToggleConfig.isHovorkaModelEnabled()` (:277).
- **D — dead.** `calculatePredictedGlucose:407`, called only at `:181` inside the
  `predictionPath.isEmpty()` branch. That branch is unreachable: `resolvePathDurationMinutes`
  (:621) returns `max(PREDICTION_PATH_MINUTES, …)` so `pathMinutes ≥ 240`, and both B and C emit
  a point per 5-minute step. A fourth model of the same quantity that never runs.

**B and C are mutually exclusive and selected by configuration.** `FeatureToggleConfig:28`
declares `hovorkaModelEnabled = false`, but `application.yml:152` sets
`hovorka-model-enabled: ${APP_FEATURES_HOVORKA_MODEL_ENABLED:true}` — so the ODE is live in a
normal deployment, and setting `APP_FEATURES_HOVORKA_MODEL_ENABLED=false` swaps every
user-visible prediction number to a different model with nothing reconciling the two. The audit's
working assumption that "the chart is the Hovorka path" holds for the default deployment only.

**Disagreement scenario:** A and C disagree by construction. `carbContribution` is priced with
`carbRatio`, which finding F12 (`2026-08-18-dosing-path-safety.md`) proved has *zero* influence
on the ODE — 1.0 vs 4.0 is bit-identical on the Hovorka path. So for any meal with COB decaying
over the horizon, doubling `carbRatio` doubles A's carb term and leaves C's curve unmoved. The
ISF term no longer diverges (see `01-isf-fallback.md`), but the carb term is a free parameter on
one side and inert on the other. A-vs-B disagree the same way in magnitude but not in kind, since
B also prices carbs with `carbRatio`.

**Reachability:** A is serialized at `:214` on every response and typed in
`glucose-monitor-fe/src/services/glucoseCalculationsApi.ts`, but no frontend code renders it —
latent. B-vs-C is live-by-configuration: it silently redefines `twoHourPrediction`,
`fourHourPrediction`, `eightHourPrediction`, `predictionTrend` and the rendered chart. D is
unreachable.

**Confidence:** confirmed-by-reading.
**Status:** open. A is latent; B-vs-C is a live configuration-selected divergence; D is dead code
documenting a fifth opinion. Splitting or retiring these is out of scope here.

## Observed but out of scope

- `FeatureToggleConfig:28` comments the Hovorka flag as "Off by default - toggle on
  per-environment after validation", while `application.yml:152` defaults it to `true`. Code
  comment and shipped configuration disagree about which prediction model is the default. Not a
  dual-computation finding; a documentation/config divergence worth one line to whoever owns the
  flag.
