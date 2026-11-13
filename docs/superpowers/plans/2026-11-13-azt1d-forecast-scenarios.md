# AZT1D Forecast Scenarios Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mine real episodes from the 25 AZT1D subjects, check the forecast on each against what happened, and keep a small de-identified set as always-run tests.

**Architecture:** One additive seam on `PredictionReplayEngine` (`replayAt`) lets a test ask for the forecast at an exact time. Everything else is test code in package `che.glucosemonitorbe.azt1d`: a bolus-log loader, an episode miner, a forecaster (neutral or twin fitted on earlier days only), per-kind rules, a local baseline, a data-gated sweep, and a fixture exporter and reader.

**Tech Stack:** Java 21, JUnit 5, AssertJ, Gradle. Existing: `Azt1dDataset`, `PredictionReplayEngine`, `AnchorSample`, `DigitalTwinCalibrator`, `Azt1dCalibrationValidationTest.rawPredictor()` / `personalParams(Profile)` (package-private static, so new files stay in the same package).

**Spec:** `docs/superpowers/specs/2026-11-13-azt1d-forecast-scenarios-design.md`

## Global Constraints

- Dataset path: `-Dazt1d.dir="/Users/vlad/Downloads/AZT1D  A Real-World Dataset for Type 1 Diabetes/AZT1D 2025"` (two spaces after "AZT1D"). Data-gated tests use `Assumptions.assumeTrue(...)` so they skip when it is absent, exactly like `Azt1dCalibrationValidationTest`.
- Run tests with `./gradlew test --tests '<pattern>' -x jacocoTestReport`; `build.gradle` already forwards `azt1d.dir`. A new property `azt1d.baseline` must be added to the forwarded list in `build.gradle` (Task 7).
- Only human-chosen doses are mined: skip any episode with an `Automatic Bolus` record from 120 min before the start to the end of the checked horizon.
- Checked horizon is 180 min, sampled at 30, 60, 90, 120, 150, 180 (the model emits every 5 min to 4 h, so these exist).
- Correction drop: start at least 9.0 mmol/L, no carbs and no other manual bolus within ±180 min. Meal with bolus: at least 30 g and a manual bolus within 15 min. Meal without bolus: at least 30 g and no manual bolus from -15 to +180 min. Overnight steady: local start hour 0 to 2, no manual insulin or carbs within ±180 min.
- At most 6 episodes per kind per subject, evenly spaced in time.
- Fixtures: times relative to episode start; clock hour 0-23 and weekday only; no dates, no subject ids; at most 300 KB in total.
- The twin used in a scenario is fitted only on data before the episode's local day, and applied only when `Result.improved()`, as the live app does.
- No production behaviour changes. The `replayAt` seam is additive, and `replay()` must stay identical (its existing tests are the proof).
- Committed fixtures are out of scope until the owner confirms the AZT1D licence; this plan writes them only to `build/scenario-fixtures/`.
- Every commit message ends with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.

## Review Focus

- A subject whose CGM has gaps around an episode: the episode is skipped, never a crash or a forecast from a wrong anchor (Tasks 3, 4).
- Two doses 20 minutes apart: neither qualifies as a clean correction (Task 3).
- A subject flagged implausible by `Profile.plausible()`: scenarios still run on the fallback profile, and the report marks the subject (Task 7).
- No baseline file on the first run, or a corrupt one: first run creates it and passes; corrupt fails with a message naming the path (Task 7).
- Zero fixture files present: the always-run fixture tests pass and say so; a fixture containing a date or a subject id fails the guard (Task 9).

---

