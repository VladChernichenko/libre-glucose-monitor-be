# Meal absorption (Gamma kernel) and the carb-advice guard - design

Date: 2026-11-20. Scope: backend (`glucose-monitor-be`). No iPhone change is required.

## Intent

After a meal the forecast drops too low, and the app then tells the user to eat fast carbs they do
not need. On 2026-11-19 the user took an 8 U Fiasp pre-bolus at 19:30 and ate 98 g over the next
1.5 h. The CGM stayed at 6.6-8.3 mmol/L until midnight, but every stored forecast went to
1-3 mmol/L. Twice the carb-advice card said "take carbs now", and the user ate 30 g and 10 g at
5.4 and 6.2 mmol/L because of it.

The amounts were right: 98 g against 8 U at 10 g/U nets out to about +1.4 mmol/L, which is what
happened. The timing is wrong. The model releases a meal's glucose early and then leaves a thin
tail that runs past the 4 h horizon. Fiasp acts mostly 1.5-3 h after the dose. In that window the
model has too little glucose coming in, so it draws a low.

This project does two things. It stops a forecast-only low from telling the user to eat while a
meal is still being absorbed. And it replaces the meal absorption shape with a Gamma kernel whose
peak time and width are set separately, fitted against real post-meal CGM with the insulin curves
the forecast actually uses.

## Evidence (2026-11-20 investigation)

- `forecast_snapshot` (the dashboard forecast frozen at each anchor note), user
  `963266ad-…`, 2026-11-01 onward, 89 snapshots. The bias (predicted − actual) is negative for
  every anchor type. After meals it is −3.0 mmol/L at 60 min and −4.2 at 120 min. MAE is about
  equal to |bias|, so the error is mostly systematic. 4 of 23 meal anchors forecast < 3.9 at
  120 min. No real low followed any of them.
- The forecast replayed through `HovorkaGlucosePredictionService` (working tree with
  `MealGutChains`), with every 19 Nov note known at 19:30:

  | min after bolus | 60 | 120 | 180 | 240 |
  |---|---|---|---|---|
  | actual | 6.9 | 6.6 | 8.2 | 7.6 |
  | model | 7.5 | 7.2 | 4.5 | 3.0 |

- Dalla Man appearance for 30 g at the default 45-min half-life: the peak comes at 14-28 min.
  Only 53% (GI 30), 73% (GI 50) and 84% (GI 70) of the glucose appears within 4 h. Every slowing
  lever (low GI, fiber, a longer half-life, the fat/protein brake) scales `K_MIN` down and pushes
  mass past the horizon. A 90-min half-life made the forecast *lower*.
- The Fiasp curve moved to 120 min / 5 h on 2026-11-18 (commit `a9820be`). That removed the
  early dip and moved the low to 2.5-4 h. Meal bias at 120 min after the change: −4.8 (n = 5).
- Dinner ISF 2.76 (against 1.5 in the other windows) scales both the carb and the insulin terms
  by 2.76. Any timing mismatch is amplified by the same factor.

## Goals

1. After a meal, a forecast low that the CGM does not confirm never produces a "take carbs now"
   card, on the dashboard or on the watch.
2. A real low after a meal still gets the card before the CGM reaches 3.9 mmol/L.
3. The forecast's post-meal bias at 60 and 120 min is within ±1.0 mmol/L on the evaluation set,
   down from −3.0 / −4.2.
4. The absorption shape has separate, interpretable controls: peak time and width. All of a
   meal's glucose appears within a bounded time.

## Non-goals

- The COB number (`CarbsOnBoardService`) keeps its own model. It also feeds the meal-window ISF
  estimates, so changing it changes ISF suggestions. That needs a separate project. Known
  mismatch kept for now: for rescue carbs, COB reaches zero at 45 min, while today's gut model
  still holds 36% at 60 min.
- Sizing a meal from GL (`A = η·GL`). In type 1 diabetes a low-GI meal is slower, not smaller.
  Amplitude stays carbs × `aG` × meal-window factor, as today.
- Fixing how fiber is counted on labels. Catalog carbs include fiber, while EU Open Food Facts
  carbs exclude it. Doses are priced on the logged carbs, and the forecast must stay consistent
  with them. Logged as a follow-up.
- Stopping Open Food Facts from lowering GI for fat and protein (the kernel counts them again).
  `CarbsOnBoardService` reads both that GI and the speed class derived from it, so removing the
  lowering would change COB and the ISF suggestions. The kernel is fitted on the GI as stored, so
  the double count is absorbed by the fit. Follow-up, together with the COB project.
- The `carb_half_life` setting in the forecast. It keeps driving COB. The Gamma path ignores it:
  the setting was defined for the exponential model, and per-user timing comes from the twin.
