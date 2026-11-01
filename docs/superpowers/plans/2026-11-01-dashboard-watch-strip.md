# Dashboard Watch Strip Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A one-line strip at the top of the dashboard that appears only while something needs watching (low, high, or settling after a dose/meal/activity), with a computed suggestion and its basis, plus activity effects learned per user.

**Architecture:** The backend adds a `watch` object to the dashboard's `GlucoseCalculationsResponse`, computed after the forecast and the existing `carbAdvice` from the same calculation. URGENT_LOW reuses `carbAdvice` and the hypo-event flow; URGENT_HIGH runs a forecast-based correction through `InsulinCalculatorService`'s safety checks; CALM reads recent notes and IOB/COB. Activity learning is a separate, gated stage of the digital-twin calibration whose values reach the live forecast. iOS only renders the object and composes text in the user's unit.

**Tech Stack:** Spring Boot 3 / Java 21 / JUnit 5 + Mockito / Flyway / Postgres (backend); SwiftUI / XCTest (iOS).

**Spec:** `docs/superpowers/specs/2026-10-30-dashboard-watch-strip-design.md` (read §1a first: it lists what already exists and must be reused, not copied).

## Global Constraints

- Backend glucose is mmol/L end to end; mg/dL is display only (iOS).
- COB/IOB only from `GlucoseCalculationsService.activeCobIobInputs` (already on the response as `activeCarbsOnBoard` / `activeInsulinOnBoard`); hypo thresholds only from `service/observer/HypoThresholds` (`HYPO_MMOL` 3.9, `RECOVERY_MMOL` 4.5).
- Freshness only from `assistant.common.CgmFreshnessService.freshReading(userId, Instant)` (15 min, LibreLinkUp-down → empty).
- Pre-emptive low carbs only from `carbAdvice` (`assistant.advice.CarbAdviceService`); never a `carb_ratio` formula.
- Target = `user_settings.target_glucose_mmol` (default 5.55, 4.4–10.0); rescue carbs = `rescue_carbs_g` (default 15, 5–40). Both columns already exist.
- Schema changes edit the migration that owns the table (`V8__user_digital_twin.sql` for the twin); flag `flyway repair` on the dev DB (native Homebrew Postgres 15).
- What-if requests (with `prospectiveNotes`) never compute `watch` and never touch any hold.
- Tests mock-first (London school). Files under 500 lines.
- iOS: do not run the app; verify with `xcodebuild … build-for-testing` (and `test` only if the user allows the simulator). New Swift files are added to `GlucoseMonitor.xcodeproj/project.pbxproj` by hand.
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Twin applied, ISF fallback:** a user with no applied twin gets ISF scale 1.0, never 0 or null → test in Task 1.
2. **Dashboard polled twice on the same reading:** the hold must not count it as a new reading → test in Task 3.
3. **Hypo event CONFIRMED long ago with no `recovered_at`:** must not pin the strip red forever. Only events detected within `HypoThresholds.MAX_OPEN_MINUTES` count → test in Task 2.
4. **Activity fit with all activity anchors in the training half:** validation has nothing to score, so learned gains must not be applied → test in Task 5.
5. **Strip shown while the app shows a cached or stale calculation:** must be hidden → test in Task 8.

---

## Backend (`glucose-monitor-be`)

### Task 1: Twin-scaled ISF and forecast correction entry point

**Files:**
- Create: `src/main/java/che/glucosemonitorbe/watch/TwinIsfService.java`
- Modify: `src/main/java/che/glucosemonitorbe/service/InsulinCalculatorService.java` (new public method after `calculateRecommendedInsulin`)
- Test: `src/test/java/che/glucosemonitorbe/watch/TwinIsfServiceTest.java`, `src/test/java/che/glucosemonitorbe/service/InsulinCalculatorForecastCorrectionTest.java`

**Interfaces:**
- Produces: `TwinIsfService.effectiveIsf(UUID userId, UserSettingsDTO settings, LocalDateTime time): double` = `settings.getEffectiveIsf(time)` × `DigitalTwinService.activeScales(userId).map(s -> s.clamped().isfScale()).orElse(1.0)`.
- Produces: `InsulinCalculatorService.calculateForecastCorrection(String username, double currentReadingMmol, double landingMmol, double targetMmol, double isf): double` returns raw units `max(0, (landingMmol − targetMmol) / isf)`, unrounded. Throws `DosingRefusedException` with `GLUCOSE_BELOW_SAFE_THRESHOLD` when `currentReadingMmol < HYPO_REFUSAL_THRESHOLD_MMOL`, `SETTINGS_INVALID` when `isf` is not finite or ≤ 0, `DOSE_EXCEEDS_MAX_BOLUS` when units > `maxBolusUnits(settings)`. Uses the existing `requireSettings(username)`. No IOB parameter exists, so a double count is impossible.