### Task 1: Replay-at-time seam

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/hovorka/learning/PredictionReplayEngine.java` (extract the per-anchor assembly from `prepareAnchors` into `private AnchorContext prepareAnchorAt(long t0, List<Event> sortedEvents)`; `prepareAnchors` loops over candidate times calling it; keep `sortedEvents` as a field for reuse)
- Test: `src/test/java/che/glucosemonitorbe/hovorka/learning/PredictionReplayEngineTest.java`

**Interfaces:**
- Produces: `public List<AnchorSample> replayAt(long anchorEpochMs, TwinScales scales)` on `PredictionReplayEngine`. Same events window, trend and sampling as `replay` for that anchor. Returns an empty list when there is no CGM reading within `alignToleranceMs` of `anchorEpochMs`.

- [ ] **Step 1: Write the failing test** `replayAtMatchesWhatReplayEmitsForThatAnchor` in `PredictionReplayEngineTest`: reuse the synthetic trace and events from `producesFiniteInRangeSamplesFromASyntheticTrace`, `strideMin = 30`, `sampleHorizons = {30,60,90,120}`. Take the anchor `t = firstReadingEpochMs + 30*60_000`. Assert `engine.replayAt(t, TwinScales.neutral())` equals `engine.replay(TwinScales.neutral())` sublist `[4, 8)` (anchor index 1, four samples each). Second test `replayAtWithNoReadingNearTheAnchorIsEmpty`: anchor 3 h after the last reading returns an empty list.
- [ ] **Step 2: Run** `./gradlew test --tests '*PredictionReplayEngineTest' -x jacocoTestReport`. Expected: FAIL, `replayAt` not defined.
- [ ] **Step 3: Implement** the extraction and `replayAt`. The sample loop in `replay` moves into a private helper used by both, so the two cannot drift.
- [ ] **Step 4: Run** `./gradlew test --tests '*PredictionReplayEngineTest' --tests '*DigitalTwinCalibratorTest' --tests '*HovorkaGlucosePredictionServiceTest' -x jacocoTestReport`. Expected: PASS (existing behaviour unchanged).
- [ ] **Step 5: Commit** `feat(test-seam): replay the forecast at an exact anchor time` with `git add` of the two files.

### Task 2: Bolus-log loader

**Files:**
- Modify: `src/test/java/che/glucosemonitorbe/azt1d/Azt1dDataset.java`
- Test: `src/test/java/che/glucosemonitorbe/azt1d/Azt1dBolusLogTest.java`

**Interfaces:**
- Produces: `public record BolusRecord(long epochMs, String bolusType, double carbsG, double totalU, double correctionU, double foodU)`; `public static List<BolusRecord> loadBolusLog(Path csv) throws IOException`; `public static Map<String, List<BolusRecord>> loadAllBolusLogs(Path datasetRoot) throws IOException` (key `"Subject N"`); `public static boolean isAutomatic(BolusRecord r)` (type starts with `Automatic Bolus`); `public static boolean isManual(BolusRecord r)` (`totalU > 0` and not automatic). Rows with neither insulin nor carbs are not returned. Columns are resolved from the header (`BolusType`, `CorrectionDelivered`, `FoodDelivered`, `TotalBolusInsulinDelivered`, `CarbSize`, `EventDateTime`), as `load` does. `Subject` is unchanged.

- [ ] **Step 1: Write the failing test** `Azt1dBolusLogTest` with a `@TempDir` CSV using the real header order `EventDateTime,DeviceMode,BolusType,Basal,CorrectionDelivered,TotalBolusInsulinDelivered,FoodDelivered,CarbSize,CGM`. Tests: `readsAMealBolusWithCarbs` (`Standard`, carbs 45, food 4.5, total 4.5 gives `isManual`), `readsACorrectionOnlyDose` (`Standard/Correction`, correction 2.0, total 2.0, carbs 0), `flagsAutomaticBoluses` (`Automatic Bolus/Correction` gives `isAutomatic` and not `isManual`), `skipsRowsWithNoInsulinAndNoCarbs`, and `resolvesColumnsFromTheHeaderInAnotherOrder` (CGM column first).
- [ ] **Step 2: Run** `--tests '*Azt1dBolusLogTest'`. Expected: FAIL, symbols not defined.
- [ ] **Step 3: Implement** the record, the two loaders and the two predicates in `Azt1dDataset`.
- [ ] **Step 4: Run** the same command plus `--tests '*Azt1dCalibrationValidationTest'`. Expected: PASS (the validation test still skips without the dataset).
- [ ] **Step 5: Commit** `test(azt1d): load the bolus log with bolus types`.

### Task 3: Episode model and correction-drop miner

**Files:**
- Create: `src/test/java/che/glucosemonitorbe/azt1d/ScenarioKind.java`, `ScenarioEpisode.java`, `ScenarioMiner.java`
- Test: `src/test/java/che/glucosemonitorbe/azt1d/ScenarioMinerTest.java`

**Interfaces:**
- Consumes: `Azt1dDataset.Subject`, `Azt1dDataset.BolusRecord`, `isAutomatic`, `isManual`.
- Produces: `enum ScenarioKind { CORRECTION_DROP, MEAL_WITH_BOLUS, MEAL_NO_BOLUS, OVERNIGHT_STEADY }`; `record ScenarioEpisode(String subjectId, ScenarioKind kind, long startEpochMs, double startMmol, int horizonMin)`; `static List<ScenarioEpisode> ScenarioMiner.mine(Azt1dDataset.Subject s, List<Azt1dDataset.BolusRecord> log, ScenarioKind kind)` returning at most 6 episodes, evenly spaced, sorted by time. Constants as in Global Constraints, defined once in `ScenarioMiner`. An episode needs a CGM reading within 5 min of the start and at least 80% of the 5-minute readings across the horizon.

- [ ] **Step 1: Write the failing tests** in `ScenarioMinerTest` using in-memory subjects (`new Azt1dDataset.Subject(id, cgm, events, Azt1dDataset.Profile.defaults())`, a flat 10 mmol/L trace every 5 min for 2 days). Names and assertions: `aCleanCorrectionQualifies` (one 4 U `Standard/Correction` at 14:00 gives exactly one episode with `startMmol` 10.0 and `horizonMin` 180); `belowNineMmolDoesNotQualify` (trace at 7.0); `carbsWithinThreeHoursDisqualify`; `anAutomaticBolusInTheWindowDisqualifies` (automatic bolus 90 min before the start, and again 100 min after); `twoDosesTwentyMinutesApartDisqualifyBoth`; `aCgmGapAtTheStartDisqualifies` (readings removed from -10 to +10 min); `aGapInsideTheHorizonBelowEightyPercentDisqualifies`; `capsAtSixEvenlySpaced` (10 clean doses on 10 days give 6 episodes whose start times are strictly increasing and include the first and the last).
- [ ] **Step 2: Run** `--tests '*ScenarioMinerTest'`. Expected: FAIL, classes not defined.
- [ ] **Step 3: Implement** `ScenarioKind`, `ScenarioEpisode` and `ScenarioMiner.mine` for `CORRECTION_DROP`; other kinds return an empty list for now. Shared window helpers (`hasManualBolus(log, fromMin, toMin)`, `hasCarbs(...)`, `hasAutomatic(...)`, `coverageOk(...)`) are private static methods reused by Task 4.
- [ ] **Step 4: Run** `--tests '*ScenarioMinerTest'`. Expected: PASS.
- [ ] **Step 5: Commit** `test(azt1d): mine clean correction episodes`.

### Task 4: Meal and overnight miners

**Files:**
- Modify: `src/test/java/che/glucosemonitorbe/azt1d/ScenarioMiner.java`
- Test: `src/test/java/che/glucosemonitorbe/azt1d/ScenarioMinerTest.java`

**Interfaces:**
- Consumes: Task 3's `ScenarioMiner.mine` and its private window helpers.
- Produces: `mine` now handles `MEAL_WITH_BOLUS`, `MEAL_NO_BOLUS`, `OVERNIGHT_STEADY` with the Global Constraints rules; the episode start is the meal time (or the overnight start), `startMmol` the reading at that time.

- [ ] **Step 1: Write the failing tests** (same file, same in-memory helper): `aMealWithAManualBolusInFifteenMinutesQualifiesAsMealWithBolus` (45 g at 12:00, 4.5 U at 12:10); `aMealWithABolusSixteenMinutesLaterIsNotMealWithBolus`; `aMealWithNoBolusForThreeHoursQualifiesAsMealNoBolus`; `aMealUnderThirtyGramsDoesNotQualify` (25 g); `aSecondMealInsideTheWindowDisqualifies` (45 g at 12:00 and 20 g at 13:00); `anOvernightStretchQualifiesBetweenMidnightAndTwo` (start 01:00, nothing else); `anOvernightStartAtThreeDoesNotQualify`; `anOvernightWithAManualBolusAtMidnightDisqualifies`; `anAutomaticBolusAfterAMealDisqualifiesIt` (automatic 60 min after the meal).
- [ ] **Step 2: Run** `--tests '*ScenarioMinerTest'`. Expected: FAIL on the new tests.
- [ ] **Step 3: Implement** the three kinds in `ScenarioMiner.mine`.
- [ ] **Step 4: Run** `--tests '*ScenarioMinerTest'`. Expected: PASS.
- [ ] **Step 5: Commit** `test(azt1d): mine meal and overnight episodes`.

### Task 5: Scenario forecaster

**Files:**
- Create: `src/test/java/che/glucosemonitorbe/azt1d/ScenarioForecaster.java`
- Test: `src/test/java/che/glucosemonitorbe/azt1d/ScenarioForecasterTest.java`

**Interfaces:**
- Consumes: Task 1 `replayAt`, Task 3 `ScenarioEpisode`, `Azt1dCalibrationValidationTest.rawPredictor()` and `personalParams(Profile)`, `DigitalTwinCalibrator`, `RapidInsulinIobParameters(4.5, 55.0)`.
- Produces: `enum ScaleMode { NEUTRAL, TWIN }`; `record ForecastResult(ScenarioEpisode episode, ScaleMode mode, TwinScales scales, boolean twinApplied, List<AnchorSample> samples)` with `OptionalDouble predictedAt(int min)` and `OptionalDouble actualAt(int min)` (from the sample with that `horizonMin`); `final class ScenarioForecaster` with `ScenarioForecaster(Azt1dDataset.Subject subject)`, `ForecastResult forecast(ScenarioEpisode ep, ScaleMode mode)` and `ForecastResult forecastWith(ScenarioEpisode ep, TwinScales scales)`. The engine config is `horizonMin = 180`, `sampleHorizons = {30,60,90,120,150,180}`. TWIN fits on readings and events strictly before the local day of `ep.startEpochMs()` (80/20 train/validation split as in the validation test), caches the fit per local date, uses neutral scales with `twinApplied = false` when fewer than 500 prior readings exist or the fit is not `improved()`.

- [ ] **Step 1: Write the failing tests**: `neutralForecastOfACorrectionFallsBelowTheStartValue` (synthetic subject: flat 10 mmol/L, one 4 U dose; `predictedAt(120)` is below `startMmol`); `twinModeWithLittleHistoryFallsBackToNeutral` (episode on day 1; `twinApplied` is false and scales equal `TwinScales.neutral()`); `theTwinFitNeverSeesDataAfterTheEpisodeDay` (two synthetic subjects identical before the episode day and different after it; their TWIN scales are equal); `forecastWithUsesTheGivenScalesVerbatim` (scales with `isfScale` 0.5 give a smaller fall at 120 than neutral).
- [ ] **Step 2: Run** `--tests '*ScenarioForecasterTest'`. Expected: FAIL, class not defined.
- [ ] **Step 3: Implement** `ScenarioForecaster`, `ScaleMode` and `ForecastResult`.
- [ ] **Step 4: Run** `--tests '*ScenarioForecasterTest'`. Expected: PASS.
- [ ] **Step 5: Commit** `test(azt1d): forecast an episode with a neutral or earlier-data twin`.

### Task 6: Scenario rules

**Files:**
- Create: `src/test/java/che/glucosemonitorbe/azt1d/ScenarioRules.java`
- Test: `src/test/java/che/glucosemonitorbe/azt1d/ScenarioRulesTest.java`

**Interfaces:**
- Consumes: Task 5 `ForecastResult`.
- Produces: `enum Outcome { PASS, FAIL, NOT_APPLICABLE }`; `record Verdict(Outcome outcome, String rule, double errorMmol, String detail)`; `static Verdict ScenarioRules.evaluate(ForecastResult r)`; constants `FLAT_FORECAST_RATIO = 0.6`, `MIN_REAL_FALL = 1.0`, `MIN_REAL_RISE = 2.0`, `OVERNIGHT_BAND = 2.0`. `errorMmol` is `|predicted120 - actual120|` for all kinds.
- Rules: *CORRECTION_DROP*: with `realFall = start - actual120` and `forecastFall = start - predicted120`, NOT_APPLICABLE when `realFall < 1.0`; FAIL (`rule = "flat-forecast"`) when `forecastFall < 0.6 * realFall`; else PASS. *MEAL_WITH_BOLUS* and *MEAL_NO_BOLUS*: NOT_APPLICABLE when the real maximum over 30-180 min minus start is under 2.0; FAIL (`"no-rise"`) when the forecast maximum minus start is under 1.0; else PASS. *OVERNIGHT_STEADY*: NOT_APPLICABLE when any real value over 30-180 min leaves start ±2.0; FAIL (`"drift"`) when `|predicted120 - start| > 2.0`; else PASS.

- [ ] **Step 1: Write the failing tests** building `ForecastResult` directly from `AnchorSample` lists: `aCorrectionWhoseForecastBarelyMovesFailsAsFlat` (start 10.0, real fall 3.0, forecast fall 0.5: FAIL, rule `flat-forecast`); `aCorrectionWhoseForecastFollowsRealityPasses` (forecast fall 2.8); `aCorrectionWhereRealityDidNotFallIsNotApplicable` (real fall 0.4); `aMealForecastThatNeverRisesFails`; `aMealForecastThatRisesPasses`; `anOvernightForecastThatDriftsFails`; `errorIsTheAbsoluteGapAtOneHundredTwentyMinutes`.
- [ ] **Step 2: Run** `--tests '*ScenarioRulesTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** `ScenarioRules`.
- [ ] **Step 4: Run** `--tests '*ScenarioRulesTest'`. Expected: PASS.
- [ ] **Step 5: Commit** `test(azt1d): pass/fail rules per scenario kind`.

