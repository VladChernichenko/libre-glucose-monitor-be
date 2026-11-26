# Meal Absorption (Gamma) and Carb-Advice Guard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop forecast-only lows from telling the user to eat after a meal. Replace the meal
absorption shape with a Gamma kernel fitted to real post-meal CGM. Judge both with a replay
harness.

**Architecture:** A pure `CarbAdviceTrigger` plus `MealContext` add a CGM-confirmation rule to
carb advice. A new per-meal `GammaMealAppearance` plugs in behind the existing appearance seam
(`MealGutChains.Appearance` feeding `HovorkaOdeSolver.stepWithAppearance`), behind the flag
`app.features.gamma-absorption-enabled`. A test-scope `MealReplayHarness` replays exported
notes and CGM, and AZT1D, to produce the baseline and the acceptance numbers. The digital twin
gains two absorption scales.

**Tech Stack:** Java 21 (Temurin), Spring Boot, JUnit 5, AssertJ, Mockito, Apache Commons Math 3
(`Gamma.regularizedGammaP`, `GammaDistribution`, `LmParameterFitter`), Flyway, PostgreSQL.

**Spec:** `docs/superpowers/specs/2026-11-20-meal-absorption-gamma-and-advice-guard-design.md`

## Global Constraints

- Gradle runs on Java 21:
  `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`.
- Per task, run only the tests named in it. Run the full suite once before each PR.
  `CategoryTest` already fails on `main` (since `13849b8`) and is not this plan's concern.
- Work on a branch cut from `meal-gut-chains`. It carries the per-meal seam this plan builds on.
- Real-user CGM and notes exports are never committed. Data-gated tests skip via
  `Assumptions.assumeTrue` when their data is absent.
- Fitting and tuning use data before 2026-11-01. Success is judged on 2026-11-01 onward.
- Meal context: a non-rescue carb note of ≥ 10 g in the last 4 h, or a rapid dose not labelled
  "Correction" in the last 60 min with no carbs since.
- Confirmation in meal context: trend ≤ −0.1 mmol/L/min, or reading ≤ 4.5 mmol/L (both
  inclusive). The fast-fall path and behaviour outside meal context do not change. A held advice
  is never withdrawn by the guard.
- Amplitude stays as today: `D_i = toCarbMmol(entry)` (grams × `aG` × meal-window factor / 0.18),
  mass `F × D_i` with `F = DallaManGutModel.F = 0.90`. No GL.
- Kernel bounds: mode `T ∈ [10, 180]` min, SD `σ ∈ [8, 150]` min, `k ≥ 1.5`. Every kernel has
  ≥ 99% of its mass by 480 min. The default meal (GI 70, no fiber/fat/protein) has ≥ 95% by
  240 min. The rescue kernel is fixed at `T = 15`, `σ = 10`.
- Kernel inputs: fiber is the entry's own plus first-course fiber within 30 min (existing
  `effectiveFiberByMeal`). Fat and protein are those of all entries within ±30 min. GI is the
  entry's `estimatedGi`, else 70. `carb_half_life` is ignored on the Gamma path.
- The insulin curve is never refitted on meal data.
- Twin absorption scales: clamp [0.5, 2.0], ridge toward 1.0, fitted on `Regime.MEAL` anchors
  only. Applied only if the twin beats baseline on held-out data, and rejected if pinned at a
  bound.
- Migrations: edit `V8__user_digital_twin.sql` in place. Add the matching statements to
  `scripts/upgrade-existing-db.sql`, then repair the dev DB (`./flyway.sh repair`).
- `app.features.gamma-absorption-enabled` defaults to `false` until Task 14.

## Review Focus

1. A meal logged in several notes over 1.5 h (the 2026-11-19 pattern). The guard must hold as
   long as any part of the meal is within 4 h, and fat/protein from the other notes must reach
   each part's kernel. Tests: Task 5 (`guardHoldsAcrossAMealLoggedInParts`) and Task 9
   (`fatFromNeighbouringNoteDelaysThePotato`).
2. The carb-advice gram search assumes more what-if carbs never lower the forecast minimum. It
   must stay true on the Gamma path. Test: Task 9 (`moreRescueCarbsNeverLowerTheMinimum`).
3. Odd nutrition: GI null, GI 0 (Open Food Facts meat), fat 150 g, fiber 40 g. The kernel must
   stay inside its bounds with ≥ 99% of mass by 480 min. Tests: Task 7
   (`extremeInputsStayInBounds`) and Task 8 (`oddNutritionStaysInBounds`).
4. Boundary readings: exactly 4.5 mmol/L and exactly −0.1 mmol/L/min confirm; an unknown trend
   (0) does not. Test: Task 5 (`confirmationBoundariesAreInclusive`).
5. A twin fitted against Dalla Man must not apply while Gamma is on, until it is refitted.
   Test: Task 12 (`twinFittedUnderOtherAbsorptionModelIsNotApplied`).

---

## File Structure

