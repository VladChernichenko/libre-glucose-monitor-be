## Traced, no divergence found: `ExperimentService`

`ExperimentService` delegates rather than duplicates, on both paths that matter:

- `:67` takes `calculationsService.activeCobIobInputs(userId, now)` and its COB/IOB calls at
  `:71-72` pass `inputs.carbsEntries()` / `inputs.insulinEntries()` straight through. The loop at
  `:117-118` looks like a second computation but is not: `estimateCleanInMinutes` (:112) receives
  those same shared lists as parameters (`:81`) and only evaluates them at successive future
  timestamps. Same entries, same engine, different `t`.
- `assessBasalForecast` (:417) calls `calculationsService.calculateGlucoseData(req)` (:425) and
  consumes `getPredictionPath()` and `getFourHourPrediction()` from the response. It reads the
  model's output; it does not re-derive it.

## Finding: verification scores the model against a formula that is not the model

**Quantity:** the predicted 2-hour glucose delta used to measure prediction error.

**Paths:**
- `service/VerificationService.java:211` —
  `predictedDelta = (carbs × carbRatio/10) − (insulin × isf)`, a single linear expression over
  the raw `Note` fields. It reads neither `predictionPath`, nor the `factors` block, nor the ODE.
- Everything else that predicts glucose: the Hovorka path, the OpenAPS path, and the `factors`
  block (see `02-factors-block.md`).

`baseline` and `twoHour` come from `findClosestCgm` — real recorded readings (`:191-192`). That
half is correct and expected; comparing a prediction to a real outcome is the point of
verification. The finding is the other operand.

**Why this matters more than a display divergence:** the error feeds auto-titration.
`error = actualDelta − predictedDelta` (:213) → `relError` (:214, aggregated at `:282`) →
`boundedCarbRatioStep(curCR, relError)` (:283) → an accepted suggestion overwrites `carbRatio`,
and `carbRatio` sets `gramsPerUnit = 10 × isf / carbRatio` for every subsequent meal bolus
(`:44-45`, `:63-66`). So the system tunes a dosing parameter to reduce the error of a formula the
user never sees, against a model the formula does not describe. Finding F12
(`2026-08-18-dosing-path-safety.md`) established that `carbRatio` has zero influence on the
Hovorka path — so the loop optimises a parameter that cannot move the prediction it is nominally
correcting.

**Second divergence in the same line — the ISF is the wrong one.** `:204` resolves
`isf = cob.getIsf()`, the base settings value, where every other current path resolves
`getEffectiveIsf(time)` to honour the per-meal-window override. `ai/ContextAggregatorService:100`
does exactly that and carries a comment stating the rule: "no path here may silently substitute
the base ISF where a meal-window value exists". `VerificationService` is the path that still does.
For a user with `isfDinner` set well away from base `isf`, every evening event is scored with the
wrong sensitivity, and the resulting `relError` is biased in one direction — which is the signal
that moves `carbRatio`.

**Disagreement scenario:** 60 g carbs, 6 u insulin, `carbRatio = 2.0`, base `isf = 1.0`,
`isfDinner = 3.0`, dinner-time event. `predictedDelta = (60 × 0.2) − (6 × 1.0) = +6.0 mmol/L`.
Priced with the ISF actually in force it would be `12.0 − 18.0 = −6.0` — opposite sign. The
Hovorka path, which prices carbs by `aG`/`tMaxG` and not `carbRatio` at all, gives a third answer.
Whatever the CGM did, the error attributed to `carbRatio` is drawn from the first of those three.

**Reachability:** live. Drives `carbRatio` titration, which prices every meal bolus. Bounded to
±`MAX_CR_STEP` per acceptance (`:70-77`, the fix from `2026-08-18-dosing-path-safety.md`), so a
single acceptance cannot halve or double the dose — but the bound limits the step, not the
direction, and a consistently biased signal walks the parameter the same way over many
acceptances.

**Confidence:** confirmed-by-reading.
**Status:** open. Re-basing this loop onto the real prediction path is Plan 3 in the
`2026-08-18-dosing-path-safety.md` out-of-scope table, still unwritten; the base-ISF half looks
like a straightforward fix in the same shape as C2 (`9b6ccd4`) and is worth separating from it.