### Task 7: Baseline and the data-gated sweep

**Files:**
- Create: `src/test/java/che/glucosemonitorbe/azt1d/ScenarioBaseline.java`, `Azt1dScenarioSweepTest.java`
- Modify: `build.gradle` (add `'azt1d.baseline'` and `'azt1d.baseline.update'` to the forwarded property list)
- Test: `src/test/java/che/glucosemonitorbe/azt1d/ScenarioBaselineTest.java`

**Interfaces:**
- Consumes: Tasks 2-6.
- Produces: `final class ScenarioBaseline` with `static Path defaultPath()` (`-Dazt1d.baseline`, else `~/.glucose-monitor-test/azt1d-scenario-baseline.json`), `static Optional<ScenarioBaseline> load(Path p)` (empty when the file is absent, `IllegalStateException` naming the path when it is unreadable), `void save(Path p)`, `void put(ScenarioKind k, String subjectId, double medianErrorMmol, int failCount)`, and `List<String> regressions(ScenarioBaseline current)`; a regression is a `failCount` above the recorded one, or a median error above `recorded * 1.15 + 0.2`. `Azt1dScenarioSweepTest.sweepReportsAndHoldsTheBaseline` mines up to 6 episodes per kind per subject, forecasts each with TWIN and NEUTRAL, prints a table per kind and subject (episodes, pass, fail, not-applicable, median error twin and neutral, `⚠fb` for subjects with `!profile().plausible()`), and: with no baseline (or `-Dazt1d.baseline.update=true`) saves one and passes with a printed "baseline created"; otherwise asserts `regressions` is empty. Failing scenarios at baseline time are printed under "known gaps".