| File | Responsibility |
|---|---|
| `src/main/java/.../assistant/advice/CarbAdviceTrigger.java` (new) | Pure trigger decision: forecast minimum, fast-fall rule, CGM confirmation |
| `src/main/java/.../assistant/advice/MealContext.java` (new) | Pure "is a meal being absorbed now" from recent notes |
| `src/main/java/.../assistant/advice/CarbAdviceService.java` | Uses the two above; one notes query covering 4 h |
| `src/main/java/.../hovorka/GammaKernel.java` (new) | Gamma distribution from mode and SD, bounds, CDF and pdf |
| `src/main/java/.../hovorka/GammaKernelParams.java` (new) | Coefficients (prior, population) and `kernelFor(MealInputs, scales)` |
| `src/main/java/.../hovorka/MealInputs.java` (new) | GI, fiber, fat, protein, rescue flag for one entry |
| `src/main/java/.../hovorka/GammaMealAppearance.java` (new) | Per-meal appearance from kernels, same `Appearance` as `MealGutChains` |
| `src/main/java/.../hovorka/HovorkaGlucosePredictionService.java` | Chooses the appearance model by flag; builds `MealInputs` |
| `src/main/java/.../hovorka/HovorkaParameters.java` | Adds `absorptionTimeScale`, `absorptionWidthScale` |
| `src/main/java/.../hovorka/learning/TwinScales.java`, `DigitalTwinCalibrator.java`, `PredictionReplayEngine.java`, `hovorka/HovorkaParameterService.java` | Twin absorption scales |
| `src/main/java/.../entity/UserDigitalTwin.java`, `service/DigitalTwinService.java`, `service/DigitalTwinCalibrationService.java`, `db/migration/V8__user_digital_twin.sql`, `scripts/upgrade-existing-db.sql` | Storage and applying of the scales |
| `scripts/export-replay-data.sh` (new) | Exports one user's notes, CGM and settings to a directory outside the repo |
| `src/test/java/.../backtest/MealReplayHarness.java` (new) | Replay, anchors, modes, metrics, report |
| `src/test/java/.../backtest/MealReplayBaselineTest.java` (new, data-gated) | Prints reports for real exports and AZT1D |
| `src/test/java/.../azt1d/Azt1dReplayData.java` (new) | AZT1D subject → harness input |
| `src/test/java/.../backtest/GammaPopulationFitTest.java` (new, data-gated) | Population coefficient fit and report |

`...` = `che/glucosemonitorbe`.

---

## Milestone A — Replay harness and baseline (PR 1)

### Task 1: Extract the carb-advice trigger (no behaviour change)

**Files:**
- Create: `src/main/java/che/glucosemonitorbe/assistant/advice/CarbAdviceTrigger.java`
- Modify: `src/main/java/che/glucosemonitorbe/assistant/advice/CarbAdviceService.java`
- Test: `src/test/java/che/glucosemonitorbe/assistant/advice/CarbAdviceTriggerTest.java`