- [ ] **Step 1: Write the failing tests**

```java
// TwinIsfServiceTest
@Test void noAppliedTwin_usesSettingsIsf() { when(twin.activeScales(id)).thenReturn(Optional.empty());
    when(settings.getEffectiveIsf(t)).thenReturn(2.0); assertEquals(2.0, svc.effectiveIsf(id, settings, t), 1e-9); }
@Test void appliedTwin_multipliesClampedScale() { when(twin.activeScales(id)).thenReturn(Optional.of(TwinScales.of(3.0, 1.0)));
    when(settings.getEffectiveIsf(t)).thenReturn(2.0); assertEquals(4.0, svc.effectiveIsf(id, settings, t), 1e-9); } // clamped to 2.0

// InsulinCalculatorForecastCorrectionTest
@Test void aimsFromLandingNotCurrent() { assertEquals(2.25, calc.calculateForecastCorrection("u", 9.0, 11.0, 5.5, 2.0), 1e-9); }
@Test void refusesBelow39() { assertRefused(DosingRefusalReason.GLUCOSE_BELOW_SAFE_THRESHOLD, () -> calc.calculateForecastCorrection("u", 3.8, 11.0, 5.5, 2.0)); }
@Test void refusesNonPositiveIsf() { assertRefused(DosingRefusalReason.SETTINGS_INVALID, () -> calc.calculateForecastCorrection("u", 9.0, 11.0, 5.5, 0.0)); }
@Test void refusesOverMaxBolus() { /* weight 70 → max 21 U */ assertRefused(DosingRefusalReason.DOSE_EXCEEDS_MAX_BOLUS, () -> calc.calculateForecastCorrection("u", 9.0, 30.0, 5.5, 1.0)); }
@Test void landingBelowTarget_isZero() { assertEquals(0.0, calc.calculateForecastCorrection("u", 9.0, 5.0, 5.5, 2.0), 1e-9); }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew test --tests 'che.glucosemonitorbe.watch.TwinIsfServiceTest' --tests 'che.glucosemonitorbe.service.InsulinCalculatorForecastCorrectionTest'`
Expected: compilation failure (types/methods missing).

- [ ] **Step 3: Implement both methods as specified in Interfaces.** `TwinIsfService` is a `@Service` with `DigitalTwinService` injected.

