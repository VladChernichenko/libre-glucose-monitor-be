# Parameter Audit — Findings

**Audited at** `3734f74` · **Date** 2026-08-18

## Scope and method

Every parameter that can influence a predicted glucose value, a dose, or a recommendation, across
both prediction pipelines:

- **Dashboard** — `POST /api/glucose-calculations/` → `GlucoseCalculationsService` →
  `HovorkaGlucosePredictionService`. This is the forecast, COB/IOB and trend the user sees.
- **Predict** — `POST /api/predict` → `GlucosePredictService` → `MacroNutrientGastricModel` +
  `HovorkaGlucosePredictionService`.

Structural claims were settled by tracing the call path from HTTP entry to
`HovorkaOdeSolver.derivatives` and to the emitted `PredictionPointDTO`. Numeric claims were settled
by **perturbation**: each parameter was set to two plausible extremes and the emitted curves
compared. Bit-identical output is the death certificate — no amount of code reading substitutes for
it, and three parameters in this audit look live in the source while being provably dead.

Probes were throwaway JUnit classes, run and deleted; the numbers survive, the scaffolding does not.

**Harness state.** Docker up; full suite **1002 tests, 3 failures**. All three are
`Nightscout URL host could not be resolved` — an SSRF guard performing real DNS resolution from
inside `UserDataSourceConfigServiceTest` and `NightscoutCgmIntegrationTest`. Unrelated to the model,
but recorded as F16.

**Reference.** The verdict matrix treats the supplied model description as a set of claims to
confirm or refute, not as a specification to judge.

---

## 1. Claim verdict matrix

| # | Claim | Dashboard | `/api/predict` | Ref |
|---|---|---|---|---|
| 1.1 | Body weight drives V_G and F_01 | **CONFIRMED** (maxΔ 3.00) | CONFIRMED | — |
| 1.2 | ISF sets insulin effect strength | **PARTIAL** — `settings.isf` drives it (maxΔ 8.40); the twin-calibrated `params.isf` is inert (maxΔ 0.10) | same | F3 |
| 1.3 | tMaxG is the base absorption-speed setting | **CONFIRMED** (maxΔ 1.30), but from `carbHalfLife` alone | CONFIRMED, macro-derived instead | F6 |
| 1.4 | EGP₀ auto-computed from `BASAL_CHECK` | **REFUTED** — ×0.5 vs ×5 bit-identical | REFUTED | F1 |
| 2.1 | Carbs drive the gut input | **CONFIRMED** (maxΔ 2.10) | CONFIRMED | — |
| 2.2 | Protein/fat set caloric density → slow emptying | **PARTIAL** — no caloric-density path exists; they act only through the GLP-1 brake (maxΔ 0.10 / 0.20) | **CONFIRMED** — fat 0→60 g moves tMaxG 27.2→104.7 min | F6 |
| 2.3 | Fiber reduces intestinal absorption via viscosity | **REFUTED** — 0 g vs 25 g bit-identical | **CONFIRMED** — tMaxG 53.9→88.9 min | F2 |
| 3.1 | Bolus time and dose drive the IOB curve | **CONFIRMED** (maxΔ 11.50; DIA 2.20, peak 2.30) | CONFIRMED | — |
| 3.2 | Long-acting insulin models EGP suppression | **PARTIAL** — timing live (maxΔ 3.50), **dose size dead** (6 u vs 40 u bit-identical) | same | F4 |
| 4.1 | Current CGM initialises Q₁/Q₂ | **CONFIRMED** (maxΔ 7.90) | CONFIRMED | — |
| 4.2 | Recent history: stomach residue + active insulin | **CONFIRMED** (meal age maxΔ 2.90) | CONFIRMED | — |
| 5.1 | Curve horizon up to 300 minutes | **REFUTED** — 240 default, 480 ceiling; no 300 anywhere | REFUTED | F7 |
| 5.2 | Optimal pre-bolus pause returned | **REFUTED** — endpoint never returns it | **CONFIRMED** — `preBolusMinutes` | F8 |
| 5.3 | Bolus-type recommendation (square/dual wave) | **REFUTED** | **PARTIAL** — `SQUARE_WAVE` at fat ≥ 20 g; the pattern table's `Dual Wave` is unreachable from the note path | F9 |
| 5.4 | Refined GI and GL from the food matrix | **PARTIAL** — GI live (maxΔ 1.30); **GL bit-identical** | same | F5 |
| 5.5 | GLP-1 incretin response and ileal brake | **CONFIRMED** — bounded since `3734f74`; effect now small | CONFIRMED | — |