**Interfaces:**
- Produces:
  - `public static final double FALL_RATE = -0.1;`
  - `public static Optional<PredictionPointDTO> minimum(List<PredictionPointDTO> forecast, LocalDateTime now, int minutes)`
  - `public static boolean fires(LocalDateTime now, double trendMmolPerMin, List<PredictionPointDTO> forecast)` (today's rule: hypo within 60, or fast fall and hypo within 90).
- `CarbAdviceService.FALL_RATE` stays, aliased to `CarbAdviceTrigger.FALL_RATE`.

- [ ] **Step 1: Write the failing test.** `CarbAdviceTriggerTest` covers:
  - `firesWhenMinimumUnder3_9Within60`: a V-shaped path 6.0 → 3.4 at +45, trend 0, so `fires` is true.
  - `firesOnFastFallWithin90`: 6.0 → 3.5 at +80, trend −0.15, so true.
  - `doesNotFireOnSlowFallBeyond60`: same path, trend 0, so false.
  - `doesNotFireWhenPathSettlesAt4_4`: false.

  Copy the `vShape` helper from `CarbAdviceServiceTest`.
- [ ] **Step 2: Run** `./gradlew test --tests '*CarbAdviceTriggerTest'`. Expected: compilation failure (class missing).
- [ ] **Step 3: Implement** `CarbAdviceTrigger` by moving `triggered` and `minimum` out of the service unchanged. The service calls `CarbAdviceTrigger.fires` and `CarbAdviceTrigger.minimum`.
- [ ] **Step 4: Run** `./gradlew test --tests '*CarbAdviceTriggerTest' --tests '*CarbAdviceServiceTest' --tests '*WatchStrip*'`. Expected: all pass, with the existing tests unchanged.
- [ ] **Step 5: Commit** `refactor(advice): extract the carb-advice trigger into a pure class`.

### Task 2: Export script

**Files:**
- Create: `scripts/export-replay-data.sh`

**Interfaces:**
- Produces: in `<outdir>`, the files `cgm.csv` (`date_timestamp,sgv`), `notes.csv`
  (`timestamp,carbs,insulin,type,meal,insulin_code,nutrition_profile`; timestamps ISO-8601 with
  offset), and `settings.properties`. The properties are `isf`, `isfBreakfast`, `isfLunch`,
  `isfDinner`, `isfNight`, `gramsPerUnit`, `carbRatio`, `bodyWeightKg`, `timezone`, `rescueCarbsG`,
  plus `curve.<INSULIN_CODE>=<diaHours>/<peakMinutes>` for every rapid insulin in
  `insulin_catalog`, `curve.default` (the user's selected rapid insulin), and
  `basalDurationHours` (from the user's long-acting catalog entry).

- [ ] **Step 1: Write** `scripts/export-replay-data.sh <user-email> <outdir>`. It uses `psql` with `PGPASSWORD` from the environment (no defaults in the file). It refuses an `<outdir>` inside the git repo (`git -C "$outdir" rev-parse` must fail), creates the directory with mode 700, and writes the three files with `\copy`.
- [ ] **Step 2: Run** it for the dev user into `~/replay-data/963266ad` and check: `head -2` of both CSVs shows the headers above; `grep -c . settings.properties` ≥ 11.
- [ ] **Step 3: Commit** `chore(scripts): export one user's notes, CGM and settings for replay`.

### Task 3: `MealReplayHarness`

**Files:**
- Create: `src/test/java/che/glucosemonitorbe/backtest/MealReplayHarness.java`
- Create: `src/test/java/che/glucosemonitorbe/backtest/MealReplayHarnessTest.java`

**Interfaces:**
- Consumes: `CarbAdviceTrigger` (Task 1), `NoteToCarbsEntryMapper`, `ProteinGluconeogenesis.withDelayedProteinGlucose`, `HovorkaGlucosePredictionService.buildPredictionPath` (the hot-path overload with `isfScale` and trend), and `BacktestHarness.splitCsv`/`macroFromJson`.
- Produces:
  - `record Data(List<BacktestHarness.CgmPoint> cgm, List<Note> notes, UserSettingsDTO settings, Map<String, RapidInsulinIobParameters> curves, double basalDurationHours, ZoneId zone)`
  - `static Data load(Path dir)`: notes are converted to the user's wall time in `zone`, because meal-window ISF depends on it.
  - `enum Mode { AS_LOGGED, FULL_KNOWLEDGE }`
  - `enum Bucket { MEAL, PRE_BOLUS, CORRECTION, FASTING }`
  - `record Metrics(int n, double bias, double mae)`
  - `@FunctionalInterface interface Forecaster { List<PredictionPointDTO> forecast(LocalDateTime now, double g0, double trend, List<Note> visible); }`
  - `static Forecaster hovorka(Data d, boolean gamma)`: the real model, raw (`PredictionResidualProvider.NONE`, `isfScale` 1.0). Inputs are built as production does: mapper with nutrition-aware on, delayed protein entries, rapid doses with their `insulin_code` curve (unknown code → `curves.get("default")`), and long-acting notes. The `gamma` argument is ignored until Task 9 wires the flag.
  - `@FunctionalInterface interface MealContextFn { boolean at(LocalDateTime now, List<Note> visible); }` with `MealContextFn NONE = (n, v) -> false`.
  - `record Options(Mode mode, LocalDate fromInclusive, LocalDate toExclusive, MealContextFn mealContext)`
  - `static Report run(Data d, Forecaster f, Options o)`
  - `Report`:
    - `Metrics metrics(Bucket b, int horizonMin)`;
    - `Metrics rescueMetrics(int horizonMin)` (anchors at hypo-treatment notes);
    - `int adviceAnchors()`, `int falseAdvice()`;
    - `List<LowOutcome> lows()` with `record LowOutcome(LocalDateTime lowAt, LocalDateTime firstFireAt, Integer leadMin)` (`firstFireAt`/`leadMin` null when missed);
    - `String render()`.

Rules the implementation must follow:
- **Anchors** (inside `[from, to)`): every note with rapid insulin > 0, carbs ≥ 10 g or
  `isHypoTreatment()`, plus every 15 min within 4 h after a carb note of ≥ 10 g. The anchor's
  start value is the CGM within ±2.5 min; skip the anchor if there is none. The trend is
  `(g0 − CGM at −15 min) / 15`, or 0 when missing.
- **Visible notes:** in `AS_LOGGED` mode, notes with timestamp ≤ anchor. In `FULL_KNOWLEDGE`
  mode, also notes in `(anchor, anchor + 120 min]`.
- **Bucket:**
  - `MEAL` if a non-rescue carb note of ≥ 10 g is in the last 4 h;
  - otherwise `PRE_BOLUS` if a rapid dose not labelled "Correction" is in the last 60 min with no carbs since;
  - otherwise `CORRECTION` if a rapid dose is in the last 4 h;
  - otherwise `FASTING`.
- **Trigger used by the harness:** until Task 6, `CarbAdviceTrigger.fires(now, trend, forecast)`.
  From Task 6 on, `fires(now, g0, trend, forecast, o.mealContext().at(now, visible))`.
- **False advice** (`AS_LOGGED` only): the trigger fires at an anchor, and every CGM reading in
  `(t, t + 60]` is ≥ 4.5.
- **Lows** (`AS_LOGGED` only):
  - A low is the first CGM reading < 3.9 after ≥ 30 min of readings ≥ 3.9, with its time inside
    `[from, to)`.
  - For each low, evaluate the trigger on a 5-min grid over `[lowAt − 60, lowAt)`.
  - `firstFireAt` is the earliest firing grid point. `leadMin = lowAt − firstFireAt`.
  - It counts as missed when nothing fires, and as late when `leadMin < 15`.

- [ ] **Step 1: Write the failing tests** in `MealReplayHarnessTest`, all with a stub `Forecaster` and synthetic data:
  - `countsFalseAdviceWhenCgmStaysUp`: one meal note; CGM flat at 7.0; the stub returns a path reaching 3.0 at +40. Expect `falseAdvice() == adviceAnchors()` and both > 0.
  - `noFalseAdviceWhenCgmReallyFalls`: CGM falls to 3.5 within 60 min. Expect `falseAdvice() == 0`.
  - `lowLeadTimeIsMeasuredOnTheFiveMinuteGrid`: the CGM drops below 3.9 at t; the stub fires from t−25. Expect one `LowOutcome` with `leadMin == 25`.
  - `missedLowHasNullLead`: the stub never fires. Expect `firstFireAt == null`.
  - `fullKnowledgeSeesNotesUpTo120MinAhead`: assert on the `visible` list the stub receives at an anchor 30 min before a second meal note.
  - `bucketsFollowTheSpecOrder`: one anchor of each bucket.
  - `notesAreReadInTheUsersWallTime`: a `notes.csv` row at `2026-11-19T19:44:26+03:00` with `timezone=Europe/Moscow` loads as `19:44:26`.
- [ ] **Step 2: Run** `./gradlew test --tests '*MealReplayHarnessTest'`. Expected: compilation failure.
- [ ] **Step 3: Implement** `MealReplayHarness` to the interfaces and rules above.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `test(backtest): meal replay harness with advice and bias metrics`.

### Task 4: AZT1D adapter and baseline report

**Files:**
- Create: `src/test/java/che/glucosemonitorbe/azt1d/Azt1dReplayData.java`
- Create: `src/test/java/che/glucosemonitorbe/azt1d/Azt1dReplayDataTest.java`
- Create: `src/test/java/che/glucosemonitorbe/backtest/MealReplayBaselineTest.java`
- Modify: `build.gradle` (forward `replay.dir` to the test JVM, like `azt1d.dir`)

**Interfaces:**
- Consumes: `MealReplayHarness` (Task 3); `Azt1dDataset.loadAll`/`loadAllBolusLogs`.
- Produces: `static MealReplayHarness.Data Azt1dReplayData.of(Azt1dDataset.Subject s, List<Azt1dDataset.BolusRecord> log)`.
  - Carbs become normal notes.
  - Manual boluses become rapid notes labelled by whether carbs are within 15 min ("Bolus" or "Correction"). Automatic boluses become notes labelled "Correction".
  - Settings come from the subject's seeded ISF, weight and carb ratio (as `gramsPerUnit`); `zone` is UTC.
  - Curve: `default=4.5/55` (as `ScenarioForecaster.RAPID_IOB`).

- [ ] **Step 1: Write** `Azt1dReplayDataTest.mapsManualBolusNearCarbsAsMealBolus` (synthetic subject). Run `./gradlew test --tests '*Azt1dReplayDataTest'`. Expected: compilation failure.
- [ ] **Step 2: Implement** `Azt1dReplayData`. Run the same command. Expected: PASS.
- [ ] **Step 3: Write** `MealReplayBaselineTest` with two data-gated tests:
  - `userExport`: reads `-Dreplay.dir`, skipping when absent. Prints `render()` for the `AS_LOGGED` and `FULL_KNOWLEDGE` modes over `[2026-11-01, 2100-01-01)`, plus the `AS_LOGGED` lows over the full history.
  - `azt1d`: reads `-Dazt1d.dir`. Prints the same for every subject, plus pooled totals.
- [ ] **Step 4: Run the baseline** with `./gradlew test --tests '*MealReplayBaselineTest' -Dreplay.dir=$HOME/replay-data/963266ad -Dazt1d.dir="/Users/vlad/Downloads/AZT1D  A Real-World Dataset for Type 1 Diabetes/AZT1D 2025"`. Expected: the reports print, and the user's `MEAL` bias at 120 min is negative (around −4, per the spec's evidence). This command is the **baseline run** that later tasks repeat.
- [ ] **Step 5: Commit** `test(backtest): AZT1D replay adapter and baseline report`. Paste both reports into the PR 1 description as the baseline.

---

## Milestone B — Carb-advice guard (PR 2)

### Task 5: `MealContext` and the guarded trigger

**Files:**
- Create: `src/main/java/che/glucosemonitorbe/assistant/advice/MealContext.java`
- Modify: `src/main/java/che/glucosemonitorbe/assistant/advice/CarbAdviceTrigger.java`, `CarbAdviceService.java`
- Test: `src/test/java/che/glucosemonitorbe/assistant/advice/MealContextTest.java`, `CarbAdviceTriggerTest.java`, `CarbAdviceServiceTest.java`

**Interfaces:**
- Produces:
  - `MealContext.MEAL_WINDOW = Duration.ofHours(4)`, `MIN_MEAL_CARBS_G = 10.0`, `PRE_BOLUS_WINDOW = Duration.ofMinutes(60)`.
  - `public static boolean MealContext.at(LocalDateTime now, List<Note> recentNotes)`. Notes after `now` are ignored. Rescue = `Note.isHypoTreatment()`. Long-acting = `Note.isLongActing()`. "Correction" is compared case-insensitively.
  - `CarbAdviceTrigger.CONFIRM_READING_MMOL = 4.5`.
  - `public static boolean CarbAdviceTrigger.fires(LocalDateTime now, double currentGlucose, double trendMmolPerMin, List<PredictionPointDTO> forecast, boolean mealContext)`. With `mealContext == false` it equals the Task 1 rule. With `true`, the hypo-within-60 path also needs `trend <= FALL_RATE || currentGlucose <= CONFIRM_READING_MMOL`; the fast-fall path is unchanged.
- `CarbAdviceService.evaluate`:
  - Loads notes once over `[now − 4 h, now]`. `lastFastCarbs` filters that list to the last 15 min.
  - Passes `MealContext.at(now, notes)` to the 5-argument trigger.
  - The hold path (`hold.notTriggered`) is untouched.

- [ ] **Step 1: Write the failing tests.**
  - `MealContextTest`:
    - `carbNoteWithin4hIsMealContext` (10 g at −3 h 59 → true);
    - `carbNoteOlderThan4hIsNot`;
    - `rescueCarbsAloneAreNot`;
    - `preBolusWithoutCarbsWithin60IsMealContext`;
    - `correctionDoseIsNot`;
    - `preBolusFollowedByCarbsIsMealContextThroughTheCarbs`;
    - `futureNotesAreIgnored`.
  - `CarbAdviceTriggerTest`:
    - `mealContextNeedsConfirmation` (5.4, trend 0, path to 1.0 within 60, so false);
    - `confirmationBoundariesAreInclusive` (reading 4.5, so true; trend −0.1, so true; trend 0 at 4.6, so false);
    - `fastFallPathUnchangedInMealContext`;
    - `outsideMealContextUnchanged`.
  - `CarbAdviceServiceTest`:
    - `dinnerOf2026_11_19GetsNoAdvice`: notes as in the spec, at 19:53 with reading 5.4, trend 0 and a path to 1.0 → `Optional.empty()`;
    - `guardHoldsAcrossAMealLoggedInParts`: potato at 19:44, salad at 20:03, anchor 20:15 → empty;
    - `heldAdviceIsNotWithdrawnByTheGuard`: first call outside meal context triggers; second call in meal context, reading unchanged → still present.
- [ ] **Step 2: Run** `./gradlew test --tests '*MealContextTest' --tests '*CarbAdviceTriggerTest' --tests '*CarbAdviceServiceTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** `MealContext`, the overload, and the service changes.
- [ ] **Step 4: Run** the same command plus `--tests '*WatchStrip*'`. Expected: PASS.
- [ ] **Step 5: Commit** `fix(advice): in meal context, advise carbs only on a CGM-confirmed fall`.

### Task 6: Guard acceptance

**Files:**
- Modify: `src/test/java/che/glucosemonitorbe/backtest/MealReplayHarness.java` (switch to the 5-argument trigger)
- Modify: `src/test/java/che/glucosemonitorbe/backtest/MealReplayBaselineTest.java`

- [ ] **Step 1: Switch** the harness trigger as described in Task 3. Run `./gradlew test --tests '*MealReplayHarnessTest'` and expect PASS (with `MealContextFn.NONE` behaviour is identical).
- [ ] **Step 2: Add** to both baseline tests a second `AS_LOGGED` run with `mealContext = MealContext::at`. Print false advice and lows side by side with the unguarded run.
- [ ] **Step 3: Repeat the baseline run** (Task 4 Step 4). Expected:
  - false advice on 2026-11-19 is 0;
  - meal-anchor false advice from 2026-11-01 is ≥ 80% lower than unguarded;
  - no low that fired unguarded is missed when guarded;
  - every low that had `leadMin ≥ 15` unguarded still has `leadMin ≥ 15`, on both the user's full history and AZT1D.

  If a low is lost, tune the three constants on data before 2026-11-01 only, rerun, and record the values.
- [ ] **Step 4: Commit** `test(backtest): guarded vs unguarded advice report`. Paste the report into the PR 2 description.

---

## Milestone C — Gamma kernel (PR 3)

### Task 7: `GammaKernel`

**Files:**
- Create: `src/main/java/che/glucosemonitorbe/hovorka/GammaKernel.java`
- Test: `src/test/java/che/glucosemonitorbe/hovorka/GammaKernelTest.java`

**Interfaces:**
- Produces:
  - `public record GammaKernel(double k, double theta)` with constants `MODE_MIN = 10`, `MODE_MAX = 180`, `SD_MIN = 8`, `SD_MAX = 150`, `K_MIN = 1.5`, `MAX_Q99_MIN = 480`.
  - `static GammaKernel fromModeAndSd(double modeMin, double sdMin)`;
  - `double cdf(double tMin)` (0 for t ≤ 0; `Gamma.regularizedGammaP(k, t/θ)`);
  - `double pdf(double tMin)`;
  - `double quantile(double p)` (`GammaDistribution.inverseCumulativeProbability`);
  - `double mode()`, `double sd()`.
- Algorithm (in this order):
  1. Clamp mode and SD into their ranges.
  2. `θ = (−T + √(T² + 4σ²)) / 2`, `k = 1 + T/θ`.
  3. If `k < 1.5`: set `k = 1.5` and `θ = T / 0.5`.
  4. While `quantile(0.99) > 480`: bisect σ down, with T fixed, until it holds (tolerance 1 min).

- [ ] **Step 1: Write the failing tests:**
  - `modeAndSdAreExact` (T 48, σ 53 → `mode()`, `sd()` within 1e-9);
  - `rescueKernelIsK4Theta5` (15, 10 → k 4, θ 5);
  - `massIsConserved` (Σ of `cdf(m) − cdf(m−1)` for m = 1..2000 within 1e-6 of 1);
  - `extremeInputsStayInBounds` (inputs (1, 1), (500, 500), (180, 150) → mode/SD in range, `k ≥ 1.5`, `quantile(0.99) ≤ 480`).
- [ ] **Step 2: Run** `./gradlew test --tests '*GammaKernelTest'`. Expected: compilation failure.
- [ ] **Step 3: Implement** `GammaKernel`.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `feat(hovorka): Gamma appearance kernel from peak time and width`.

### Task 8: `MealInputs`, `GammaKernelParams`, `GammaMealAppearance`

**Files:**
- Create: `src/main/java/che/glucosemonitorbe/hovorka/MealInputs.java`, `GammaKernelParams.java`, `GammaMealAppearance.java`
- Test: `src/test/java/che/glucosemonitorbe/hovorka/GammaKernelParamsTest.java`, `GammaMealAppearanceTest.java`

**Interfaces:**
- Consumes: `GammaKernel` (Task 7); `MealGutChains.Appearance`.
- Produces:
  - `public record MealInputs(double gi, double fiberG, double fatG, double proteinG, boolean rescue)`.
  - `public record GammaKernelParams(double t0, double aGi, double bFat, double bProtein, double bFiber, double w0, double cGi, double cFat, double cProtein, double cFiber, double rescueMode, double rescueSd)`:
    - `PRIOR = new GammaKernelParams(35, 43, 20, 7, 4, 45, 26, 16, 7, 5, 15, 10)`;
    - `POPULATION = PRIOR` (replaced in Task 10);
    - `GammaKernel kernelFor(MealInputs in, double timeScale, double widthScale)` uses the spec's formulas, `T = s_T × […]` and `σ = s_W × […]`, with `ln(1 + x)` for fat, protein and fiber grams. Rescue returns `fromModeAndSd(rescueMode, rescueSd)` and ignores macros and scales.
  - `public final class GammaMealAppearance`:
    - `void addMeal(int step, double massMmol, GammaKernel kernel)`. Same step convention as `MealGutChains`: the meal enters before step `step`, so it is eaten at time `step − 1`.
    - `MealGutChains.Appearance appearance(int pathMinutes)`: `stepMean[m] = Σ mass × (cdf(m − e) − cdf(m − 1 − e))` and `atEnd[m] = Σ mass × pdf(m − e)`, with `e = step − 1`.

- [ ] **Step 1: Write the failing tests.**
  - `GammaKernelParamsTest`:
    - `defaultMealIs95PercentIn4h` (GI 70, no macros, `POPULATION`: `cdf(240) ≥ 0.95`);
    - `gi30PeaksAbout30MinLaterAnd40PercentWiderThanGi100` (`PRIOR`: ΔT within 30 ± 2, σ ratio 1.40 ± 0.03);
    - `fat45gDelaysPeak60To90Min` (`PRIOR`);
    - `scalesMultiplyModeAndSd` (2.0/1.5 → mode ×2, SD ×1.5 before clamping);
    - `rescueIgnoresMacrosAndScales`;
    - `oddNutritionStaysInBounds` (GI 0, fat 150, fiber 40, protein 120, scales 2.0 → `quantile(0.99) ≤ 480`, `k ≥ 1.5`).
  - `GammaMealAppearanceTest`:
    - `appearanceIsAdditiveAcrossMeals`;
    - `pastMealCountsOnlyWhatIsLeft` (eaten 60 min ago → Σ `stepMean` over a 2000-min path = mass × (1 − cdf(60)) within 1e-6);
    - `prospectiveMealStartsAtItsStep` (`stepMean[m] == 0` for m < step).
- [ ] **Step 2: Run** `./gradlew test --tests '*GammaKernelParamsTest' --tests '*GammaMealAppearanceTest'`. Expected: compilation failure.
- [ ] **Step 3: Implement** the three classes.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `feat(hovorka): per-meal Gamma appearance with prior coefficients`.

### Task 9: Wire Gamma into the forecast behind the flag

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/hovorka/HovorkaParameters.java`
- Modify: `src/main/java/che/glucosemonitorbe/hovorka/HovorkaGlucosePredictionService.java`
- Modify: `src/main/java/che/glucosemonitorbe/hovorka/HovorkaParameterService.java`, `hovorka/learning/PredictionReplayEngine.java` (copy sites only)
- Modify: `src/main/java/che/glucosemonitorbe/config/FeatureToggleConfig.java`, `src/main/resources/application.yml`
- Modify: `src/test/java/che/glucosemonitorbe/backtest/MealReplayHarness.java` (`hovorka(d, gamma)` sets the flag)
- Test: `src/test/java/che/glucosemonitorbe/hovorka/GammaForecastTest.java`

**Interfaces:**
- Consumes: Task 8 classes.
- Produces:
  - `HovorkaParameters` gains trailing components `double absorptionTimeScale, double absorptionWidthScale`, plus a 10-argument constructor defaulting both to 1.0. The four `new HovorkaParameters(` sites in `src/main` (including `pAdj` in `integrate`) pass `p.absorptionTimeScale()` and `p.absorptionWidthScale()` through.
  - `FeatureToggleConfig.gammaAbsorptionEnabled` (default false). `application.yml`: `gamma-absorption-enabled: ${APP_FEATURES_GAMMA_ABSORPTION_ENABLED:false}`.
  - `HovorkaGlucosePredictionService`:
    - field `@Value("${app.features.gamma-absorption-enabled:false}") private boolean gammaAbsorptionEnabled;` with `public void setGammaAbsorptionEnabled(boolean)`;
    - field `private GammaKernelParams gammaKernelParams = GammaKernelParams.POPULATION;` with `public void setGammaKernelParams(GammaKernelParams)` (used by the population fit);
    - constant `MEAL_CLUSTER_MIN = 30`;
    - `buildAppearance` uses `GammaMealAppearance` when the flag is on. The inputs are `MealInputs(giOf(e), fiberByMeal.getOrDefault(e, 0), fat ±30 min, protein ±30 min, RescueCarbProfile.isRescue(e.getAbsorptionMode()))`, with mass `toCarbMmol(e, p, settings) × DallaManGutModel.F` and kernel `gammaKernelParams.kernelFor(inputs, p.absorptionTimeScale(), p.absorptionWidthScale())`. Protein/fat-only entries add no kernel.

- [ ] **Step 1: Write the failing tests** in `GammaForecastTest`. Construct the service as `RealDashboardForecast` does, then call `setGammaAbsorptionEnabled(true)`.
  - `timeScaleTwoDelaysTheCarbEffectPeak`: the peak minute of `carbAbsorptionEffect` with `absorptionTimeScale` 2.0 is later than with 1.0.
  - `absorptionScalesSurviveTheBasalCopy`: same as above, with an active long-acting note so `pAdj` is rebuilt.
  - `fatFromNeighbouringNoteDelaysThePotato`: 26 g potato, then a carb-free 30 g-fat note 10 min later. The potato's effect peaks later than without the fat note.
  - `moreRescueCarbsNeverLowerTheMinimum`: with 8 U on board, the forecast minimum with a what-if `hypo_treatment` note of 10, 20 or 40 g at now is non-decreasing.
- [ ] **Step 2: Run** `./gradlew test --tests '*GammaForecastTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** the changes above.
- [ ] **Step 4: Run** `./gradlew test --tests '*GammaForecastTest' --tests 'che.glucosemonitorbe.hovorka.*' --tests '*DigitalTwin*' --tests '*PredictionReplay*' --tests '*GlucoseCalculationsService*'`. Expected: PASS, including `HovorkaRescueAbsorptionTest`'s golden curve unchanged with the flag off.
- [ ] **Step 5: Commit** `feat(hovorka): Gamma meal absorption behind gamma-absorption-enabled`.

### Task 10: Population fit

**Files:**
- Create: `src/test/java/che/glucosemonitorbe/backtest/GammaPopulationFitTest.java`
- Modify: `src/main/java/che/glucosemonitorbe/hovorka/GammaKernelParams.java` (`POPULATION` constants)

**Interfaces:**
- Consumes: `MealReplayHarness` (Tasks 3, 4); `Azt1dReplayData`; `ScenarioMiner.mine(subject, log, ScenarioKind.MEAL_WITH_BOLUS)`; `LmParameterFitter.fit(ResidualModel, start, lower, upper)`; `HovorkaGlucosePredictionService.setGammaKernelParams` (Task 9).
- Produces: printed fit reports and a new `POPULATION` record.

Procedure (one data-gated test per stage, each printing its result):
1. **`fitBaseTimingOnAzt1d`.** AZT1D `MEAL_WITH_BOLUS` episodes, forecast at episode start with
   the meal and bolus known. Free parameters `t0`, `w0`; all others at `PRIOR`.
   - Residuals: one per episode for each of
     - CGM error at 30-min steps over 0–240 min;
     - peak-value error;
     - time-to-peak error ÷ 30;
     - AUC error ÷ 240;
     - an asymmetric term `max(0, 3.9 − pred) × 3` where the CGM is ≥ 4.5.
   - Ridge rows `(p − prior) / prior × 2`.
   - Bounds: `t0 ∈ [15, 90]`, `w0 ∈ [20, 120]`.
   - Leave-subjects-out (5 folds): report the fold MAE spread.
2. **`fitMacrosAndGiOnUserMealsBeforeNovember`.** The user export in `FULL_KNOWLEDGE` mode, meal
   anchors before 2026-11-01, `t0`/`w0` from stage 1. Free parameters: `aGi`, `cGi`, `bFat`,
   `cFat`, `bProtein`, `cProtein`, `bFiber`, `cFiber`. Same residuals and ridge, each bounded to
   [0, 3 × prior].
3. **`checkEarlyShapeOnCgMacros`.** Report only. For each CGMacros meal (no insulin), forecast
   with the flag off (Dalla Man) and on (fitted Gamma). Report time-to-peak error and the
   +30/+60 min MAE for both. A worse Gamma number is flagged in the report, not fitted.

- [ ] **Step 1: Write** the three tests. Run `./gradlew test --tests '*GammaPopulationFitTest' -Dreplay.dir=… -Dazt1d.dir=…`. Expected: three printed reports.
- [ ] **Step 2: Copy** the fitted values into `GammaKernelParams.POPULATION`, keeping `PRIOR`. Run `./gradlew test --tests '*GammaKernelParamsTest' --tests '*GammaForecastTest'`. Expected: PASS. In particular, `defaultMealIs95PercentIn4h` must hold for `POPULATION`. If it fails, tighten the stage-1 bounds and refit.
- [ ] **Step 3: Commit** `feat(hovorka): population Gamma coefficients from AZT1D and pre-November meals`. Put the three reports, with the literature sources for the prior, into the PR description.

### Task 11: Gamma acceptance (population only)

**Files:**
- Modify: `src/test/java/che/glucosemonitorbe/backtest/MealReplayBaselineTest.java`

- [ ] **Step 1: Add** a Gamma run (`hovorka(d, true)`, guard on) next to the baseline, in both modes.
- [ ] **Step 2: Repeat the baseline run** (Task 4 Step 4). Expected, on 2026-11-01 onward and AZT1D:
  - `MEAL` bias at 60 and 120 min within ±1.0 (`FULL_KNOWLEDGE`);
  - `MEAL` MAE at 120 ≥ 30% below baseline;
  - `CORRECTION` and `FASTING` MAE no worse than baseline;
  - the advice criteria of Task 6 still met;
  - `rescueMetrics` |bias| at 30 and 60 min ≤ 1.0. Otherwise change the rescue kernel, using pre-November hypo treatments only, and record why;
  - AZT1D meal-scenario peak-time pass rate (`Azt1dScenarioSweepTest`) no worse;
  - CGMacros +30/+60 not flagged worse in Task 10's report.

  If any criterion fails, go back to Task 10 using pre-November data only.
- [ ] **Step 3: Commit** `test(backtest): Gamma acceptance report`. Paste the report into PR 3.

---

## Milestone D — Twin absorption scales (PR 4)

### Task 12: Scales in the twin, storage and model match

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/hovorka/learning/TwinScales.java`, `DigitalTwinCalibrator.java`, `PredictionReplayEngine.java`
- Modify: `src/main/java/che/glucosemonitorbe/hovorka/HovorkaParameterService.java`
- Modify: `src/main/java/che/glucosemonitorbe/entity/UserDigitalTwin.java`, `service/DigitalTwinService.java`, `service/DigitalTwinCalibrationService.java`
- Modify: `src/main/resources/db/migration/V8__user_digital_twin.sql`, `scripts/upgrade-existing-db.sql`
- Test: `src/test/java/che/glucosemonitorbe/hovorka/learning/DigitalTwinCalibratorTest.java`, `src/test/java/che/glucosemonitorbe/service/DigitalTwinServiceTest.java` (create or extend)

**Interfaces:**
- Produces:
  - `TwinScales(double isfScale, double agScale, double absorptionTimeScale, double egpScale, double absorptionWidthScale)`. `tMaxGScale` is renamed; the 4-argument constructor keeps `(isf, ag, time, egp)` and sets width to 1.0. `clamped()` and `pinnedScale()` cover all five.
  - `UserDigitalTwin`: `absorptionTimeScale` (column `absorption_time_scale`, renamed from `tmax_g_scale`), `absorptionWidthScale` (`absorption_width_scale DOUBLE PRECISION NOT NULL DEFAULT 1.0`), `absorptionModel` (`absorption_model VARCHAR(16)`, nullable: `DALLA_MAN` or `GAMMA`).
  - Both `applyScales` set `absorptionTimeScale`/`absorptionWidthScale` from the clamped scales.
  - `DigitalTwinCalibrator.Config`: `public boolean fitAbsorption = false;`, `public int minMealSamples = 20;`. A new stage runs after the `aG` stage when `fitAbsorption` is true and the `Regime.MEAL` count is ≥ `minMealSamples`. It calls `robustFit(train, {1,1}, {0.5,0.5}, {2,2}, p -> new TwinScales(isf, ag, p[0], egp, p[1]), s -> s.regime() == Regime.MEAL)`. Otherwise both scales stay 1.0.
  - `DigitalTwinCalibrationService` sets `cfg.fitAbsorption` from `FeatureToggleConfig.gammaAbsorptionEnabled`, and stores the scales and `absorptionModel` (`GAMMA` when the flag is on, else `DALLA_MAN`).
  - `DigitalTwinService.activeScales` returns empty when the stored `absorptionModel` differs from the current flag. `null` counts as `DALLA_MAN`.

- [ ] **Step 1: Write the failing tests:**
  - `absorptionScalesFittedOnMealAnchorsOnly` (synthetic source whose MEAL residual is minimised at time scale 1.4 → fitted 1.4 ± 0.1; correction anchors don't move it);
  - `absorptionScalesStayNeutralBelowMinMealSamples`;
  - `pinnedAbsorptionScaleRejectsTheTwin`;
  - `twinFittedUnderOtherAbsorptionModelIsNotApplied` (`DigitalTwinServiceTest`: stored `DALLA_MAN` with the flag on → `activeScales` empty).
- [ ] **Step 2: Run** `./gradlew test --tests '*DigitalTwinCalibratorTest' --tests '*DigitalTwinServiceTest'`. Expected: FAIL.
- [ ] **Step 3: Implement**. Edit V8 in place. Add to `upgrade-existing-db.sql`: `ALTER TABLE user_digital_twin RENAME COLUMN tmax_g_scale TO absorption_time_scale;` (guarded by an `information_schema` check), plus `ADD COLUMN IF NOT EXISTS` for the two new columns. Apply both to the dev DB, then run `./flyway.sh repair`.
- [ ] **Step 4: Run** the Step 2 command plus `--tests '*PredictionReplay*' --tests '*HovorkaParameterService*' --tests '*ExistingDbUpgradeIT*'`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(twin): per-user absorption time and width scales`.

### Task 13: Personal scales acceptance

- [ ] **Step 1: Run** `SPRING_PROFILES_ACTIVE=recalibrate-cli APP_FEATURES_GAMMA_ABSORPTION_ENABLED=true ./gradlew bootRun` against the dev DB. Then query `select status, absorption_time_scale, absorption_width_scale, applied from user_digital_twin` for the user. Expected: a status line, and either `applied = true` with improved validation MAE, or a stated reason for not applying.
- [ ] **Step 2: Record** the result in the PR 4 description. No code change unless the run errors.

---

## Milestone E — Switch on (PR 5)

### Task 14: Flip the flag

**Files:**
- Modify: `src/main/resources/application.yml`, `src/main/java/che/glucosemonitorbe/config/FeatureToggleConfig.java`

- [ ] **Step 1: Set** the `gamma-absorption-enabled` default to `true` in both files.
- [ ] **Step 2: Run** the full suite: `./gradlew test`. Expected: only the pre-existing `CategoryTest` failure. Any golden-curve test that pins Dalla Man output must set the flag to `false` explicitly. Do not regenerate it.
- [ ] **Step 3: Repeat the baseline run** (Task 4 Step 4) and paste the final report into PR 5.
- [ ] **Step 4: Commit** `feat(hovorka): Gamma meal absorption on by default`. After deploy, twins with `absorption_model` ≠ `GAMMA` stay inactive until the 03:15 nightly refit or `POST /api/digital-twin/recalibrate`.