- [ ] **Step 4: Run the tests again.** Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/che/glucosemonitorbe/watch/TwinIsfService.java src/main/java/che/glucosemonitorbe/service/InsulinCalculatorService.java src/test/java/che/glucosemonitorbe/watch src/test/java/che/glucosemonitorbe/service/InsulinCalculatorForecastCorrectionTest.java
git commit -m "feat(watch): twin-scaled ISF and forecast-landing correction"
```

### Task 2: Watch DTO, low and calm states

**Files:**
- Create: `src/main/java/che/glucosemonitorbe/watch/WatchStrip.java`
- Create: `src/main/java/che/glucosemonitorbe/watch/WatchStripService.java`
- Modify: `src/main/java/che/glucosemonitorbe/dto/GlucoseCalculationsResponse.java` (field `private WatchStrip watch;` with comment "null = no strip")
- Modify: `src/main/java/che/glucosemonitorbe/service/HypoEventService.java` (new read method)
- Test: `src/test/java/che/glucosemonitorbe/watch/WatchStripServiceTest.java`

**Interfaces:**
- Produces (`WatchStrip.java`; all glucose mmol/L, times user wall time):

```java
public record WatchStrip(State state, Reason reason, Suggestion suggestion,
                         GlucosePoint low, GlucosePoint landing, Double iob, Double cob,
                         LocalDateTime nextCheckAt, ActivityEstimate activityEstimate) {
    public enum State { CALM, URGENT_LOW, URGENT_HIGH }            // priority: URGENT_LOW > URGENT_HIGH > CALM
    public enum Reason { LOW_NOW, TREATING_LOW, FORECAST_LOW, CARBS_LANDING,
                         HIGH_FORECAST, INSULIN_LANDING, SMALL_RISE, WATCHING, DIP_AHEAD }
    public enum Kind { CARBS, INSULIN, NO_ACTION, REFUSED }
    public enum ActivityEstimate { LEARNED, DEFAULT }
    public record Suggestion(Kind kind, Integer grams, Double units, String refusalCode,
                             Double targetMmol, Double isf, Double minWithCarbs) {}
    public record GlucosePoint(LocalDateTime at, double mmol) {}
}
```

- Produces: `HypoEventService.findUnrecoveredConfirmed(UUID userId): Optional<HypoEventDTO>`, the newest `CONFIRMED` event with `recovered_at` null and `detectedAt` within `HypoThresholds.MAX_OPEN_MINUTES`. Add `recoveredAt` to `HypoEventDTO` only if the filter needs it at the DTO level; otherwise filter on the entity via `HypoEventRepository`.
- Produces: `WatchStripService.evaluate(UUID userId, GlucoseCalculationsResponse resp): Optional<WatchStrip>` (Task 3 adds HIGH and the hold; Task 4 wires it in). `now` = `resp.getCalculatedAt()`; freshness uses an injected `Clock` (`Clock.systemUTC()` default, package-private constructor for tests), like `CarbAdviceService`.
- Consumes: `CgmFreshnessService.freshReading`, `HypoEventService.findOpenEvent` / `findUnrecoveredConfirmed`, `NoteRepository.findByUserIdAndTimestampBetween`, `UserSettingsService` (target, rescue carbs), `resp.getCarbAdvice()`, `resp.getPredictionPath()`, `resp.getActiveInsulinOnBoard()`, `resp.getActiveCarbsOnBoard()`, `resp.getFourHourPrediction()`.

Rules, evaluated in order, first match wins (spec §6.2–§6.3). `reading` = the fresh stored reading's mmol. The client's `currentGlucose` is never used.

1. No fresh reading → empty.
2. `reading < 3.9` or an open hypo event → URGENT_LOW, `LOW_NOW`, `CARBS` with `grams = rescue_carbs_g`, `nextCheckAt = now + 15 min`.
3. An unrecovered confirmed event, unless `reading ≥ 4.5` and every forecast point in (now, now+60] is ≥ 4.5 → URGENT_LOW, `TREATING_LOW`, `NO_ACTION`, `nextCheckAt = now + 15 min`.
4. `carbAdvice` present → URGENT_LOW:
   - `TAKE_NOW` → `FORECAST_LOW`, `CARBS`, grams from it, `minWithCarbs = minWith`;
   - `STILL_LANDING` → `CARBS_LANDING`, `NO_ACTION`, `grams = landingGrams`.
   - In both cases `low = (minWithoutAt, minWithout)` and `nextCheckAt = recheckAt`.
5. (Task 3: URGENT_HIGH.)
6. A note in [now−4 h, now] with rapid insulin (`insulin > 0`, type not `long_acting`), carbs ≥ 10 g, or type `activity`, and IOB ≥ 0.3 or COB ≥ 5 → CALM, `NO_ACTION`.
   - `landing` is the forecast point at +240 min (or the last one).
   - If the forecast minimum over (now, now+240] is < 4.5 → `DIP_AHEAD` with `low` at that point; otherwise `WATCHING`.
   - `nextCheckAt` is the earlier of now + 60 min and the time of the forecast minimum (DIP_AHEAD) or peak (WATCHING).
7. Else empty.

`iob`/`cob` are copied from the response on every strip. `activityEstimate` is `DEFAULT` here; Task 7 switches it.

- [ ] **Step 1: Write the failing tests** (mocks for every collaborator; a helper builds a response with a given forecast)

```java
@Test void noFreshReading_noStrip()
@Test void readingBelow39_lowNow_rescueGrams()            // rescue_carbs_g 20 → grams 20, nextCheckAt now+15
@Test void clientGlucoseIgnored_storedReadingWins()       // resp.currentGlucose 3.0, stored 6.0, no other trigger → empty
@Test void confirmedUnrecovered_staysUntilRecoveredAndHolding()   // reading 4.6 but forecast 4.3 at +40 → TREATING_LOW; forecast ≥4.5 → empty
@Test void staleConfirmedEvent_ignored()                  // repository returns none past MAX_OPEN_MINUTES → no TREATING_LOW (Review Focus 3)
@Test void carbAdviceTakeNow_copiedVerbatim()             // grams, minWithout/At, minWith, recheckAt
@Test void carbAdviceStillLanding_noCarbs()               // kind NO_ACTION, grams = landingGrams
@Test void calm_afterDoseWithIob_watchingWithLanding()
@Test void calm_dipAhead_whenMinUnder45()
@Test void calm_requiresIobOrCob()                         // dose 3 h ago, IOB 0.2, COB 0 → empty
@Test void longActingAloneIsNotADose()
@Test void lowBeatsCalm()                                  // order check
```

- [ ] **Step 2: Run** `./gradlew test --tests 'che.glucosemonitorbe.watch.WatchStripServiceTest'`. Expected: compilation failure.
- [ ] **Step 3: Implement** `WatchStrip`, the response field, `findUnrecoveredConfirmed`, and `WatchStripService.evaluate` with rules 1–4, 6, 7.
- [ ] **Step 4: Run the tests again.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(watch): watch strip DTO with low and calm states`