---

## 2. Parameter ledger

`ODE?` = does perturbing it change the emitted curve. `D` = dashboard, `P` = `/api/predict`.

### 2.1 Individual physiological parameters

| Parameter | Declared | Written by | Default / fallback | ODE? D | ODE? P | Measured |
|---|---|---|---|---|---|---|
| `bodyWeightKg` | `user_settings.body_weight_kg` | Settings UI | 70 kg | yes | yes | maxΔ 3.00 (55↔100 kg) |
| `vG` | derived `0.16 × weight` | — | — | yes | yes | via weight |
| `f01` | derived `0.0097 × weight` | — | — | yes | yes | via weight |
| `isf` | `user_settings.isf` | Settings, `ISF_ONE_UNIT` | 1.0 (col), 2.2 (`HovorkaParameterService`) | yes | yes | maxΔ 8.40 |
| `isfBreakfast/Lunch/Dinner/Night` | `user_settings.*` | Settings, `IsfMealWindowSuggestionService` | null → `isf` | yes | yes | maxΔ 4.90 |
| `params.isf` (twin-scaled) | `HovorkaParameters.isf` | `applyScales(isfScale)` | 1.0× | **near-dead** | near-dead | maxΔ 0.10 → F3 |
| `carbHalfLife` → `tMaxG` | `user_settings.carb_half_life` | Settings | 45 min | yes | overridden | maxΔ 1.30 |
| `egpNet` | `HovorkaParameters.egpNet` | `estimateEgpNet` (`BASAL_CHECK`) | `f01` | **DEAD** | DEAD | ×0.5↔×5 identical → F1 |
| `egp0` | `HovorkaParameters.egp0` | `applyScales(egpScale)` | `0.0161 × weight` | **DEAD** | DEAD | ×0.5↔×5 identical → F1 |
| `aG` | `HovorkaParameters.aG` | `applyScales(agScale)` | 1.00 | yes | yes | maxΔ 1.10; 0.7/1.0/1.3 → 1.25/1.65/2.03 mmol·L⁻¹/10 g |
| `k12`, `k21` | `K12_POP`, `K21_POP` | constant | 0.066 | yes | yes | maxΔ 0.80 |
| `G_THRESHOLD` | `HovorkaParameters` | constant | 4.5 | yes | yes | gates F01 below 4.5 |
| `isfScale` | `user_digital_twin` | `DigitalTwinCalibrationService` | 1.0 | **near-dead** | near-dead | → F3 |
| `agScale` | `user_digital_twin` | `DigitalTwinCalibrationService` | 1.0 | yes | yes | live via `aG` |
| `egpScale` | `user_digital_twin` | `DigitalTwinCalibrationService` | 1.0 | **DEAD** | DEAD | → F1 |
| `tMaxGScale` | `user_digital_twin` | `DigitalTwinCalibrationService` | 1.0 | **DEAD** | DEAD | never read → F10 |

### 2.2 Nutrition (БЖУК)

