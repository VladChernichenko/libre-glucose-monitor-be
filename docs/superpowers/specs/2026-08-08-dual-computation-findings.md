# Dual-Computation Divergence Audit — Findings

**Baseline:** `8b5d8c3` (`main`, 2026-08-24). Executed 2026-08-31 from
`docs/superpowers/plans/2026-08-08-dual-computation-audit.md`.

## Summary

**Tracking:** all open findings are filed under the `dual-computation-audit` label; index issue **#32**.

Eleven tasks. **Ten open findings, one retraction, four targets traced clean.** Full suite after
the two test tasks: 1147 tests, 0 failures, 4 skipped.

**A note on this audit's own reliability.** The single finding it inherited from 2026-08-08 was
false, and had been false since the day it was written (`01-isf-fallback.md`). The 2026-08-30
re-baseline diagnosed its *reachability* claim as stale and preserved the underlying divergence —
repeating the original error one level down. Both the finding and the correction were written
from the shape of the code without checking whether the branch in question could execute. Every
finding below states its reachability, and each was checked by executing the greps rather than
reading the plan's predictions. Two of the plan's other predictions were also wrong (Task 2 found
four models where two were expected; Task 8's hypothesised live divergence proved bounded and
latent). Treat "expected at `8b5d8c3`" text in the plan as a hypothesis, never as a result.

### Live — reaches a user or a dosing parameter

| Finding | Where it surfaces |
|---|---|
| **05 A — the AI advisor computes its own 2 h prediction** (`ContextAggregatorService:109`) | Rendered to the patient as `PREDICTION_2H`; correction estimate rendered at priority **high**; both injected into the LLM prompt as ground truth. The defect class `30b76bd` fixed, on a more prominent path. |
| **04 — verification scores the model against a formula that is not the model** (`VerificationService:211`) | Drives `carbRatio` auto-titration → `gramsPerUnit` on every meal bolus. Also the only remaining path using base ISF instead of `getEffectiveIsf`. |
| **03 — ISF titration computes COB from nutrition-stripped entries** (`IsfMealWindowProfileService:299`) | Inflates the derived ISF, which lands in `isf_meal_window_snapshots` and prices correction boluses. |
| **05 B — a second, lossy note→`CarbsEntry` converter** (`ContextAggregatorService:142`) | Advisor COB/IOB diverge from the dashboard's for nutrition-profiled meals. |
| **02 — B-vs-C: two whole prediction implementations behind one flag** | `APP_FEATURES_HOVORKA_MODEL_ENABLED` silently redefines every prediction number. |

### Latent — real duplication, agrees today, nothing enforced

| Finding | Pinned? |
|---|---|
| 02 A — the analytical `factors` block, serialized but unrendered | no (live divergence, not a duplication) |
| 05 C — three constants + one method body duplicated across two services | **yes**, strongly |
| 06 — `PEAK_X3_BASAL` duplicates `1 − F01/EGP0` | **yes**, strongly |
| 07 — four gut rates derived in three places; `1.68` hardcoded at `HovorkaOdeSolver:349` | weakly — strong form needs a refactor this plan forbids |
| 08 — `V_I_SCALE` bridges an OpenAPS IOB curve into Hovorka insulin action | no — nothing to bind it to |

### Resolved after filing — the x₃ / insulin-scale cluster

Finding 06's open question was filed as `needs user input`. Investigating it on 2026-08-31
dissolved the question: the mechanism is numerically inert. Driven through the real
`HovorkaOdeSolver.derivatives()`, a 5 u bolus produces a peak `plasmInsulin` of 0.4640 mU/L
(physiology: 40–100), a peak bolus-driven `x₃` of 0.00019573 against a basal 0.40, and withholds
0.001784 mmol/L across 4.5 h — roughly 56× below the 0.1 mmol/L chart quantum.

The seam at `HovorkaGlucosePredictionService:299-306` is therefore best read not as a modelling
choice but as a **workaround for a scale mismatch**: `x₃_ss = S_IE × I` means `PEAK_X3_BASAL =
0.40` needs `I ≈ 800 mU/L`, so seeding it and letting the ODE run would decay it to ~0.005 within
an hour and let EGP drift back toward `EGP0`. Tracked as #27 (symptom), #31 (root cause,
promoted from latent) and #33 (the blocker on any fix). No finding in this report now requires
user input.

### Retracted

`01-isf-fallback.md` — the ISF Fallback Divergence does not exist and never did.

### Traced, no divergence found

`confidence`; `ExperimentService` (delegates properly on both paths); the two other
`BasalInsulinResolver` holders; the Hovorka warm-up continuity test — the last passing with a
0.10 mmol/L margin and a recorded residual first-step under-shoot.

### Dead code found in passing

`calculatePredictedGlucose:407` (unreachable — a fourth prediction model); both `resolveIsf`
fallbacks and the `settings == null` guard at `HovorkaGlucosePredictionService:694`.

---

## Retracted: ISF Fallback Divergence

> **Status changed 2026-08-31 from `open` to retracted.** The original entry (written
> 2026-08-08 at `6cdfdc6`) claimed the two `resolveIsf` implementations fall back to different
> values, and that the difference reached the user through `predictionTrend`. The 2026-08-30
> re-baseline corrected the *reachability* half of that claim and kept the divergence, calling it
> latent. Executing Task 1 shows the divergence itself is not real, and was not real when the
> finding was written. Retracted in full — not downgraded.