### Task 3: URGENT_HIGH correction and the flicker hold

**Files:**
- Create: `src/main/java/che/glucosemonitorbe/watch/WatchStateHold.java`
- Modify: `src/main/java/che/glucosemonitorbe/watch/WatchStripService.java`
- Test: `src/test/java/che/glucosemonitorbe/watch/WatchStateHoldTest.java`, extend `WatchStripServiceTest`

**Interfaces:**
- Consumes: Task 1's `TwinIsfService.effectiveIsf` and `InsulinCalculatorService.calculateForecastCorrection`.
- Produces: `WatchStateHold.apply(UUID userId, LocalDateTime readingTime, @Nullable WatchStrip candidate): Optional<WatchStrip>` (`@Component`, in-memory `ConcurrentHashMap`, same pattern as `assistant.advice.CarbAdviceHold`):
  - candidate URGENT_LOW → clear the hold, return candidate (lows have their own hold and hysteresis);
  - candidate HIGH/CALM with priority ≥ held → store `(candidate, readingTime, streak 0)`, return candidate;
  - otherwise (null or lower priority) → if `readingTime` is after the held reading time, streak+1; streak 2 drops the hold; a gap longer than `CgmFreshnessService.MAX_AGE` drops it at once. Return held or candidate.
  - `clear(UUID userId)`.

Rule 5 (between low and calm). Fires when `twoHourPrediction > 10.0` and `fourHourPrediction > 10.0` (`HIGH_MMOL = 10.0`). Then URGENT_HIGH with `landing = (now+240, fourHourPrediction)`, `nextCheckAt = now + 60 min`, and:
- any rapid-insulin note in [now−2 h, now] → `INSULIN_LANDING`, `NO_ACTION`;
- else `units = floor(raw × 2) / 2` from `calculateForecastCorrection(username, reading, fourHourPrediction, target, effectiveIsf(now))`:
  - `units < 0.5` → `SMALL_RISE`, `NO_ACTION`;
  - else `HIGH_FORECAST`, `INSULIN`, `units`, `targetMmol`, `isf`.
- `DosingRefusedException` → `HIGH_FORECAST`, `REFUSED`, `refusalCode = reason.name()`.

No trend term enters the dose. `evaluate` passes its candidate through `hold.apply(userId, readingTime, candidate)`. A missing fresh reading clears the hold.

- [ ] **Step 1: Write the failing tests**

```java
// WatchStripServiceTest
@Test void high_correctionFromLanding_roundedDown()        // landing 11.0, target 5.5, isf 2.0 → raw 2.75 → 2.5 U
@Test void high_doseWithin2h_insulinLanding()
@Test void high_under05_smallRise()
@Test void high_refusal_surfacesCode()                      // calculator throws DOSE_EXCEEDS_MAX_BOLUS → REFUSED + code
@Test void high_needsBoth2hAnd4hAbove10()                   // 2 h 10.5, 4 h 9.0 → not HIGH
@Test void high_usesStoredReadingForRefusalCheck()
// WatchStateHoldTest
@Test void calmHeldForTwoNewReadings()
@Test void samePollingReading_doesNotCount()                // Review Focus 2
@Test void higherPriorityReplacesAtOnce()
@Test void lowClearsHold()
@Test void gapOverMaxAge_dropsHold()
```