| Parameter | Declared | Written by | Default / fallback | ODE? D | ODE? P | Measured |
|---|---|---|---|---|---|---|
| `carbs` | `notes.carbs` | Note create/edit | 0 | yes | yes | maxΔ 2.10 (20↔80 g) |
| `protein` | `nutrition_profile` JSON | scan / manual entry | null → 0 | weak | **yes** | D maxΔ 0.10; P tMaxG 27→105 min → F19 |
| `fat` | `nutrition_profile` JSON | scan / manual entry | null → 0 | weak | **yes** | D maxΔ 0.20; P as above |
| `fiber` | `nutrition_profile` JSON | scan / manual entry | null → 0 | **DEAD** | yes | D identical; P tMaxG 53.9→88.9 → F2 |
| `estimatedGi` | `nutrition_profile` JSON | enricher / scan | 70 (ODE), 55 (COB), 55 (pre-bolus), 0 (patterns) | yes | yes | maxΔ 1.30 → F11, F20 |
| `glycemicLoad` | `nutrition_profile` JSON | enricher | null → 0 | **DEAD** | DEAD | 0↔80 identical → F5 |
| `carbRatio` | `user_settings.carb_ratio` | Settings, `VerificationService` | 2.0 | **DEAD** | DEAD | 1.0↔4.0 identical → F12 |
| `absorptionMode` | `notes.absorption_mode` | create / enricher | null → `DEFAULT_DECAY` | no | no | gates COB only |
| `absorptionSpeedClass` | `nutrition_profile` | enricher | null → 240 min COB | no | no | COB window only |
| `suggestedDurationHours` | `nutrition_profile` | pattern matcher | null → 240 min | indirect | indirect | sets path length |
| `patternName`, `bolusStrategy`, `mealSequencingPriority` | `nutrition_profile` | pattern matcher | null | no | no | → F9 |
| `K_GRI` / `K_MAX` / `K_MIN` / `K_ABS` / `F` / `B` / `D_LOW` | `DallaManGutModel` | constant | Dalla Man 2007 | yes | yes | — |
| `BASE_T_HALF_MIN`, `caloricScale` | `DallaManGutModel` | constant | 45 min, clamp [0.4, 1.5] | yes | yes | 1.0 at default half-life |
| `BETA_CARBS/PROTEIN/FAT` | `MacroNutrientGastricModel` | constant | 1.05 / 1.60 / 2.20 | **unreachable** | yes | β 1.21→1.86 → F6 |
| `EMPTYING_*`, `DEFAULT_MEAL_VOLUME_ML` | `MacroNutrientGastricModel` | constant | 9.0, 27.5, 250 mL | **unreachable** | yes | → F6 |
| `FIBER_VISCOSITY_K` | `MacroNutrientGastricModel` | constant | 0.02 | **unreachable** | yes | → F2 |
| `K_PF_DRAIN`, `K_M_PF`, `K_INC_PF`, `K_DEL`, `KAPPA_GLP1` | `HovorkaOdeSolver` | constant | 0.008, 200, 0.020, 0.020, 1.0 | yes | yes | bounded since `3734f74` |

### 2.3 Insulin therapy

| Parameter | Declared | Written by | Default / fallback | ODE? D | ODE? P | Measured |
|---|---|---|---|---|---|---|
| bolus units | `notes.insulin` | Note create/edit | 0 | yes | yes | maxΔ 11.50 (0↔8 u) |
| bolus timestamp | `notes.timestamp` | Note create/edit | — | yes | yes | drives IOB phase |
| `diaHours` | `user_insulin_preferences` | Insulin catalog | 5.0 | yes | yes | maxΔ 2.20 (3↔7 h) |
| `peakMinutes` | `user_insulin_preferences` | Insulin catalog | 75 | yes | yes | maxΔ 2.30 (45↔120) |
| `effectiveInsulinVolume` | `HovorkaParameters` | derived `2 × vG` | — | yes | yes | restores ISF in G |
| long-acting **timing** | `notes.timestamp` (type `long_acting`) | Note create | — | yes | yes | maxΔ 3.50 (10 h↔26 h) |
| long-acting **dose** | `notes.insulin` | Note create | — | **DEAD** | DEAD | 6 u↔40 u identical → F4 |
| `PEAK_X3_BASAL`, `BASAL_DIA_HOURS`, `WANE_START_HOURS` | `BasalInsulinResolver` | constant | 0.40, 28 h, 20 h | yes | yes | via timing |
| `KA3`, `KB3`, `V_I_SCALE` | `HovorkaOdeSolver` | constant | 0.03, 1.5e-5, 12.0 | yes (weak) | weak | sole path for `params.isf` → F3 |
| `KE1`, `KE2` (renal) | `HovorkaOdeSolver` | constant | 0.003, 9.0 | yes | yes | maxΔ 9.00 (G 7↔16) |
| `gramsPerUnit` | derived `10 × isf / carbRatio` | — | refuse outside 3–30 | n/a | n/a | **dosing only** → F12 |

### 2.4 Warm start and state

| Parameter | Declared | Written by | Default / fallback | ODE? D | ODE? P | Measured |
|---|---|---|---|---|---|---|
| `currentGlucose` | request | CGM / manual | — | yes | yes | maxΔ 7.90 (4.0↔12.0) |
| meal history window | `getRecentNotes` | — | 8 h | yes | yes | maxΔ 2.90 (10↔300 min) |
| long-acting window | `getLongActingNotes` | — | 36 h | yes | yes | — |
| meal age cap | `buildWarmState` | — | 480 min | yes | yes | — |
| `tauMinutes` (interstitial lag) | `InterstitialLagModel` | **system property**, mutable static | 12 min | yes | yes | → F14 |
| `MAX_CORRECTION`, `SHRINK_K`, `GLOBAL_SHRINK_K` | `ResidualBiasModel` | constant | 2.5, 12, 30 | yes | n/a | clamps learned bias |
| `RESIDUAL_RAMP_MINUTES` | `HovorkaGlucosePredictionService` | constant | 30 | yes | n/a | — |
| `GAIN_INSULIN`, `GAIN_INDEP`, `TAIL_HALF_LIFE_MIN`, `WARMUP_MINUTES` | `ActivityModulation` | constant | 1.0, 0.005, 120, 360 | only with activity notes | no | — |