**Quantity:** the insulin sensitivity factor (ISF) used to price insulin's glucose-lowering
effect for a given time window.

**Why there is no divergence:** both implementations delegate to the same resolver and differ
only in a fallback that cannot be reached.

- `service/GlucoseCalculationsService.java:260` — `resolveIsf(settings, time)` returns
  `settings.getEffectiveIsf(time)`, falling back to `DEFAULT_ISF = 1.0` (:39) only when that
  returns `null`.
- `hovorka/HovorkaGlucosePredictionService.java:693` — `resolveIsf(settings, fallbackIsf, time)`
  returns the same `settings.getEffectiveIsf(time)`, falling back to `fallbackIsf` (`pAdj.isf()`)
  only when that returns `null`.

`UserSettingsDTO.getEffectiveIsf(time)` (`dto/UserSettingsDTO.java:87`) returns the meal-window
override when one applies and the stored `isf` otherwise. It returns `null` only if `isf` itself
is `null`, and `isf` is never `null` on any path:

- `db/migration/V1__baseline_schema.sql:44` — `isf DOUBLE PRECISION NOT NULL DEFAULT 1.0`.
- `service/UserSettingsService.java:41` — the no-settings-row case returns
  `new UserSettingsDTO(null, userId, 2.0, 1.0, 45, 240)`, i.e. `isf = 1.0`, not `null`.

So `getEffectiveIsf` never returns `null`, both fallbacks are unreachable dead code, and the two
methods return the identical value for every user at every timestamp. The `settings == null`
guard at `:694` is likewise dead: every call site resolves `settings` from
`userSettingsService.getUserSettings(userId)` (`:137`, `:159`, `:192`, `:211`), which never
returns `null`, and no caller passes a literal `null`.