- [ ] **Step 2: Run** `./gradlew test --tests 'che.glucosemonitorbe.watch.*'`. Expected: new tests FAIL.
- [ ] **Step 3: Implement** rule 5 and `WatchStateHold`, and route `evaluate`'s result through the hold.
- [ ] **Step 4: Run the tests again.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(watch): forecast-high correction and per-reading hold`

### Task 4: Wire into the dashboard call

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/service/GlucoseCalculationsService.java` (`calculateDashboard`)
- Test: `src/test/java/che/glucosemonitorbe/service/GlucoseCalculationsDashboardWatchTest.java`

**Interfaces:**
- Consumes: `WatchStripService.evaluate(UUID, GlucoseCalculationsResponse)`.
- Inject it `@Lazy @Autowired(required = false)` like `carbAdviceService`. Evaluate after `carbAdvice` is set (the strip reads it), in its own try/catch that logs WARN and leaves `watch` null. Skip it when `whatIf`.

- [ ] **Step 1: Write the failing tests**
  - `dashboard_setsWatchAfterCarbAdvice`: verify the `InOrder` carbAdvice → watch, and that `response.getWatch()` is the stub.
  - `whatIf_neverEvaluatesWatch`.
  - `watchFailure_keepsForecast`: evaluate throws, the response still has `predictionPath` and `watch == null`.
- [ ] **Step 2: Run** `./gradlew test --tests 'che.glucosemonitorbe.service.GlucoseCalculationsDashboardWatchTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** the wiring.
- [ ] **Step 4: Run** the test, then the full suite `./gradlew test`. Expected: PASS, apart from the known pre-existing `IsfMealWindowProfileServiceTest` time-zone failure. If it is still failing, note it rather than fix it.
- [ ] **Step 5: Commit** `feat(watch): watch strip on the dashboard response`

### Task 5: Activity gains through the forecast and replay

**Files:**
- Create: `src/main/java/che/glucosemonitorbe/hovorka/ActivityGains.java`
- Modify: `src/main/java/che/glucosemonitorbe/hovorka/HovorkaGlucosePredictionService.java` (the `customParams` overload near line 297 and `buildWithParams`, where `new ActivityModulation()` sits near line 386)
- Modify: `src/main/java/che/glucosemonitorbe/hovorka/learning/AnchorSampleSource.java`, `PredictionReplayEngine.java`
- Test: `src/test/java/che/glucosemonitorbe/hovorka/ActivityGainsForecastTest.java`

**Interfaces:**
- Produces: `record ActivityGains(double gainScale, double tailHalfLifeMin)`:
  - `NEUTRAL = new ActivityGains(1.0, ActivityModulation.TAIL_HALF_LIFE_MIN)`;
  - `clamped()`: gain to [0.5, 3.0], half-life to [60, 360];
  - `ActivityModulation modulation()` = `new ActivityModulation(GAIN_INSULIN * gainScale, GAIN_INDEP, tailHalfLifeMin)`.
- Produces: a `buildPredictionPath(...)` overload identical to the `customParams` one plus a trailing `ActivityGains gains`. The existing overload delegates with `NEUTRAL`. `buildWithParams` takes `gains` and builds `gains.clamped().modulation()`. With `NEUTRAL` the output is bit-identical to today.
- Produces: `AnchorSampleSource.replay(TwinScales scales, ActivityGains gains)` as a `default` method returning `replay(scales)`. `PredictionReplayEngine` overrides it and passes `gains` to the new overload; `replay(scales)` calls it with `NEUTRAL`.

- [ ] **Step 1: Write the failing tests**
  - `neutralGains_bitIdenticalToDefault`: same inputs with activity, existing overload vs new overload with `NEUTRAL`, every point equal.
  - `largerGain_lowerCurveAfterWalk`.
  - `longerHalfLife_lowerAt3h`.
  - `clampsOutOfRange`: (5.0, 1000) → (3.0, 360).
- [ ] **Step 2: Run** `./gradlew test --tests 'che.glucosemonitorbe.hovorka.ActivityGainsForecastTest'`. Expected: compilation failure.
- [ ] **Step 3: Implement** as specified.
- [ ] **Step 4: Run** it plus `./gradlew test --tests 'che.glucosemonitorbe.hovorka.*'`. Expected: PASS (existing Hovorka tests unchanged).
- [ ] **Step 5: Commit** `feat(twin): activity gains through forecast and replay`

### Task 6: Activity calibration stage

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/hovorka/learning/DigitalTwinCalibrator.java` (new method and record; keep the file under 500 lines, and if it would not fit, put the stage in a new `ActivityCalibrator.java` in the same package)
- Test: `src/test/java/che/glucosemonitorbe/hovorka/learning/ActivityCalibrationTest.java`