### 2.5 Outputs

| Output | Produced by | Dashboard | `/api/predict` | Ref |
|---|---|---|---|---|
| prediction curve | both | yes, 240 min (480 for HFHP) | yes | F7 |
| emission step | `DENSE_STEP_MIN` / `SPARSE_STEP_MIN` | 5 min ≤ 4 h, then 10 | same | — |
| `G_MIN` / `G_MAX` clamp | `HovorkaGlucosePredictionService` | 1.0 / 25.0 | same | — |
| 2 h / 4 h / 8 h headline | `GlucoseCalculationsService` | yes | no | F18 |
| `preBolusMinutes` | `PreBolusResolver` | **no** | yes | F8 |
| `bolusStrategy` | `MacroNutrientGastricModel` | **no** | yes | F9 |
| `tMaxGUsed`, `betaWeighted` | `MacroNutrientGastricModel` | **no** | yes | F6 |
| `estimatedMealGi` / `estimatedMealGl` | `summarizeNutrition` | display only | — | F5 |
| `predictionTrend` | `determineTrend` | yes — from the **linear** formula, not the curve | — | F12 |
| `confidence` | `calculateConfidence` | yes — from list sizes only | — | F15 |
| `predictedGlucoseLower/Upper` | nothing | **always null** | always null | F17 |

---

## 3. Findings register

### S1 — could mislead a dosing decision