**Was it ever true?** No. At `6cdfdc6`, the commit that introduced this finding,
`UserSettingsDTO.getEffectiveIsf` was byte-identical to today's (`git show
6cdfdc6:src/main/java/che/glucosemonitorbe/dto/UserSettingsDTO.java`), and
`V1__baseline_schema.sql:44` already declared `isf` `NOT NULL DEFAULT 1.0`. The finding was
written against the *shape* of the two methods without checking whether either fallback was
reachable. That is the same error the 2026-08-30 re-baseline diagnosed in this finding's
reachability claim — committed one level deeper, and missed by the re-baseline too.

**Disagreement scenario:** none exists. A user would need `user_settings.isf IS NULL`, which the
schema forbids.

**Reachability:** n/a — no divergence to reach anything. Note separately that `factors` **is**
still serialized at `GlucoseCalculationsService:214` and is typed but unrendered in
`glucose-monitor-fe/src/services/glucoseCalculationsApi.ts`; whether the analytical model behind
it diverges from the Hovorka path on *other* terms is Task 2's question, and this retraction does
not answer it. Only the ISF term is cleared here.

**Confidence:** confirmed-by-reading, with the historical claim verified against `6cdfdc6`.
**Status:** not-a-bug — retracted, the finding was incorrect as written.

**Residual (worth its own line, not a divergence):** two unreachable fallbacks and one dead null
guard survive in production code, and their presence is what made this finding look plausible
twice. Removing them is a small, safe cleanup outside this plan's no-production-code rule.

---

## Finding: Four implementations of "predicted glucose over the horizon"

**Tracked:** #26 (feature-flag half) and #28 (`factors`-block half).


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

---

## Traced, no divergence found: `confidence`

`GlucoseCalculationsService.calculateConfidence` (:514) has exactly one call site (:149) and no
counterpart anywhere in `src/main` — `grep -rn "calculateConfidence("` returns the declaration
and that one call, nothing else. It is a self-contained heuristic over entry counts and the
current glucose reading. Single source of truth; nothing to reconcile.

## Finding: ISF titration computes COB from nutrition-stripped entries

**Tracked:** #24


**Quantity:** carbs-on-board for a given note at a given time.

Every caller uses the same engine — `CarbsOnBoardService.calculateTotalCarbsOnBoard` — so the
divergence is not in the maths. It is in **what each caller feeds it**, and one caller strips the
fields the maths reads.

**Paths:**
- `service/GlucoseCalculationsService.java:239` (`activeCobIobInputs`) builds entries via
  `convertNoteToCarbsEntry` (:569) → `service/nutrition/NoteToCarbsEntryMapper.toCarbsEntry`
  (:28), which populates `estimatedGi`, `glycemicLoad`, `fiber`, `protein`, `fat`,
  `absorptionSpeedClass`, `absorptionMode`, `suggestedDurationHours` and `patternName` from the
  note's stored `nutritionProfile` JSON, and applies `RescueCarbProfile.mark` to hypo treatments
  (:44-46). Notes come from an 8-hour window (`getRecentNotes:561`).
- `service/IsfMealWindowProfileService.java:299` (`buildCarbsEntry`) builds its own entry with
  `id`, `timestamp`, `carbs`, `insulin`, `mealType`, `originalCarbs`, `userId` and a defaulted
  `absorptionMode` — **and nothing else**. No GI, no macros, no speed class, no suggested
  duration. Notes come from an event-scoped window, `[t0 − 30 min, tEnd]` (:228).

**Why the stripped fields change the number:** `CarbsOnBoardService.calculateRemainingCarbs`
reads exactly those fields — `getSuggestedDurationHours()` (:49) and
`getAbsorptionSpeedClass()` (:54) set `maxDuration`, and `:65` branches on
`"GI_GL_ENHANCED".equalsIgnoreCase(entry.getAbsorptionMode())` to pick the curve. A note carrying
a nutrition profile is therefore modelled as a slow, GI-aware absorption by the dashboard and as
a plain default decay by ISF titration — same note, same engine, different curve.

**Disagreement scenario:** a meal note with a stored nutrition profile setting
`absorptionMode = GI_GL_ENHANCED` and `suggestedDurationHours = 6`. At `tEnd` the dashboard
reports the meal still substantially on board; `IsfMealWindowProfileService` sees a
default-decay entry that has largely cleared, so `cobAtEnd` is lower, `absorbedGrams =
carbsGrams − cobAtEnd` is higher, `deltaCarbMmol` is higher, and
`insulinAttributedDrop = deltaCarbMmol − observedDelta` is inflated. The derived
`isf = insulinAttributedDrop / units` therefore comes out **too high** — the titration credits
insulin with clearing carbs the dashboard says had not yet arrived.

**Reachability:** live and dosing-relevant. The estimate feeds `isf_meal_window_snapshots`
(`V7__isf_meal_window_snapshots.sql`), which `UserSettingsDTO.getEffectiveIsf` reads, which is
what prices a correction bolus. This is not a display divergence.

**Not a defect in the window choice.** The `[t0 − 30 min, tEnd]` scope and the deliberate
`n.isHypoTreatment()` exclusion (:233-236) are correct for the question this service asks —
"what was this bolus's nutrient envelope" is not "what is on board now", and the rescue-carb
exclusion is documented and right. The finding is only that the entries are built by a second,
lossy builder instead of the shared mapper.

**Confidence:** confirmed-by-reading.
**Status:** open.

## Survey: every COB/IOB computation site

| Caller | Lines | Routes through `activeCobIobInputs`? |
|---|---|---|
| `GlucoseCalculationsService` | 129, 132, 140, 141, 297, 298, 321, 322 | owns it (:239) |
| `ExperimentService` | 71, 72 (via `inputs`), 117, 118 (own list) | partly — see `04-experiment-verification.md` |
| `ai/ContextAggregatorService` | 94, 95 | no — see `05-context-aggregator.md` |
| `IsfMealWindowProfileService` | 245 | no — the finding above |

---

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

**Tracked:** #23


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

---

## Finding A: the AI advisor ships its own 2-hour prediction

**Tracked:** #22


**Quantity:** predicted glucose 2 hours ahead, and the correction dose implied by it.

**Paths:**
- `ai/ContextAggregatorService.java:109` —
  `predicted2h = clamp(latest + (activeCob/10)×carbRatio − activeIob×isf + preBolusTimingContribution, 1.0, 25.0)`
- the Hovorka `predictionPath` that produces `twoHourPrediction` and the rendered chart
  (see `02-factors-block.md` for the other three).

That expression is the analytical model `30b76bd` removed from the dashboard headline, still
running in a second service — same `carbRatio` pricing, same `1.0, 25.0` clamp as the dead
`calculatePredictedGlucose:407`. `estimatedCorrectionUnits` (:102) is a second such quantity:
`max(0, (latest − 6.5)/isf − activeIob)`, with the target hardcoded at
`CORRECTION_TARGET_MMOl = 6.5` (:33).

**Reachability: patient-facing, and LLM-facing.** Unlike the `factors` block, which is serialized
but unrendered, this one is displayed and reasoned from:
- `ai/SafetyAndScoringService.java:56` emits it as the `PREDICTION_2H` pattern —
  "Model 2h prediction is X mmol/L."
- `:71` emits `estimatedCorrectionUnits` as `CORRECTION_MATH_GUIDE` at priority **high** —
  "Correction guidance estimate is ~Nu using current settings and active insulin."
- `ai/LlmGatewayService.java:425` and `:461` inject both into the LLM prompt, so the model
  reasons from them as ground truth and can restate them in prose.
- `ai/RagRetrieverService.java:29-30` uses their presence to select retrieved guidance.

So a number that disagrees with the chart is shown to the patient, used to justify a correction
dose, and fed to a language model that will repeat it. This is the defect class `30b76bd` fixed,
surviving on a path that renders more prominently than the one that was fixed.

**Disagreement scenario:** the dashboard chart and the advisor disagree whenever `carbRatio`
misprices the meal, which F12 established is always, since `carbRatio` is inert on the ODE. With
COB decaying 40 g over the horizon and `carbRatio = 2.0`, this model adds `+8.0 mmol/L` from
carbs alone; the ODE's carb rise is set by `aG` and `tMaxG` and is unrelated to that number.

**Confidence:** confirmed-by-reading.
**Status:** open — the most consequential finding in this audit.

## Finding B: a second, lossy note→`CarbsEntry` converter

**Tracked:** #25


`ai/ContextAggregatorService.java:142` (`toCarbsEntry`) builds entries with `id`, `timestamp`,
`carbs`, `insulin`, `mealType`, `comment`, `glucoseValue`, `originalCarbs`, `userId` and the
rescue marker — but **no** `estimatedGi`, `glycemicLoad`, `fiber`, `protein`, `fat`,
`absorptionSpeedClass`, `suggestedDurationHours` or `absorptionMode`, all of which
`service/nutrition/NoteToCarbsEntryMapper:28` populates from the note's stored `nutritionProfile`.
`GlucoseCalculationsService:57` and `GlucosePredictService:93` both inject that shared mapper;
this service does not.

`CarbsOnBoardService.calculateRemainingCarbs` reads exactly those fields (`:49`, `:54`, `:65`),
so a nutrition-profiled meal decays as a plain default entry for the advisor and as a GI-aware
curve for the dashboard — the same divergence as `03-confidence-cob-iob.md`, in a second service.

The javadoc at `:134-140` records a bug this split already caused: the advisor reported a 15 g
rescue still ~11 g on board while the dashboard had it three-quarters absorbed. That was fixed by
adding `RescueCarbProfile.mark` here too (`:154`) — i.e. by patching the copy rather than
adopting the mapper, which is why the nutrition fields are still missing.

**Reachability:** live — `activeCob` (:94) feeds `predicted2h` in Finding A and is displayed.
**Confidence:** confirmed-by-reading. **Status:** open.

## Finding C: duplicated constants and a duplicated method body

**Tracked:** not filed separately — folded into #22 (same service, same fix).


Declared privately in **both** `ContextAggregatorService` and `GlucoseCalculationsService`:
`DEFAULT_CARB_RATIO = 2.0` (:31 / :38), `DEFAULT_ISF = 1.0` (:32 / :39),
`PRE_BOLUS_MAX_TIMING_EFFECT = 1.2` (:35 / :49).

`calculatePreBolusTimingContribution` exists in both (`:189` / `:455`). Diffing the two bodies
with comments and blank lines stripped yields **no differences**: identical thresholds
(10.0 / 25.0), identical coefficients (`0.6 + delta × 0.6`, `delta × 0.6`), identical clamping.

Nothing binds any of these. They agree today; a change to one is invisible to the other.

**Reachability:** latent — the values match at `8b5d8c3`.
**Confidence:** confirmed-by-reading.
**Status:** open (latent — paths agree at `8b5d8c3`, nothing enforces it). Pinned by Task 10.

## Window and ISF resolution

- **Window.** This service selects notes over `[end − windowHours, end]` (`:54`, `:71`), with
  `windowHours` supplied by the caller (`ai/AiInsightService:24, 33, 55`), against
  `activeCobIobInputs`'s fixed 8-hour lookback (`GlucoseCalculationsService.getRecentNotes:561`).
  For `windowHours < 8` the advisor computes COB/IOB from strictly fewer notes than the dashboard
  and will report lower values for the same instant, with nothing reconciling them. Folded into
  Finding B rather than filed separately — it is the same "different inputs, same engine" shape.
- **ISF.** `:99-101` resolves `cob.getEffectiveIsf(end)` with a `DEFAULT_ISF` fallback. The claim
  in its comment holds: this is the window-aware resolver, matching the C2 fix (`9b6ccd4`). Note
  that `VerificationService:204` is now the only path still substituting base ISF
  (`04-experiment-verification.md`). The `DEFAULT_ISF` fallback here is unreachable for the same
  reason as `01-isf-fallback.md`.

## Observed but out of scope

`CORRECTION_TARGET_MMOl = 6.5` (:33) is a hardcoded correction target with no counterpart
constant in `GlucoseCalculationsService`. The time-dependent-ISF final review (C1) found
`glucose-monitor-fe/src/services/carbsOnBoard.ts` using **7.0** for the same purpose. Cross-repo,
so out of scope per the backend-only constraint — recorded, not dropped. The constant name also
carries a typo (`MMOl`).

---

## Finding: EGP suppression is modelled twice and stitched by re-parameterisation

**Tracked:** #27 — reframed 2026-08-31; see also #31 and #33.


**Quantity:** the fraction by which hepatic glucose production is suppressed at time `t`, and the
resulting net EGP.

**Paths:**
- `hovorka/BasalInsulinResolver.java:87` (`suppressionCurve`) — a function of hours since the
  last long-acting note: plateau at `PEAK_X3_BASAL = 0.40` (:41) to 20 h, linear taper to 0 at
  `BASAL_DIA_HOURS = 28.0` (:44). `netEgp` (:108) returns `egp0Abs × (1 − x3Basal)`.
- `hovorka/HovorkaOdeSolver.java:388` — `x3` as a live ODE state,
  `dx3 = −KA3·x3 + KB3·plasmInsulin` (`KA3 = 0.03`, `KB3 = 0.000015`), with
  `egp = p.egp0() × max(0, 1 − x3)` at `:392`.

Both compute `EGP = egp0 × (1 − suppression)`. They disagree about what drives the suppression:
elapsed time since an injection, versus integrated plasma insulin.

**The seam.** `hovorka/HovorkaGlucosePredictionService.java:285-306` does not reconcile them — it
collapses one into the other's parameters. `x3Basal` (:285) produces `egpNow` (:287); the
re-parameterisation at `:299-301` writes `egpNow` into **both** `egpNet` and `egp0`; then `:306`
resets the ODE's own state with `state.withX3(0.0)`.

**The open question.** After the seam, `egp0` is already the *suppressed* value, and `x3`
restarts from zero. A subsequent bolus drives `x3` up, and `:392` then suppresses the
already-suppressed `egp0` a second time. Whether that is intended is genuinely unclear from the
code. The comment at `:294-299` concedes a limitation — "the basal/fasting case is not improved
here… A complete basal EGP bias correction requires a steady-state PK compartment (future work)"
— but that sentence is about the *fasting* case holding steady, and says nothing about
compounding during an active bolus. Per the plan's uncertainty rule I am not guessing which it
is.

**Reachability:** live — `predictionPath` and every headline field read off it.
**Confidence:** confirmed-by-reading (the structure); the intent is not.
**Status:** needs user input — is the post-bolus re-suppression of an already-suppressed `egp0`
deliberate, or an artefact of stitching the two models? A one-line answer from whoever wrote
`49bcb7b` settles it; the code cannot.

## Finding: `PEAK_X3_BASAL` duplicates a derivable quantity, unpinned

**Tracked:** #29


`BasalInsulinResolver:34-35` documents `PEAK_X3_BASAL` as derived: `x3 = 1 − F01/EGP0 =
1 − 0.0097/0.0161 ≈ 0.40`. Recomputed: `0.39751552795…`, so the declared `0.40` is that value
rounded to 2 dp — consistent at `8b5d8c3`.

Three constants encode one relationship (`PEAK_X3_BASAL:41`, `F01_PER_KG:57`, `EGP0_PER_KG:58`)
and **nothing binds them**. The nearest existing test,
`HovorkaOdeSolverTest.basalResolver_suppressionCurve_followsExpectedProfile:191`, asserts
`suppressionCurve(h)` equals `PEAK_X3_BASAL` — self-referential. Change `PEAK_X3_BASAL` to 0.50
and that test still passes; change `F01_PER_KG` and nothing anywhere fails, while the physiology
`PEAK_X3_BASAL` is documented to encode silently stops holding.

**Reachability:** latent — the values agree today.
**Confidence:** confirmed-by-reading.
**Status:** open (latent — paths agree at `8b5d8c3`, nothing enforces it). Pinned by Task 10.

## Traced, no divergence found: the other two `basalResolver` holders

`service/UnloggedEventDetectionService.java:74` and
`service/DigitalTwinCalibrationService.java:72` hold a `BasalInsulinResolver` and pass it into a
prediction-service constructor (`:109`, `:172` respectively). Neither calls
`resolveEgpSuppression` or `netEgp` itself — constructor plumbing, not a third EGP path.

## The counterfactual, from the reference map

The seam exists because there is no basal insulin PK compartment to carry steady-state
suppression, which `:294-299` states outright. The reference map (see the plan's Global
Constraints) names the shape of the resolution: a two-stage subcutaneous depot `S₁ → S₂` with
absorption time constant `τ_S`, feeding `dI/dt = U_I/V_I − k_e·I`, so `x₃` is driven by a
modelled plasma insulin instead of being re-parameterised around. Recorded as the *shape* only —
its parameter values (`τ_S ≈ 40–55 min`, `V_I = 0.12 L/kg`, `k_e = 0.138 min⁻¹`) are unverified
against primary sources and must not be adopted from that document. `08-plasma-insulin.md`
reaches the same absent compartment from the insulin side.

---

## Finding: the effective gut rates are derived in three places, bound by none

**Tracked:** #30


**Quantity:** the effective gastric/intestinal rate constants at a step — `kGriEff`, `kMaxEff`,
`kMinEff`, `kAbsEff` — and the `Ra` computed from them.

`DallaManGutModel` itself is a pure function library with a single definition of each primitive.
The duplication is in its callers, which each re-derive the same four effective values from the
same constants using separately-written expressions.

**Paths:**
- `hovorka/HovorkaOdeSolver.java:348-364` (`derivatives`) — `tHalfMeal = p.tMaxG() × 1.68`,
  `cCal = caloricScale(tHalfMeal)`, `giScale(gi)`, then the three emptying rates, `kAbsEff` from
  `effectiveKAbs(p.tMaxG())`, `kempt`, and `kemptEff = kempt × ilealBrake(inc)`.
- `hovorka/HovorkaGlucosePredictionService.java:586-598` (the warm-up replay) — the same nine
  lines, hand-written, from `activeTMaxG` and `activeGi`.
- `hovorka/HovorkaGlucosePredictionService.java:443-444` — a partial third derivation for
  display: `kAbsDisplay = effectiveKAbs(pStep.tMaxG()) × giScale(state.activeGI())`, then
  `gutModel.ra(state.qgut(), kAbsDisplay)`, shipped as
  `PredictionPointDTO.carbAbsorptionEffect`.

**The deltas, and which are real.** The replay's comment at `:585-586` claims it is "identical to
derivatives(), which derives them from `p.tMaxG()`". Diffing them:

| | `derivatives` | warm-up replay | verdict |
|---|---|---|---|
| half-life factor | bare literal `1.68` (:349) | `HovorkaParameterService.HALF_LIFE_TO_TMAX_G` (:588) | **same value, unpinned** — see below |
| `cCal` / `kAbsEff` source | `p.tMaxG()` | `activeTMaxG` (rescue-aware, :581-593) | **deliberate**, not a defect — the replay integrates *past* meals including rescue carbs, whose `tMaxG` differs per entry. The comment overstates parity. |
| `kEmpt` meal reference | `mealMmol` from `s0.mealMmol()` (:251) | `dRef`, refreshed as `qsto1 + qsto2` (:565) and stored into the state at `:614` | **equivalent** — `dRef` *is* the state's `mealMmol`, maintained manually where `step()` carries it |
| empty-stomach guard | `kEmpt` returns `kMinEff` when `mealMmol ≤ 0` (`DallaManGutModel:80`) | `dRef > 0 ? kEmpt(…) : 0.0` (:596) | **benign** — the two differ, but only when `dRef ≤ 0`, in which case `qsto2 ≈ 0` and `kemptEff × qsto2` is zero either way |

So the four candidate deltas resolve to: one deliberate difference the comment misdescribes, two
non-issues, and one genuine unpinned duplication.

**The unpinned duplication.** `HovorkaParameterService.HALF_LIFE_TO_TMAX_G = 1.68` (:70) is used
at eight sites across the codebase; `HovorkaOdeSolver:349` is the one that hardcodes the literal
instead. Identical today. Change the named constant — a plausible calibration edit — and the ODE
silently keeps integrating at 1.68 while the replay, `MacroNutrientGastricModel`,
`PredictionReplayEngine` and `GlucosePredictService` all move. The two halves of the same
prediction would then disagree about how fast a meal empties, with no test failing.

**Nothing binds the derivations.** `DallaManGutModelTest` asserts the model functions in
isolation (`kEmpt` at `:41-73`, `caloricScale` from `:160`); `GutModelMealTypeTest`,
`HovorkaRescueAbsorptionTest` and `HovorkaGlucosePredictionServiceTest` exercise behaviour
through the public path. None asserts that `derivatives` and the replay produce the same four
rates for the same inputs. Each derivation is free to drift alone.

**Reachability:** `predictionPath` and `PredictionPointDTO.carbAbsorptionEffect`.
**Confidence:** confirmed-by-reading.
**Status:** open (latent — paths agree at `8b5d8c3`, nothing enforces it). Task 10 attempts the
pin; see `10-pinning-tests.md` for whether the strong behavioural form was reachable without the
refactor this plan forbids.

**Obvious follow-up, deliberately not taken here:** extracting one shared helper for the four
rates would collapse all three derivations. That is a production change to the ODE's hot path and
belongs in its own test-first plan, not in an audit.

---

## Finding: plasma insulin is bridged from a foreign PK model by one empirical constant

**Tracked:** #31 — promoted to root cause 2026-08-31; see #27 and #33.


**Quantity:** plasma insulin concentration `I(t)`, which drives `x₁`, `x₂` and `x₃`.

**Paths:**
- `hovorka/HovorkaOdeSolver.java:385` — `plasmInsulin = insulinActivityRate × V_I_SCALE`, with
  `V_I_SCALE = 12.0` (:37) commented "Empirical bridge: mU/L per normalised insulin effect rate
  unit". Feeds `dx3 = −KA3·x3 + KB3·plasmInsulin` (:388), `KB3 = 0.000015` derived as
  `KA3 × S_IE`.
- `insulinActivityRate` accumulates at `hovorka/HovorkaGlucosePredictionService.java:375-382`
  from `iobActivityRate(dose.iobTimeline(), min − 1)`, i.e. from
  `service/InsulinCalculatorService.iobOpenApsExponential:78`, parameterised by `diaHours` and
  `peakMinutes`.
- The Hovorka `S₁ → S₂ → I` subcutaneous PK chain **does not exist in this codebase**.
  `:378` states it: "Full S1->S2->I PK model is deferred; this preserves the IOB
  pharmacokinetics already…".

So the ODE's insulin-action states are driven by an OpenAPS-lineage exponential IOB curve, while
every other coefficient in `derivatives()` (`KA3`, `KB3`, `k12`, `F01`, `EGP0`, `KE1`, `KE2`) is
Hovorka-lineage. Two parameter sets from two different papers meet at one multiplication, and
`V_I_SCALE` is the entire reconciliation. `git log -S V_I_SCALE` returns a single commit,
`49bcb7b` — it was introduced alongside the `x₃` work, with no recorded fit, derivation or
citation beyond the word "empirical".

**Can the two lineages be driven into inconsistency? No — and this is the substantive result.**
`insulinActivityRate` is shaped by `diaHours`/`peakMinutes`, which are **not** free-form user
settings. `UserInsulinPreferencesService.getRapidIobParameters:46-65` reads them from the seeded
`insulin_catalog` via the user's chosen rapid insulin, defaulting to `DEFAULT_RAPID_CODE`,
clamping `peak` below `dia×60/2 − 5`, and falling back to 4.5 h / 75 min. The seeded rapid
entries sit in a narrow physiological band (Fiasp 55 min / 4.5 h; Apidra 75 min / 4.0 h). A user
picks a brand, not a number. There is therefore no reachable configuration that drives
`plasmInsulin` far outside the range `V_I_SCALE` and `KB3` were tuned against together.

**Reachability:** latent. `predictionPath` and every field read off it depend on this bridge, but
no user action can pull the two lineages apart. The risk is a future edit — retuning `KB3` from
Hovorka's table, or swapping the IOB curve — moving one side while the constant that reconciles
them stays put, with nothing failing.

**Confidence:** confirmed-by-reading.
**Status:** open (latent).

**Honest scoping note.** This is the weakest fit to the audited pattern in the report, and it was
added to the plan on the strength of a reference document rather than from the code. The pattern
is *one quantity computed by two independent paths inside the codebase*; here the second path —
the Hovorka PK chain — exists only in the literature. `V_I_SCALE` is strictly a unit conversion
between one implemented model and another, not a competing producer of `I(t)`. Read this as a
**model-fidelity** finding with a magic constant at its centre, not as a dual-computation
divergence. It earns its place because the missing compartment is load-bearing elsewhere: it is
the same absence that forces `06-basal-egp.md`'s seam to re-parameterise `egp0` and reset `x₃`
rather than let `x₃` carry basal suppression. One gap, two symptoms.

---

## Traced, no divergence found — but the margin is thin: warm-up continuity under active IOB/COB

`HovorkaGlucosePredictionServiceTest.activeDoseAndMeal_firstEmittedPoint_noKinkAtWarmupBoundary`
reproduces the 2026-08-08 screenshot's inputs — a 5.0 u correction 82 min before "now", a 10 g
meal 34 min before, `g0 = 6.4` — and asserts the step from the anchor into the first emitted
point is consistent with the curve's own trend one and two steps later.

**It passes.** Measured at `8b5d8c3`:

| | value |
|---|---|
| `g0` (anchor) | 6.4 |
| `p0` (minute 5) | 6.3 |
| `p1` (minute 10) | 6.0 |
| `p2` (minute 15) | 5.7 |
| `stepIntoP0` | −0.10 |
| `localTrend` | −0.30 |
| **gap** | **0.20** (bound: 0.30) |

**Read the pass carefully — it is not an all-clear.**

1. **The margin is 0.10 mmol/L.** The gap is 0.20 against a bound the plan itself called
   "generous". This is not comfortably clean.
2. **The artifact's shape is present, sub-threshold.** The curve is falling at 0.30 mmol/L per
   5-minute step, but the *first* step falls only 0.10 — a third of the rate it settles into
   immediately after. That is the same discontinuity the screenshot showed, an order of magnitude
   smaller. The warm-up boundary is not seamless; it is just no longer dramatic.
3. **The test's resolution is coarse.** `PredictionPointDTO` rounds to 1 dp
   (`Math.round(gAdj × 10.0) / 10.0`), so every measured value is quantised to 0.1 mmol/L and the
   0.20 gap is two quanta. A real kink below ~0.15 mmol/L is invisible to this test regardless of
   the tolerance.
4. **This rules out one input combination, not the phenomenon.** Only 5.0 u @ 82 min + 10 g @
   34 min @ `g0 = 6.4` was tried.

**Do not read this as "the bug never existed."** The ODE was rewired after the screenshot was
taken — `62dd077` changed the x3 bridge to be driven by insulin activity rather than an ISF
quotient, and `0a3c32c` reconciled ISF wall times with UTC CGM epochs. A pass here is at least as
consistent with "the kink was fixed in passing" as with "it was never there." Establishing which
would need a checkout at `6cdfdc6` and the same probe, which is outside this plan.

**Confidence:** confirmed by a passing test with recorded values.
**Status:** not-a-bug at `8b5d8c3` for the inputs tried — with the residual first-step
under-shoot above recorded as the thing to re-measure if the symptom is reported again.

---

## Pinning tests

`src/test/java/che/glucosemonitorbe/hovorka/DuplicatedConstantPinningTest.java`. Four assertions,
all **green** at `8b5d8c3` — they pin the status quo, they do not report bugs. Full suite after
adding them: **1147 tests, 0 failures, 0 errors, 4 skipped**.

| Pin | Binds | Finding | Strength |
|---|---|---|---|
| `peakX3Basal_matchesItsDerivation` | `PEAK_X3_BASAL` ↔ `1 − F01_PER_KG/EGP0_PER_KG` | `06-basal-egp.md` | **strong** — behavioural over the actual relationship; `within(0.005)` covers only the documented 2 dp rounding (0.39752 → 0.40) |
| `duplicatedConstants_agreeAcrossServices` | `DEFAULT_CARB_RATIO`, `DEFAULT_ISF`, `PRE_BOLUS_MAX_TIMING_EFFECT` across `GlucoseCalculationsService` and `ContextAggregatorService` | `05-context-aggregator.md` C | **strong** — exact equality, read reflectively from both private declarations |
| `preBolusTimingContribution_identicalAcrossServices` | the two `calculatePreBolusTimingContribution` bodies | `05-context-aggregator.md` C | **strong** — behavioural equality over 10 inputs crossing every branch (`null`, 0, 5, 9.9, 10, 20, 25, 25.1, 45, 100) |
| `halfLifeToTMaxG_pinnedAtItsPublishedValue` | `HALF_LIFE_TO_TMAX_G == 1.68` | `07-gut-rates.md` | **weak — value-only.** See below. |

### Why the gut-rate pin is weak, and what it would take to strengthen it

The strong form would assert that `HovorkaOdeSolver.derivatives` and the warm-up replay produce
the same four effective rates for identical inputs. It is **not reachable** from a test:

- `HovorkaOdeSolver.derivatives` (:326) is package-private, so a test in
  `che.glucosemonitorbe.hovorka` *can* call it.
- The replay's derivation (`HovorkaGlucosePredictionService:586-598`) is inline inside the
  private `buildWarmState` (:483). There is no seam to call it through, and creating one is a
  production change to the ODE warm-up path, which this plan's no-refactor rule forbids.

So the pin degrades to asserting the named constant still equals `1.68`. That catches the
realistic failure — someone retunes `HALF_LIFE_TO_TMAX_G` and the ODE's hardcoded literal at
`HovorkaOdeSolver:349` silently stays behind — but it does **not** catch the ODE's literal being
changed instead, and it does not verify the two derivations agree on anything else.

**To close it properly:** extract the four-rate derivation into one package-private helper called
by both `derivatives` and `buildWarmState`, then assert equality through it. That is the
`07-gut-rates.md` follow-up, and it collapses the duplication rather than merely observing it.

### Not pinned

- **`06-basal-egp.md`, the EGP double-suppression question.** Status `needs user input` — there is
  no correct behaviour to pin until someone says whether post-bolus re-suppression of an
  already-suppressed `egp0` is intended. Pinning current behaviour here would freeze a possible
  bug.
- **`08-plasma-insulin.md`, `V_I_SCALE = 12.0`.** Nothing to bind it *to*: the constant's
  counterpart is a PK compartment that does not exist in the codebase, and its provenance is
  recorded only as "empirical". A value-only pin would assert `12.0 == 12.0`.
- **Findings A and B of `05-context-aggregator.md`, and findings 02, 03, 04.** These are live
  divergences, not agreeing duplications. A test that pins them would pin the defect. They need
  fixes, which is what the tracked issues are for.

---

## Coverage cross-check — against the design spec's §3 target list

| Spec target | Covered by | Result |
|---|---|---|
| `GlucoseCalculationsService` | 01, 02, 03, 05 | 3 findings + 1 retraction |
| `HovorkaGlucosePredictionService` | 01, 06, 07, 09 | 2 findings + 1 clean trace |
| `BasalInsulinResolver` | 06 | 1 finding + 1 needs-user-input + 1 clean trace |
| `DallaManGutModel` | 07 | 1 finding |
| `CarbsOnBoardService`, `InsulinCalculatorService` | 03, 05 | 2 findings (in their callers, not the engines) |
| `ExperimentService` | 03, 04 | traced clean |
| `predictionPath[0]` continuity | 09 | traced clean, thin margin |

No spec target is untraced.

## Coverage cross-check — by quantity

The spec's list is a list of *files*, which is how it missed three live targets. Re-checked
against the quantities a complete glucose-insulin model contains:

| Quantity | Producers | Verdict |
|---|---|---|
| `G(t)` / predicted delta | 4 | finding 02 |
| `EGP(t)`, `x₃` | 2 | finding 06 |
| `F₀₁,c` | 1 — `HovorkaParameters.f01clamped:77`, one caller (`HovorkaOdeSolver:341`) | clean |
| `F_R` renal clearance | 1 — `HovorkaOdeSolver:345` | clean |
| `x₁`, `x₂` insulin action on transport/utilisation | **0 — not implemented.** Only `x₃` exists | not a divergence; a documented model simplification |
| `I(t)` plasma insulin | 1 implemented + 1 bridged-from-foreign | finding 08 |
| `S₁`, `S₂` subcutaneous depot | 0 — deferred (`HovorkaOdeSolver:378`) | finding 08 |
| `Q_sto1/2`, `Q_gut`, `k_empt` | 1 engine, 3 caller derivations | finding 07 |
| `U_G` / `Ra` | 1 engine, 2 caller derivations (incl. display-only `:443`) | finding 07 |
| `Inc` GLP-1 / ileal brake | 1 — `ilealBrake:119` and `dIncDt:109` are `public static` and, per the comment at `:117`, **deliberately shared with the warm-up replay** | clean — and see below |
| `t½`, caloric density, Elashoff β | 1 — `MacroNutrientGastricModel`, `caloricScale` | clean |
| GI scaling | 1 — `HovorkaOdeSolver.giScale` | clean |
| `f` bioavailability | 1 — applied once in `DallaManGutModel.ra`, with javadoc at two sites warning against double-application | clean |
| COB / IOB | 4 callers, 2 with their own entry builders | findings 03, 05 B |
| ISF | 1 resolver (`getEffectiveIsf`), 1 path still on base ISF | finding 04; see retraction 01 |
| `τ_pause` pre-bolus | 1 — `PreBolusResolver` | clean (iOS parity is cross-repo, out of scope) |

**The `Inc` row is the most useful line in this table.** `ilealBrake` and `dIncDt` were made
`public static` *specifically* so `derivatives` and the warm-up replay share one definition — the
comment at `HovorkaOdeSolver:117` says so. The codebase already knows how to solve the problem
finding 07 describes, and applied that solution to two of the terms in the very same block while
leaving the four rate constants duplicated. That makes 07 an oversight rather than a design
choice, and points at its fix.

## Scope addendum (2026-08-31)

Two services in the dual-computation blast radius are **not** in the design spec's §3 list,
because they did not call these APIs when it was written on 2026-08-08:

- **`ai/ContextAggregatorService`** (finding 05) — traced in depth by Task 5. Its findings are the
  most consequential in this report; do not read the spec's list as exhaustive on the strength of
  this one having been outside it.
- **`IsfMealWindowProfileService`** (finding 03) — traced by Task 3.

`ai/SafetyAndScoringService`, `ai/LlmGatewayService` and `ai/RagRetrieverService` were read only
far enough to establish reachability for finding 05 A. They were not themselves audited.

## Observed but out of scope

- `glucose-monitor-fe/src/services/glucosePrediction.ts` carries its own independent
  carb/insulin-contribution prediction. A real instance of the pattern; frontend, so out of scope
  per spec §3.
- `CORRECTION_TARGET_MMOl = 6.5` (`ContextAggregatorService:33`) against the frontend's `7.0`.
  Cross-repo.
- `FeatureToggleConfig:28` comments the Hovorka flag "off by default" while `application.yml:152`
  defaults it to `true`. Documentation/config contradiction, not a dual computation.