**Interfaces:**
- Produces: `record ActivityFit(ActivityGains gains, boolean applied, double maeWithout, double maeWith, int activityCount, String status)`.
- Produces: `ActivityFit fitActivity(AnchorSampleSource train, AnchorSampleSource val, TwinScales fixed, int activityCount)`:
  - if `activityCount < 5` (`MIN_ACTIVITIES = 5`), return `NEUTRAL`, not applied, status "fewer than 5 activities";
  - otherwise fit the vector `[gainScale, halfLife/120]` with the existing `robustFit` (IRLS + Huber + ridge to 1.0), bounds [0.5, 3.0] for both, `fixed` scales held constant, replaying via `train.replay(fixed, gains)`;
  - score the validation MAE of `val.replay(fixed, NEUTRAL)` vs `val.replay(fixed, fitted)` (no residual model on either side);
  - `applied = maeWith < maeWithout` and the validation sample count is ≥ `cfg.minValSamples`;
  - if `val` has fewer samples than that, mark it not applied, status "too little validation data".

- [ ] **Step 1: Write the failing tests.** Use a synthetic `AnchorSampleSource` whose "actual" is generated with known gains, e.g. (2.0, 240):
  - `recoversGeneratingGains`: within ±0.3 on gain and ±40 min on half-life, applied.
  - `fewerThan5Activities_defaults`.
  - `noImprovement_notApplied`: actual generated with `NEUTRAL`.
  - `emptyValidation_notApplied` (Review Focus 4).
  - `existingScalesUntouched`: `fixed` is passed through to every replay call unchanged.
- [ ] **Step 2: Run** `./gradlew test --tests 'che.glucosemonitorbe.hovorka.learning.ActivityCalibrationTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** `fitActivity`.
- [ ] **Step 4: Run the tests again.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(twin): gated activity calibration stage`

### Task 7: Persist activity gains, use them live, report the estimate

**Files:**
- Modify: `src/main/resources/db/migration/V8__user_digital_twin.sql`, adding:
  - `activity_gain_scale DOUBLE PRECISION NOT NULL DEFAULT 1.0`;
  - `activity_tail_half_life_min DOUBLE PRECISION NOT NULL DEFAULT 120`;
  - `activity_applied BOOLEAN NOT NULL DEFAULT FALSE`;
  - `activity_samples INTEGER NOT NULL DEFAULT 0`;
  - each with a `COMMENT ON COLUMN`, as the file does for the others.
- Modify: `entity/UserDigitalTwin.java`, `service/DigitalTwinCalibrationService.java` (`calibrateUser`, `persist`), `service/DigitalTwinService.java`, `hovorka/HovorkaGlucosePredictionService.java` (live overloads), `watch/WatchStripService.java`
- Test: `src/test/java/che/glucosemonitorbe/service/DigitalTwinActivityPersistenceTest.java`, extend `WatchStripServiceTest`

**Interfaces:**
- Consumes: Task 6's `fitActivity`, Task 5's overloads.
- `calibrateUser` runs `fitActivity` after the existing fit, with `fixed = result.scales()` and `activityCount` = activity notes in the training window. It runs whether or not the twin itself was applied, and is skipped when the activity provider is `NONE`. `persist` stores the four columns.
- Produces: `DigitalTwinService.activeActivityGains(UUID userId): Optional<ActivityGains>`. Present only when `activity_applied` is true, independent of the twin's own `applied`. Cached with the rest, and respects the digital-twin feature toggle.
- The live forecast overloads (the ones that do not take `customParams`, near lines 168/223/274) resolve `activeActivityGains(userId).orElse(NEUTRAL)`. Replay keeps passing gains explicitly, so calibration never sees live values.
- `WatchStripService` sets `activityEstimate = LEARNED` when `activeActivityGains` is present, otherwise `DEFAULT`.
- Flag in the commit message and the final report: the dev DB needs `flyway repair` (V8 checksum changed).

- [ ] **Step 1: Write the failing tests**
  - `persistsActivityFit`.
  - `activityAppliedIndependentOfTwinApplied`.
  - `liveForecastUsesLearnedGains`: verify the gains argument passed to `buildWithParams` (spy).
  - `watch_activityEstimateLearned`.