- A mixture of fast, slow and delayed kernels per meal. v1 uses one kernel per logged entry.
  Revisit only if the evaluation residuals show a second peak that one kernel cannot follow.
- Refitting the insulin curve on meal data. Carb timing and insulin timing trade off on the same
  meals. Fitting both on meals is how the 18 Nov change moved the low instead of removing it.
  The insulin curve stays the catalog curve, or the personal curve fitted on corrections only.
- New card states or any change to how the iPhone draws the curve.
- Guards on the dose-withholding paths (`MealPlanService` `FORECAST_LOW`,
  `PreBolusPauseOptimizer`'s 4.5 floor). A false low makes them more cautious, not less. They
  improve when the forecast does.

## Anti-goals

- A real low that loses its carb advice, or gets it only after the CGM is already under 3.9.
- A forecast that rises after meals where it used to fall, swapping false lows for false highs.
  The meal bias target is two-sided.
- A per-user fit that drifts unchecked. Personal scales apply only when they beat the population
  model on held-out data, as the twin already requires.
- Two definitions of the same absorption in two code paths: forecast and calibration replay must
  share one kernel.

## Constraints

- Prerequisite: the per-meal gut seam. `MealGutChains` plus `HovorkaOdeSolver.stepWithAppearance`
  precompute each meal's appearance and feed it to the glucose ODE. In the working tree on
  2026-11-20 this was uncommitted and not written by this design's author. It came with a
  ~440-line rewrite of `HovorkaGlucosePredictionService`, changes to `HovorkaOdeSolver`, and a
  new `lateProteinRise` field on `GlucoseCalculationsResponse`. It must be committed (or replaced
  by an equivalent seam) before work starts. The Gamma kernel plugs in behind it.
  Resolved 2026-11-20: the user chose to review and commit it as it stands, in its own commits
  ahead of this project (the `lateProteinRise` field separately from the gut seam).
- The calibration replay (`PredictionReplayEngine`) calls the same
  `HovorkaGlucosePredictionService.buildPredictionPath`, so the twin fits against whatever the
  forecast uses.
- Real-user CGM/notes exports contain health data: never committed. Harnesses are data-gated and
  skip when the data is absent, like `BacktestHarnessTest` and `Azt1dCalibrationValidationTest`.
- Datasets available: AZT1D (25 T1D subjects, carbs and boluses, no macros, automated pumps),
  CGMacros (45 non-insulin subjects with carbs, protein, fat and fiber per meal), and the dev DB
  (this user has 93 meals of ≥ 10 g with nutrition profiles since 2026-07-26, plus the
  `forecast_snapshot` rows).

## Approaches considered

1. **Guard plus Gamma (chosen).** A CGM-confirmation guard in carb advice stops the harm now and
   stays as a safety net. A Gamma kernel then fixes the shape at the source.
2. **Gamma only.** It removes false lows only if the fit is good, and until then the advice keeps
   telling the user to eat. Rejected: the harm continues meanwhile, and nothing catches an
   imperfect fit.
3. **Repair Dalla Man.** Stop GI, fiber and fat from scaling `K_MIN`, cap the tail, and fit one
   width scale. A smaller change, and it keeps the portion-size nonlinearity. Rejected: peak and
   width still move together, which is the failure the dinner showed.

The user chose 1 on 2026-11-20.

## Design

### Part 0 - Evaluation harness (built first; both parts are judged by it)

A data-gated test harness, extending `backtest/BacktestHarness`, that replays a user's exported
`notes` and `cgm_readings` (CSV, not committed):

- Anchors: every note that `ForecastSnapshotService` would anchor (rapid dose > 0, carbs ≥ 10 g,
  hypo treatment), plus every 15 min within 4 h after a carb note of ≥ 10 g.
- The model replayed is the raw physiology with no digital twin (`PredictionResidualProvider.NONE`,
  neutral scales). This user's twin is currently not applied ("no improvement out-of-sample"), so
  for this user the raw model is also what the live forecast showed. Per-user scales are judged
  separately in rollout step 4.
- Two replay modes, each judging different things:
  - **As logged.** The forecast at each anchor sees only what was logged by then, as the live
    forecast did. Judges the advice metrics, because this is what the user acts on. Meals logged
    in several notes make the early anchors low whatever the kernel does. That is the guard's
    job, not the kernel's.
  - **Full knowledge.** Every note within the next 2 h is known at the anchor (dose and the
    meals it covers count as one decision). Judges bias and MAE, because this measures the
    absorption model.
- Reports, split into meal / pre-bolus / correction / fasting anchors:
  - bias and MAE at 60, 120 and 240 min (full knowledge);
  - **false advice:** the trigger fires and the CGM stays ≥ 4.5 for the next 60 min (as logged);
  - **missed or late advice:** the CGM goes < 3.9 within 60 min and the trigger did not fire at
    least 15 min before it; report the lead time (as logged).
- Data split: everything fitted or tuned (GI and macro coefficients on real-user meals, guard
  thresholds) uses data before 2026-11-01. Success is judged on 2026-11-01 onward, the
  `forecast_snapshot` period.
- Real lows for Goal 2: this user has 14 meals of ≥ 10 g followed by a CGM reading < 3.9 within
  4 h since July (11 before 2026-11-01, 3 after). Add AZT1D meal-context anchors with their real
  lows. Automated insulin is included as logged; the episode-exclusion rules of the scenario
  suite do not apply to this metric.
- Baselines, both recorded in the PR: the per-meal Dalla Man model (`MealGutChains`, the
  prerequisite below) with today's trigger, which is what the new work is compared against.
  Also the shared-gut model of the 2026-11-19 snapshots, as the reference for what the user saw.

The same metrics run on AZT1D meal-with-bolus episodes, with the episode rules of
`2026-11-13-azt1d-forecast-scenarios-design.md`.

### Part 1 - CGM confirmation in carb advice

In `CarbAdviceService.evaluate`, before the existing trigger:

- **Meal context** holds at `now` if either:
  - a non-rescue carb note of ≥ 10 g was logged in the last 4 h; or
  - a rapid dose not labelled "Correction" was logged in the last 60 min and no carbs since.
    This is a pre-bolus waiting for its meal; reuse the alias set in `PreBolusResolver`.
- **In meal context**, the forecast-only path (forecast minimum < 3.9 within 60 min) fires only
  when the CGM confirms it: the trend is at or below `FALL_RATE` (−0.1 mmol/L/min), or the
  current reading is ≤ 4.5 mmol/L. The existing fast-fall path is unchanged.
- **Outside meal context**, behaviour is unchanged.
- An advice already held by `CarbAdviceHold` is not withdrawn by the guard. The guard only
  decides new triggers.

Design call: the 4 h window, the 4.5 level and the 60-min pre-bolus window are starting values.
The harness reports missed/late advice for each, and they may be tuned before merge. Goal 2 is
the bar.

The watch strip reads the same `CarbAdvice`, so it follows without changes.

### Part 2 - Gamma appearance kernel

**Kernel.** Each carb entry `i` (logged, prospective, rescue, or the synthetic protein entry from
`ProteinGluconeogenesis`) contributes glucose mass `M_i = F × D_i`. `D_i` is today's
`toCarbMmol` (grams × `aG` × meal-window factor / 0.18) and `F = 0.90`. The time course is a
Gamma distribution with mode `T_i` (peak time) and standard deviation `σ_i`:

- `θ = (−T + √(T² + 4σ²)) / 2`, `k = 1 + T/θ` (exact: mode `(k−1)θ = T`, variance `kθ² = σ²`).
- Appearance during forecast minute `m` = `M_i × [P(k, (m − t_i)/θ) − P(k, (m − 1 − t_i)/θ)]`,
  with `P` the regularized lower incomplete gamma (`commons-math3`, already a dependency).
  Mass is conserved exactly. Glucose that appeared before "now" is excluded by the same formula,
  so no warm-up replay of the gut is needed.
- Bounds: `T ∈ [10, 180]` min, `σ ∈ [8, 150]` min, `k ≥ 1.5`.

**Parameter mapping** (the per-meal inputs are those the forecast already has: GI, fiber, protein,
fat, rescue flag):

```
T = s_T × [ T0 + a_GI·(1 − GI/100) + b_F·ln(1+F) + b_P·ln(1+P) + b_Fib·ln(1+Fib) ]
σ = s_W × [ W0 + c_GI·(1 − GI/100) + c_F·ln(1+F) + c_P·ln(1+P) + c_Fib·ln(1+Fib) ]
```

- GI: the entry's `estimatedGi`, or `DEFAULT_GI` (70) when missing.
- Fiber: the entry's own plus first-course fiber within 30 min (existing `effectiveFiberByMeal`).
- Fat and protein: the entry's own plus those of entries within ±30 min. The meal is treated as
  one stomach load. This replaces the shared dynamic GLP-1 ileal brake in the forecast path.
  Design call: a per-meal static modifier is what the kernel can express, and it removes the
  last cross-meal coupling.
- Rescue entries (`RescueCarbProfile`): a fixed kernel, `T_r = 15` min and `σ_r = 10` min
  (`k = 4`, `θ = 5`; at least 99% of the mass by about 55 min). It is independent of macros and
  of `s_T`/`s_W`, and not fitted: no dataset labels rescues. Design call. It matches
  `RescueCarbProfile`'s "done by 45 min" closely enough that COB and the forecast agree on
  rescue carbs. Check it against this user's 30 `hypo_treatment` notes in the harness; change it
  only if the 30/60-min error there is clearly biased. This kernel also sets how many grams the
  advice card suggests, through its what-if `hypo_treatment` note.
- `s_T`, `s_W`: per-user scales (see Fitting). Default 1.0.
- Default meal (GI 70, no fiber, at most the reference fat/protein): at least 95% of its mass
  within 4 h. For any meal, at least 99% within 8 h. These are constraints on the fitted
  coefficients.

**Where it lives.** A new `GammaMealAppearance` in `hovorka`, producing the same
`Appearance(stepMean, atEnd)` as `MealGutChains`. `buildAppearance` chooses between them by
`app.features.gamma-absorption-enabled`: default false until the evaluation passes, then true.
Dalla Man stays behind the flag until the Gamma path has been on for a release. Then it is
removed in a follow-up.

### Fitting

1. **Population coefficients** (offline, data-gated test, results committed as constants with
   the fit report in the PR):
   - `T0`, `W0` on AZT1D meal-with-bolus episodes (manual bolus within 15 min,
     ≥ 30 g, no other meal or manual bolus within 3 h, automatic boluses as logged). Each
     subject uses their seeded ISF/`aG` and a catalog rapid-insulin curve.
   - Macro coefficients (`b_*`, `c_*` for fat, protein, fiber): start from a literature prior.
     A meal with about 40-50 g fat peaks 60-90 min later and stays raised 3-5 h longer than its
     low-fat version; protein matters only at large amounts (≥ 75 g). Refine on real users'
     dev-DB meals logged before 2026-11-01, with a strong ridge toward the prior. CGMacros is a
     check on the 0-90 min shape only: its subjects make their own insulin, which hides the later
     phase where fat acts. Design call. The builder writes the prior's sources into the fit
     report.
   - GI coefficients (`a_GI`, `c_GI`): start from a prior that makes GI 30 peak about 30 min
     later and run about 40% wider than GI 100. Refine the same way, on the same pre-November
     meals. GI is taken as stored, including the Open Food Facts fat/protein lowering.
   - Loss, per episode over 0-240 min: curve MAE + peak-value error + time-to-peak error + AUC
     error, plus an asymmetric penalty when the forecast is < 3.9 and the CGM is ≥ 4.5. Ridge
     toward the starting values. Solver: `LmParameterFitter` (IRLS-Huber already handles
     mislogged meals). Leave-subjects-out cross-validation.
2. **Per-user scales** `s_T`, `s_W`: the digital twin fits them on `Regime.MEAL` anchors. They
   multiply the population `T` and `σ`. Storage: in `V8__user_digital_twin.sql`, edited in place
   per the repo's migration rule (repair the dev DB), rename the reserved `tmax_g_scale` to
   `absorption_time_scale` and add `absorption_width_scale`. Ridge to 1.0, clamps [0.5, 2.0],
   and the existing rules: held-out improvement required, pinned-at-bound rejected. ISF stays
   fitted on corrections only; `aG` on all anchors.
3. **Refit on the switch.** Every twin's scales and hour-of-day residuals were fitted against
   Dalla Man. They would correct the Gamma forecast twice. When the flag flips, force a
   recalibration of every user (the existing batch `calibrateAllRealUsers`). Until a user's
   refit finishes, their twin is not applied.

### Rollout order

Each step is a separate PR, judged by the Part 0 harness:

1. Part 0 harness with the baseline report.
2. Part 1 guard. It ships on its own: it does not depend on Part 2.
3. Gamma kernel behind the flag, plus the population fit. Judged on the population model alone,
   without per-user scales: the success criteria must hold here.
4. Twin per-user scales. They must improve on step 3 out-of-sample, or they are not applied.
5. Flip the flag and force the twin refit.

## Success criteria

On this user's exported data (2026-11-01 onward, held out from all fitting and tuning) and on
AZT1D:

- False advice after meals (as logged): 0 on the 2026-11-19 dinner, and at least 80% fewer than
  the baseline overall.
- No real low loses its advice (as logged, on the user's full history and AZT1D). Lead time
  before the CGM reaches 3.9 is at least 15 min in every case where the baseline had it.
- Meal-anchor bias at 60 and 120 min within ±1.0 mmol/L (full knowledge). MAE at 120 min at least
  30% lower than the baseline. Correction and fasting anchors no worse than baseline.
- AZT1D meal scenario (peak time within tolerance) no worse than today. CGMacros +30/+60 min
  error no worse than today.

## Left to the builder

- The harness's CSV export format and how it is produced (reuse the `BacktestHarness` inputs if
  they fit).
- How the population fit handles AZT1D automated boluses inside an episode: drop the episode,
  or include the logged automated insulin.
- Which catalog insulin curve AZT1D subjects get when the pump data does not name the insulin.
- Code structure inside `GammaMealAppearance` and the kernel cache.
- Exact wording of the PR fit report.