**F12 · `carbRatio` is dead in the forecast, live in dosing, and the only knob titration moves.**
*Category: Divergent.* Perturbing `carb_ratio` 1.0 ↔ 4.0 leaves the dashboard curve bit-identical —
it has zero references anywhere under `hovorka/`. It nonetheless drives
`InsulinCalculatorService.resolveGramsPerUnit` (`10 × isf / carbRatio`,
[InsulinCalculatorService.java:265](../../../src/main/java/che/glucosemonitorbe/service/InsulinCalculatorService.java#L265)),
`determineTrend`, and `VerificationService`'s `predictedDelta`. The titration loop therefore measures
the error of a linear formula the user never sees, then applies the correction to every meal bolus.
Each accepted suggestion scales it by `clamp(1 + relError, 0.5, 2.0)`, and `gramsPerUnit` is
inversely proportional — so one acceptance can halve or double every subsequent dose, bounded only
by the wide 3–30 g/U refusal envelope. Meanwhile the chart's own carb magnitude comes from an
unreconciled chain (`aG`, `F`, `V_G`) whose implied coefficient is 2.00 at 55 kg but 1.25 at 100 kg,
against a flat stored 2.0.
*Resolution: make `carbRatio` a derived view of the model's carb magnitude, and titrate `aG`.*

### S2 — visibly wrong forecast a user might act on

**F6 · The dashboard has no caloric-density path at all.**
*Category: Divergent.* `MacroNutrientGastricModel` is called only from `GlucosePredictService` and
`PredictionReplayEngine`. For a 50 g carb / 20 g protein meal, `/api/predict` derives tMaxG from
27.2 min (0 g fat) to 104.7 min (60 g fat); the dashboard uses **26.79 min for every meal**, derived
from `carbHalfLife` alone. `BETA_CARBS/PROTEIN/FAT`, the Calvert coefficients and
`DEFAULT_MEAL_VOLUME_ML` are unreachable from the endpoint the user actually looks at.

**F2 · Fiber cannot affect the dashboard forecast.**
*Category: Dead (dashboard) / Divergent.* 0 g vs 25 g of fiber is bit-identical. `getFiber()` has no
reader under `hovorka/`; COB reads it only inside the `GI_GL_ENHANCED` branch, which a manually
entered meal never enters because `macroNutritionProfileJson` emits no `absorptionMode`. On
`/api/predict`, `FIBER_VISCOSITY_K` moves tMaxG 53.9 → 88.9 min over the same range.

**F4 · Long-acting insulin dose size is ignored; only its age matters.**
*Category: Wrong.* 6 u vs 40 u of basal is bit-identical.
[BasalInsulinResolver.java:66](../../../src/main/java/che/glucosemonitorbe/hovorka/BasalInsulinResolver.java#L66)
uses `note.getInsulin()` solely as a `> 0` gate, then returns `suppressionCurve(hoursAgo)` — a flat
`PEAK_X3_BASAL = 0.40` for the first 20 h regardless of dose. A patient who halves their basal, or
switches from 6 u to 40 u, sees an identical forecast. Timing is live (maxΔ 3.50).

**F11 · The default GI of 70 is a 30 % brake, and four subsystems disagree on it.**
*Category: Wrong.* `giScale = clamp(gi/100, 0.3, 1.5)` multiplies `k_gri`, `k_max`, `k_min` and
`k_abs`; neutral is GI 100. Every meal without a GI estimate therefore absorbs at 0.7× the Dalla Man
calibration. The fallback is 70 in the ODE, 55 in `CarbsOnBoardService`, 55 in the pre-bolus tiers,
and **0** in `GlycemicPatternMatchingService` — where null is read as the *lowest possible* GI and
silently matches `Slow Climb`.

**F18 · iOS labels the 8 h value as the "4h forecast".**
*Category: Wrong.* [ContentView.swift:536](../../../../glucose-monitor-iphone/GlucoseMonitor/ContentView.swift#L536)
reads `calc?.predictionPath?.last?.predictedGlucose`, but the backend extends the path to 480 min for
HFHP / Double-Wave meals (`resolvePathDurationMinutes`). The response carries explicit
`fourHourPrediction` and `eightHourPrediction` fields; the client ignores both. So exactly for the
meals with a long delayed tail — the ones where the distinction matters — the label is wrong.

**F19 · Food-scan mode discards manual macro corrections.**
*Category: Wrong.* [NotesView.swift:709](../../../../glucose-monitor-iphone/GlucoseMonitor/NotesView.swift#L709)
branches `if let snap = snapshot { snapshotToNutritionProfileJson(snap) } else if protein > 0 ...`.
`prefillFromSnapshot()` seeds the sliders from the scan, the user corrects protein 24 → 40 g, and
save re-serialises the *untouched* snapshot. Only carbs survives, because it rides the top-level
column — which also desyncs `notes.carbs` from `nutrition_profile.totalCarbs` and leaves
`glycemicLoad` computed for the pre-edit amount. The comment directly above claims it "always merges
manual macros"; it branches instead.

### S3 — silently degraded accuracy

**F1 · `egpNet`, `egp0`, and the twin's `egpScale` are all dead.**
*Category: Dead.* Scaling either field ×0.5 or ×5 leaves the curve bit-identical.
`buildWithParams` recomputes `egp0Abs` from the population constant × weight and overwrites both
fields on `pAdj`. `HovorkaParameterService.estimateEgpNet` — the `BASAL_CHECK` estimator — therefore
computes a value that is discarded, and `applyScales`'s javadoc claiming `egpScale` "now flows into
the live ODE" is false.

**F3 · The twin's `isfScale` is effectively dead; the raw dosing ISF is used instead.**
*Category: Dead.* `params.isf` 1.0 ↔ 4.4 moves the curve by only 0.10 mmol/L, and that residual runs
purely through `plasmInsulin` → `x3` EGP suppression
([HovorkaOdeSolver.java:300](../../../src/main/java/che/glucosemonitorbe/hovorka/HovorkaOdeSolver.java#L300)),
not through the insulin effect. `resolveIsf` falls back to the twin-calibrated value only when
`getEffectiveIsf` returns null, and `MealWindow` partitions all 24 hours, so it never does. Every
forecast prices insulin at `user_settings.isf` (maxΔ 8.40).

**F5 · Glycemic load is computed everywhere and read nowhere that matters.**
*Category: Dead.* 0 ↔ 80 is bit-identical in both pipelines. Its only causal use is two threshold
checks in `GlycemicPatternMatchingService` (`Fast Spike gl_min 20`, `Flat Plateau gl_max 10`), and
that service is injected only into `ARNutritionService` and `LogMealService` — never into
`NotesService.enrichNutrition`. For any note saved through the normal path, GL influences nothing.

**F9 · Pattern matching never runs on the note path, and its rules are mutually exclusive.**
*Category: Dead / Wrong.* `patternName`, `bolusStrategy`, `suggestedDurationHours` and
`preBolusPauseMinutes` stay null for text-enriched and manually entered notes. Where it does run,
[GlycemicPatternMatchingService.java:130](../../../src/main/java/che/glucosemonitorbe/service/nutrition/GlycemicPatternMatchingService.java#L130)
makes fiber and the FPU tiers exclusive — any meal with ≥ 5 g fiber can match *only* `Blunted Curve`,
so a pizza with 6 g fiber gets 4 h/Normal instead of 8 h/Dual Wave. And `Fast Spike` needs GI ≥ 70
**and** GL ≥ 20, i.e. ≥ 28.6 g of carbs, so a 15 g juice correction can match no pattern at all.

**F10 · `tMaxGScale` is persisted, calibrated, clamped — and never read.**
*Category: Dead.* `DigitalTwinCalibrationService` fits and stores it; `applyScales` deliberately
skips it. A column and a fitted parameter with no consumer.

**F13 · `suggestedIsf` is unreachable.**
*Category: Dead.* Set to `null` at `VerificationService:190` and `:248` and assigned nowhere, so the
`if (getSuggestedIsf() != null)` branch in `acceptSuggestion` can never fire. The DTO field and the
"Carb Ratio Refinement" UI both imply an ISF half that does not exist.

**F17 · The uncertainty band is dead end to end.**
*Category: Dead.* `PredictionResidualProvider.uncertaintySdMmol` has **zero call sites**, and nothing
in `src/main` ever sets `predictedGlucoseLower` / `predictedGlucoseUpper`. iOS decodes both fields
and passes them into `PredictionChartPoint`, where they are always nil.

**F15 · `confidence` measures list length, not confidence.**
*Category: Wrong.* `calculateConfidence` reads only `carbsEntries.size()`, `insulinEntries.size()`
and whether glucose is extreme. It is unaffected by the twin's fitted uncertainty, by residual
magnitude, or by how stale the CGM reading is.

**F20 · GI is averaged across food names, not weighted by carbs.**
*Category: Wrong.* `estimateGiFromFoods` returns `sum / foods.size()`, so "white bread and lettuce"
yields (75 + 15)/2 = 45 although the bread carries essentially all the carbohydrate. `glycemicLoad`
inherits the same error, being `gi × carbs / 100`. `GlucoseCalculationsService:561` meanwhile *does*
carb-weight its display average — so the GI shown and the GI stored are computed by different rules.

**F21 · Fat and protein slow absorption through two channels on the dashboard, three on `/api/predict`.**
*Category: Wrong.* `applyFatProteinDampening` subtracts up to 20 GI units *before* the value is
stored, which then cuts `giScale` and with it all four gut rate constants. The same fat and protein
independently drive `protFatGut` → the GLP-1 ileal brake. On `/api/predict`, `computeTMaxG` applies
them a third time through the Elashoff betas. The dampening also breaks the classifier that consumes
it: a high-GI meal with 30 g of fat drops from GI 75 to 66 and so falls below `Fast Spike`'s
`gi_min` of 70.

### S4 — cosmetic and documentation

**F7 · No 300-minute horizon exists.** 240 min default, 480 ceiling, 5/10-min emission. Either the
claim is aspirational or it refers to a version not in this tree.

**F8 · The dashboard never returns a pre-bolus pause.** `preBolusMinutes` exists only on
`PredictResponse`. `computePreBolusPause` is reachable only through pattern matching (see F9).

**F14 · `InterstitialLagModel.tauMinutes` is mutable global state.** A `public static volatile`
parsed from a system property at class-init, shared across all users and requests.

**F16 · Three tests depend on live DNS.** `Nightscout URL host could not be resolved` — an SSRF
guard resolving `ns.example.com` from inside a unit test. The only failures in the suite.

**Stale documentation.** `HovorkaParameters:8` lists `carbRatio` among values "derived from
experiments and settings" (it reaches nothing); `HovorkaParameterService.applyScales` claims
`egpScale` "now flows into the live ODE" (F1); `VerificationEvent:45` documents
`carbs_g × carbRatio` where the code applies `/10`.

---

## 4. Unresolved

**Screenshot-4 behaviour.** A dashboard reading 9.0 now with a 2 h headline of 9.3, whose curve
decayed to ~4.3 with IOB 0.00. Neither pipeline reproduces this: with those inputs the model rises
monotonically, and the residual bias is clamped at ±2.5 mmol/L. *Would be settled by* the raw
`/api/glucose-calculations/` response body for that request.

**Whether the 300-minute horizon (F7) is aspirational.** *Would be settled by* the user confirming
whether the description targets a planned horizon or an older build.

**Whether `agScale` and `carbRatio` ever agree in production.** Both estimate carb magnitude from
outcomes, independently. *Would be settled by* comparing fitted `agScale` against titrated
`carb_ratio` for real users over the same window.