- [ ] **Step 2: Run** `./gradlew test --tests 'che.glucosemonitorbe.service.DigitalTwinActivityPersistenceTest' --tests 'che.glucosemonitorbe.watch.*'`. Expected: FAIL.
- [ ] **Step 3: Implement** the migration, entity fields, calibration call, persistence, `activeActivityGains`, live resolution and `activityEstimate`.
- [ ] **Step 4: Run the full suite** `./gradlew test`. Expected: PASS, apart from the known pre-existing time-zone failure.
- [ ] **Step 5: Commit** `feat(twin): learned activity gains in the live forecast (needs flyway repair)`

## iOS (`glucose-monitor-iphone`)

Build check used by every iOS task (log to a file, then grep; never pipe and read `$?`):

```bash
xcodebuild -scheme GlucoseMonitor -project GlucoseMonitor.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 16,OS=18.3.1' -derivedDataPath /tmp/gm-derived CODE_SIGNING_ALLOWED=NO build-for-testing > /tmp/gm-build.log 2>&1; grep -c "TEST BUILD SUCCEEDED" /tmp/gm-build.log
```

Expected: `1`. Before the first iOS commit, add `.superpowers/` to the iOS `.gitignore`.

### Task 8: Decode `watch`, compose its text, decide visibility

**Files:**
- Create: `GlucoseMonitor/WatchStrip.swift` (model and text)
- Modify: `GlucoseMonitor/BackendAPI.swift` (`GlucoseCalculationsResponse`: `let watch: WatchStrip?`), `GlucoseMonitor/AppState.swift` (`visibleWatchStrip`)
- Modify: `GlucoseMonitor.xcodeproj/project.pbxproj`
- Test: `GlucoseMonitorTests/WatchStripTests.swift`

**Interfaces:**
- Produces: `extension BackendAPI { struct WatchStrip: Decodable, Equatable }` mirroring Task 2's JSON:
  - enums decode by raw `String`; an unknown value decodes to `.unknown` and the strip is then hidden;
  - dates use the same decoder strategy as `CarbAdvice`.
- Produces: `enum WatchStripText { static func headline(_ s: BackendAPI.WatchStrip, unit: String) -> String; static func detail(_ s: BackendAPI.WatchStrip, unit: String) -> String }`. Glucose goes through `ChatFormatting.glucose(mmol:unit:)` + `ChatFormatting.unitLabel`, times through `ChatFormatting.clock`, units to one decimal, grams as integers. Copy (`{g}` = formatted glucose):