- [ ] **Step 1: Write the failing tests** in `ScenarioBaselineTest` (`@TempDir`): `roundTripsEntries`, `missingFileIsEmpty`, `corruptFileThrowsWithThePath`, `aHigherFailCountIsARegression`, `aMedianErrorWithinTheMarginIsNotARegression` (recorded 1.0, current 1.34 passes; 1.36 fails).
- [ ] **Step 2: Run** `--tests '*ScenarioBaselineTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** `ScenarioBaseline` (JSON via Jackson, already on the test classpath) and `Azt1dScenarioSweepTest`; edit `build.gradle`.
- [ ] **Step 4: Run** `--tests '*ScenarioBaselineTest'` (expected PASS), then the sweep on the owner's data: `./gradlew test --tests '*Azt1dScenarioSweepTest' -x jacocoTestReport -Dazt1d.dir="<path>" -Dazt1d.baseline=build/azt1d-scenario-baseline-trial.json`. Expected: PASS, "baseline created", a printed report covering all 25 subjects. Keep the report for the owner; do not tune any constant to change it.
- [ ] **Step 5: Commit** `test(azt1d): baseline and data-gated scenario sweep`.

### Task 8: Twin sanity and the pinned-twin meta-test

**Files:**
- Create: `src/test/java/che/glucosemonitorbe/azt1d/PinnedTwinScenarioTest.java`
- Modify: `src/test/java/che/glucosemonitorbe/azt1d/Azt1dScenarioSweepTest.java` (add the twin-sanity block)

**Interfaces:**
- Consumes: Tasks 5-7. Produces: in the sweep, a per-subject line "isf at limit / ag at limit" using `TwinScales.MIN_SCALE` / `MAX_SCALE` with a tolerance of 1e-3, a stored baseline count under key `TWIN_SANITY|all`, and an assertion that the count does not exceed it.

- [ ] **Step 1: Write the failing test** `PinnedTwinScenarioTest.aTwinPinnedAtTheFloorFailsTheCorrectionScenarioWhileNeutralPasses`: synthetic subject, one 4 U correction from 11 mmol/L; take `neutral = forecaster.forecast(ep, NEUTRAL)`; build the "real" trace from the neutral model's own 30-180 min values so neutral passes by construction; `pinned = forecaster.forecastWith(ep, new TwinScales(TwinScales.MIN_SCALE, 1, 1, 1))`. Assert `ScenarioRules.evaluate(neutral).outcome() == PASS` and `evaluate(pinned)` is FAIL with rule `flat-forecast`. If the pinned fall lands within 10% of `0.6 * realFall`, report it; do not change `FLAT_FORECAST_RATIO` without telling the owner.
- [ ] **Step 2: Run** `--tests '*PinnedTwinScenarioTest'`. Expected: FAIL until the forecaster's synthetic "real" trace helper exists; add that helper to the test file.
- [ ] **Step 3: Implement** the twin-sanity block in the sweep.
- [ ] **Step 4: Run** `--tests '*PinnedTwinScenarioTest'` (PASS) and the sweep command from Task 7 (PASS, twin-sanity line printed).
- [ ] **Step 5: Commit** `test(azt1d): catch a twin pinned at its floor`.

### Task 9: Fixtures: export, read, guard

**Files:**
- Create: `src/test/java/che/glucosemonitorbe/azt1d/ScenarioFixture.java`, `ScenarioFixtureExporter.java`, `Azt1dScenarioFixtureExportTest.java`, `ScenarioFixtureRoundTripTest.java`, `ScenarioFixtureGuardTest.java`, `ScenarioFixturesTest.java`

**Interfaces:**
- Consumes: Tasks 3-6.
- Produces: `record ScenarioFixture(String id, ScenarioKind kind, int horizonMin, int hourOfDay, int weekday, FixtureProfile profile, double isfScale, double agScale, double egpScale, boolean twinApplied, List<FixtureReading> readings, List<FixtureEvent> events, FixtureExpectation expected)` with `record FixtureReading(int minute, double mmol)`, `record FixtureEvent(int minute, double carbsG, double insulinU)`, `record FixtureProfile(double weightKg, double isfMmolPerU, double carbRatioGPerU, double basalUPerH)`, `record FixtureExpectation(Outcome outcome, double errorMmol)`; `static ScenarioFixture ScenarioFixtureExporter.export(Azt1dDataset.Subject s, ScenarioEpisode ep, ForecastResult r, int index)` (minutes relative to the start, readings and events from -480 to +180); `static void write(ScenarioFixture f, Path dir)` and `static ScenarioFixture read(Path file)`; `ForecastResult ScenarioFixture.replay()` rebuilds a subject on a fixed placeholder date matching `hourOfDay` and `weekday` and runs `forecastWith` using the stored scales. Fixture files live in `src/test/resources/scenarios/azt1d/*.json`; the export test writes to `build/scenario-fixtures/` only.

- [ ] **Step 1: Write the failing tests**: `ScenarioFixtureRoundTripTest.anExportedEpisodeReplaysToItsRecordedOutcome` (synthetic subject, export, write to `@TempDir`, read, replay; outcome and error equal the recorded ones within 1e-6); `ScenarioFixtureGuardTest.noFixtureCarriesADateOrASubjectId` (scan `src/test/resources/scenarios/azt1d/*.json` for `\d{4}-\d{2}-\d{2}`, `Subject`, `subject`; assert none) and `fixturesStayUnderThreeHundredKilobytes`; a negative check `theGuardCatchesADate` on a string with `2024-02-11`; `ScenarioFixturesTest.committedFixturesDoNotRegress` (each committed fixture replays with outcome not worse than recorded and error within `recorded * 1.15 + 0.2`; with zero files it passes and prints "no committed fixtures yet"); `Azt1dScenarioFixtureExportTest.exportsACuratedSet` (data-gated: up to 6 episodes per kind spread across subjects, written to `build/scenario-fixtures/`, prints the count and total size).
- [ ] **Step 2: Run** `--tests '*ScenarioFixture*'`. Expected: FAIL, classes not defined.
- [ ] **Step 3: Implement** the fixture types, exporter, reader, replay and the tests above. No allow-list change to `NoPatientPhiDumpsInRepoTest` is needed: it only matches root-level `cgm_readings_*.csv` and `notes_*.csv`, so the new guard test is the check for this folder.
- [ ] **Step 4: Run** `--tests '*ScenarioFixture*'` (PASS), then the export on the owner's data with `-Dazt1d.dir=...` and confirm files appear in `build/scenario-fixtures/` and the guard test passes when pointed at that folder (`-Dscenario.fixtures.dir=build/scenario-fixtures`).
- [ ] **Step 5: Commit** `test(azt1d): de-identified scenario fixtures, exporter and guard`. Do not copy anything into `src/test/resources/scenarios/azt1d/` until the owner confirms the AZT1D licence.