| reason | headline | detail |
|---|---|---|
| LOW_NOW | `Low now` | `Take {grams} g fast carbs · recheck {next}` |
| TREATING_LOW | `Treating low` | `Wait until {4.5} and holding · recheck {next}` |
| FORECAST_LOW | `Low likely ~{low.at} ({low})` | `Take {grams} g now → min {minWithCarbs} · recheck {next}` |
| CARBS_LANDING | `Low likely ~{low.at} ({low})` | `Wait for the {grams} g to act · recheck {next}` |
| HIGH_FORECAST, INSULIN | `High ahead: {landing} at {landing.at}` | `Correction {units} U (target {targetMmol}, ISF {isf}) · recheck {next}` |
| HIGH_FORECAST, REFUSED | same | `No dose: {refusal text} · recheck {next}` |
| INSULIN_LANDING | same | `Insulin still landing, no correction · recheck {next}` |
| SMALL_RISE | same | `Small rise, no correction · recheck {next}` |
| WATCHING | `Watching · {iob} U, {cob} g active` | `Lands {landing} by {landing.at}` |
| DIP_AHEAD | `Watching · {iob} U, {cob} g active` | `Dip to {low} around {low.at}, have 10–15 g ready` |

  Refusal text:
  - `GLUCOSE_BELOW_SAFE_THRESHOLD` → "glucose under 3.9" (formatted in the user's unit);
  - `DOSE_EXCEEDS_MAX_BOLUS` → "over your safety limit";
  - `SETTINGS_INVALID` / `INSULIN_PARAMS_INCONSISTENT` → "check your ISF settings";
  - else → "can't calculate".

  When `activityEstimate == .default` and the reason is WATCHING or DIP_AHEAD, append ` · activity estimate still rough` to the detail.
- Produces: `AppState.visibleWatchStrip: BackendAPI.WatchStrip?`, which is nil when any of these is true: `libreDataStale`, `calculationsStale`, `calculationsFromCache`, `calculations?.watch == nil`, or the state is unknown.

- [ ] **Step 1: Write the failing tests**
  - `decodesFullWatch`.
  - `decodesMissingWatchAsNil`.
  - `unknownStateHidden`.
  - `forecastLowText_mmol` and `forecastLowText_mgdl`: 3.6 mmol → "65" mg/dL, label "mg/dL".
  - `correctionText_showsTargetAndIsf`.
  - `refusalText_mapsCodes`.
  - `roughActivitySuffix_onlyWhenDefault`.
  - `hiddenWhenCached` and `hiddenWhenCalculationsStale` (Review Focus 5).
  - `hiddenWhenLibreStale`.
- [ ] **Step 2: Run the build check.** Expected: build failure (types missing).
- [ ] **Step 3: Implement** the model, the text and `visibleWatchStrip`, and add the files to the pbxproj.
- [ ] **Step 4: Run the build check.** Expected: `1`. Run `xcodebuild … test -only-testing:GlucoseMonitorTests/WatchStripTests` only with the user's OK.
- [ ] **Step 5: Commit** `feat(watch): decode and phrase the watch strip`

### Task 9: Strip view on the dashboard

**Files:**
- Create: `GlucoseMonitor/WatchStripView.swift`
- Modify: `GlucoseMonitor/ContentView.swift` (`DashboardView` body near the `libreStaleBanner`), `GlucoseMonitor.xcodeproj/project.pbxproj`

**Interfaces:**
- Consumes: `AppState.visibleWatchStrip`, `WatchStripText`.
- Produces: `struct WatchStripView: View { let strip: BackendAPI.WatchStrip; let unit: String; let onTap: () -> Void }`:
  - bold headline over one caption detail line;
  - red tint for URGENT_*, green for CALM (mockup A);
  - no buttons;
  - accessibility label = headline + detail.
- In `DashboardView`:
  - wrap the `ScrollView` in a `ScrollViewReader`;
  - give `extendedChartSection` `.id("forecastChart")`;
  - place the strip right after the `libreStaleBanner` block, before the ISF banner.
- `onTap`:
  - URGENT_LOW with `calc.carbAdvice != nil` → `chatExpanded = true` (the existing carb card has the curves and the "Took" buttons);
  - anything else → `withAnimation { proxy.scrollTo("forecastChart", anchor: .top) }`.

- [ ] **Step 1: Implement the view and the placement.** This is view-only: logic is covered by Task 8.
- [ ] **Step 2: Run the build check.** Expected: `1`.
- [ ] **Step 3: Commit** `feat(watch): watch strip on the dashboard`

### Task 10: Target and rescue-carb settings

**Files:**
- Modify: `GlucoseMonitor/BackendAPI.swift` (`COBSettings`: `var targetGlucoseMmol: Double? = nil`, `var rescueCarbsG: Int? = nil`)
- Modify: `GlucoseMonitor/SettingsView.swift` ("User Settings" section; save through the existing `saveCalculationSettings`)
- Test: `GlucoseMonitorTests/WatchStripTests.swift` (settings cases)

**Interfaces:**
- Backend JSON names: `targetGlucoseMmol`, `rescueCarbsG` (`UserSettingsDTO`, `/api/user-settings`).
- Target is entered and shown in `appState.preferredGlucoseUnit`:
  - accepted 4.4–10.0 mmol/L, which is 79–180 mg/dL;
  - stored as mmol/L, converted with the app's existing mg/dL factor;
  - default shown when nil: 5.6 mmol/L / 100 mg/dL.
- Rescue carbs: 5–40 g, default 15.
- Out-of-range input disables Save with an inline message, so the backend's validation is never the first line.
- Produces: `enum TargetGlucoseInput { static func mmol(fromDisplay value: Double, unit: String) -> Double?; static func display(mmol: Double, unit: String) -> Double }`. It returns nil outside the range.

- [ ] **Step 1: Write the failing tests**
  - `cobSettings_encodesTargetAndRescue`.
  - `cobSettings_decodesWithoutNewFields`: an old payload gives nil.
  - `target_mgdl100_is555`: within 0.01.
  - `target_outOfRange_nil`: 78 mg/dL and 10.1 mmol/L.
- [ ] **Step 2: Run the build check.** Expected: build failure.
- [ ] **Step 3: Implement** the fields, `TargetGlucoseInput` (in `SettingsView.swift`, or a small new file added to the pbxproj) and the two settings rows.
- [ ] **Step 4: Run the build check.** Expected: `1`.
- [ ] **Step 5: Commit** `feat(settings): target glucose and rescue carbs`
