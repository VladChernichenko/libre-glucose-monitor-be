# Illness Profile (Sick Mode) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user declare an illness with a severity so forecasts and every dose suggestion use a lower ISF and higher basal need while it lasts, with sick time kept out of all learning.

**Architecture:** A timed sick episode is stored as severity segments. A pure `SickProfile` value built from those segments rides on the cached `UserSettingsDTO`, so the eight existing `getEffectiveIsf(time)` callers pick up the multiplier at one seam; grams-per-unit and the basal reference follow the same profile. A separate `SickModeService` owns commands, status, lapse and the stubborn-high proposal; the iPhone app and the chat drive it through one REST resource.

**Tech Stack:** Java 21, Spring Boot, JPA/Flyway/PostgreSQL, JUnit 5 + Mockito + AssertJ, Testcontainers; Swift/SwiftUI, XCTest.

**Spec:** `docs/superpowers/specs/2026-11-16-illness-profile-design.md` (read it first; this plan argues from it).

## Global Constraints

- Severity table, verbatim: MILD x0.85 / +0%; MODERATE x0.70 / +15%; SEVERE x0.50 / +30%; SEVERE_UNRESPONSIVE x0.40 / +30%.
- SEVERE_UNRESPONSIVE is reachable only by accepting a step-up proposal; never selectable.
- Stubborn high: sick mode on; latest correction in the episode logged >= 120 min ago; glucose now > 13.0 mmol/L; glucose now > (glucose at correction - 1.0); no carbs logged after that correction; no decision recorded for that correction.
- Lapse: 48 h after `last_confirmed_at`. Learning exclusion tail: 6 h after episode end. Frozen healthy basal is used for 56 h after episode end.
- Backdating: NOW, THIS_MORNING (06:00 today), YESTERDAY (06:00 the day before), user's wall clock; never before the end of a previous episode.
- Nothing changes dosing without a user confirmation (dashboard tap, chat card tap).
- Sick timestamps live on the user's wall clock, like `notes.timestamp` (`LocalDateTime`, `TIMESTAMPTZ` column). "Now" for the sick API is `UserZones.toWallTime(clock.millis(), UserZones.resolve(tz, offset))` from the user's settings.
- Schema rule: new tables go in a new migration (`V21`); no `ALTER TABLE` in new files.
- Files under 500 lines. Run `./gradlew test` before each commit. Backend package for new code: `che.glucosemonitorbe.sick`.
- User-facing strings in English and Russian where the chat already has both.

## Review Focus

1. **Stale cached settings.** `getUserSettings` is `@Cacheable`; after start / change / end the next forecast must see the new profile. Expected: every command evicts `userSettings` for that user. (Test in Task 5.)
2. **Severity change while a dose is active.** A dose taken at MODERATE keeps x0.70 after a step-up to SEVERE; only later doses get x0.50. (Test in Tasks 1 and 4.)
3. **Lapse boundary.** Read at `last_confirmed_at + 48 h + 1 min`: multiplier is 1.0 from the lapse instant on, status is inactive, and a new start succeeds. (Tests in Tasks 1 and 5.)
4. **Grams-per-unit below the guard after scaling.** Stored 5 g/U at x0.40 is 2.0 g/U and must price a dose, not refuse; a stored 2.0 must still refuse. (Test in Task 3.)
5. **No reading at the correction.** A correction with neither `glucose_value` nor a CGM reading within 15 min yields no stubborn-high proposal rather than an error. (Test in Task 6.)

---

## File Structure

Backend, new (`src/main/java/che/glucosemonitorbe/sick/`):
- `SickSeverity.java` - enum with multipliers and step order.
- `SickProfile.java` - immutable value: spans + frozen basal; all time-based questions.
- `SickSegment.java`, `SickStepUpDecision.java` - entities. `SickSegmentRepository.java`, `SickStepUpDecisionRepository.java`.
- `SickProfileService.java` - segments -> `SickProfile` (no other service dependencies).
- `SickModeService.java` - commands, status, lapse, healthy-basal freeze.
- `StubbornHighEvaluator.java` - the proposal rule.
- `SickModeStatusDTO.java`, `SickModeController.java`.
- `src/main/resources/db/migration/V21__sick_mode.sql`.

Backend, modified: `dto/UserSettingsDTO.java`, `service/UserSettingsService.java`, `service/InsulinCalculatorService.java`, `service/GlucoseCalculationsService.java`, `dto/GlucoseCalculationsResponse.java`, `hovorka/HovorkaGlucosePredictionService.java`, `entity/Note.java`, `service/DigitalTwinCalibrationService.java`, `service/IsfMealWindowProfileService.java`, `assistant/mealplan/HistoryDoseService.java`, `assistant/chat/IntentClassifier.java`, `assistant/chat/AssistantService.java`, `assistant/chat/AssistantEvent.java`, plus `assistant/chat/SickModeFlow.java` (new).

iPhone (`glucose-monitor-iphone/GlucoseMonitor/`), new: `SickMode.swift`, `BackendAPI+SickMode.swift`, `AppState+SickMode.swift`, `SickModeViews.swift`, `Chat/SickModeCardView.swift`. Modified: `BackendAPI.swift`, `ContentView.swift`, `Chat/ChatItem.swift`, `Chat/ChatViewModel.swift`, `Chat/ChatMessageViews.swift`, `GlucoseMonitor.xcodeproj/project.pbxproj`.

---

### Task 1: Severity and profile (pure domain)

**Files:**
- Create: `sick/SickSeverity.java`, `sick/SickProfile.java`
- Test: `src/test/java/che/glucosemonitorbe/sick/SickProfileTest.java`

**Interfaces - Produces:**
```java
public enum SickSeverity { MILD, MODERATE, SEVERE, SEVERE_UNRESPONSIVE;
    public double isfMultiplier();          // 0.85, 0.70, 0.50, 0.40
    public double basalNeed();              // 0.0, 0.15, 0.30, 0.30
    public Optional<SickSeverity> next();   // MILD->MODERATE->SEVERE->SEVERE_UNRESPONSIVE->empty
    public boolean selectable(); }          // false only for SEVERE_UNRESPONSIVE

public record SickProfile(List<Span> spans, Double healthyBasalUnits) {
    public record Span(LocalDateTime from, LocalDateTime endedAt, LocalDateTime lastConfirmedAt,
                       SickSeverity severity) {
        public LocalDateTime effectiveEnd(); }   // min(endedAt, lastConfirmedAt + 48 h); open + unconfirmed-lapse aware
    public static final SickProfile EMPTY;
    public static final int LAPSE_HOURS = 48, LEARNING_TAIL_HOURS = 6, BASAL_HOLD_HOURS = 56;
    public Optional<SickSeverity> severityAt(LocalDateTime t);   // from <= t < effectiveEnd
    public double isfMultiplierAt(LocalDateTime t);              // 1.0 when not sick
    public double basalNeedAt(LocalDateTime t);                  // 0.0 when not sick
    public boolean excludedFromLearning(LocalDateTime t);        // inside any span, or < 6 h after its effectiveEnd
    public double basalReferenceUnits(double liveUsual, LocalDateTime t); }
```
`basalReferenceUnits`: with `healthyBasalUnits == null` return `liveUsual`; sick at `t` -> `healthy * (1 + basalNeedAt(t))`; within 56 h after the last span's `effectiveEnd` -> `healthy`; otherwise `liveUsual`.

- [ ] **Step 1: Write the failing tests** in `SickProfileTest`:
```java
@Test void multiplierFollowsTheSegmentInForceAtThatTime()   // 0.70 inside MODERATE, 0.50 after change, 1.0 before/after
@Test void endIsExclusive()                                  // isfMultiplierAt(endedAt) == 1.0
@Test void anOpenSpanLapses48hAfterItsLastConfirmation()     // at +47h59 -> 0.70; at +48h -> 1.0
@Test void learningIsExcludedForSixHoursAfterTheEnd()        // +5h59 true, +6h false
@Test void basalReferenceUsesTheFrozenHealthyAmount()        // sick MODERATE: 20 -> 23.0; +55h after end: 20; +57h: liveUsual; null healthy: liveUsual
@Test void unresponsiveIsNotSelectableAndHasNoNextLevel()
@Test void emptyProfileChangesNothing()
```
- [ ] **Step 2:** `./gradlew test --tests '*SickProfileTest'` -> FAIL (classes missing).
- [ ] **Step 3: Implement** the two types as specified.
- [ ] **Step 4:** rerun -> PASS.
- [ ] **Step 5: Commit** `feat(sick): severity levels and time-based sick profile`.

### Task 2: Persistence

**Files:**
- Create: `V21__sick_mode.sql`, `sick/SickSegment.java`, `sick/SickStepUpDecision.java`, both repositories, `sick/SickProfileService.java`
- Modify: `src/test/java/.../integration/ExistingDbUpgradeIT.java` (max version 21, `to_regclass('sick_segment')`)
- Test: `src/test/java/che/glucosemonitorbe/sick/SickProfileServiceTest.java`

**Schema** (model the DDL style on `V8__user_digital_twin.sql`; FK to `users` `ON DELETE CASCADE`):
- `sick_segment(id UUID PK, user_id UUID NOT NULL, episode_id UUID NOT NULL, severity VARCHAR(24) NOT NULL, started_at TIMESTAMPTZ NOT NULL, ended_at TIMESTAMPTZ, last_confirmed_at TIMESTAMPTZ NOT NULL, end_reason VARCHAR(16), healthy_basal_units DOUBLE PRECISION, created_at, updated_at)`; index `(user_id, started_at)`; partial unique index `(user_id) WHERE ended_at IS NULL`. `end_reason` values: `USER`, `MORNING_NO`, `LAPSED`, `SEVERITY_CHANGE`.
- `sick_step_up_decision(id UUID PK, user_id UUID NOT NULL, note_id UUID NOT NULL, accepted BOOLEAN NOT NULL, decided_at TIMESTAMPTZ NOT NULL, UNIQUE (user_id, note_id))`.

**Interfaces - Produces:**
```java
interface SickSegmentRepository extends JpaRepository<SickSegment, UUID> {
    Optional<SickSegment> findByUserIdAndEndedAtIsNull(UUID userId);
    List<SickSegment> findByUserIdAndStartedAtAfterOrderByStartedAtAsc(UUID userId, LocalDateTime after);
    Optional<SickSegment> findFirstByUserIdOrderByStartedAtDesc(UUID userId); }
interface SickStepUpDecisionRepository extends JpaRepository<SickStepUpDecision, UUID> {
    boolean existsByUserIdAndNoteId(UUID userId, UUID noteId); }

@Service class SickProfileService {
    static final int HISTORY_DAYS = 40;                 // covers the 30-day calibration window + tails
    public SickProfile profile(UUID userId); }          // EMPTY when the user has no segments
```
`profile` loads segments started in the last `HISTORY_DAYS` (by real clock), maps each to a `Span`, and takes `healthyBasalUnits` from the newest segment.

- [ ] **Step 1: Failing test** `SickProfileServiceTest` (mock repository): `buildsSpansInOrderAndCarriesTheNewestHealthyBasal`, `returnsEmptyForAUserWithNoSegments`.
- [ ] **Step 2:** run -> FAIL. **Step 3:** implement migration, entities (Lombok like `UserDigitalTwin`), repositories, service. **Step 4:** `./gradlew test --tests '*SickProfileServiceTest' --tests '*ExistingDbUpgradeIT'` -> PASS.
- [ ] **Step 5: Commit** `feat(sick): sick_segment and step-up decision tables`.

### Task 3: The settings seam (ISF and grams-per-unit)

**Files:**
- Modify: `dto/UserSettingsDTO.java` (~L110), `service/UserSettingsService.java` (L41-60, L186), `service/InsulinCalculatorService.java` (L258-266), `service/GlucoseCalculationsService.java` (L382 empirical params, ~L513 legacy carb rise)
- Test: `src/test/java/.../dto/UserSettingsDTOSickTest.java`, additions to `InsulinCalculatorServiceTest`, `UserSettingsServiceTest` (or the nearest existing test of that service)

**Interfaces:**
- Consumes: `SickProfile`, `SickProfileService.profile(UUID)`.
- Produces on `UserSettingsDTO`:
```java
@JsonIgnore private SickProfile sickProfile = SickProfile.EMPTY;   // never serialised, never read from clients
public Double getEffectiveIsf(LocalDateTime time);                  // existing value x sickProfile.isfMultiplierAt(time); null stays null
public Double getEffectiveGramsPerUnit(LocalDateTime time);         // gramsPerUnit x multiplier; null stays null
public UserSettingsDTO withSickProfile(SickProfile profile);        // shallow copy (BeanUtils.copyProperties); the cached instance is never mutated
```
- `UserSettingsService.getUserSettings` sets the profile on the DTO it returns (inside the cached method). Add `@CacheEvict(value = "userSettings", key = "#userId") public void evict(UUID userId) {}`.
- `InsulinCalculatorService.resolveGramsPerUnit`: validate the **stored** value against 3-30 exactly as today, then return `settings.getEffectiveGramsPerUnit(at)`.
- `GlucoseCalculationsService`: wherever grams-per-unit is read next to an effective ISF (L382, ~L513), read `getEffectiveGramsPerUnit` with the same time argument. `HovorkaParameterService` (L155-160) is **not** changed: `aG` keeps the base ISF and raw grams-per-unit so the carb rise is unchanged.

- [ ] **Step 1: Failing tests**
```java
// UserSettingsDTOSickTest
@Test void isfIsScaledOnlyInsideASickSpan()             // dinner ISF 0.85, MODERATE -> 0.595 inside, 0.85 outside
@Test void gramsPerUnitScalesByTheSameFactor()          // 10 -> 7.0
@Test void withSickProfileLeavesTheOriginalUntouched()
@Test void theProfileIsNotSerialised()                  // ObjectMapper JSON has no "sickProfile"
// InsulinCalculatorServiceTest
@Test void mealAndCorrectionDosesBothRiseByOneOverTheMultiplier()   // MODERATE: both x (1/0.70) within 1e-6
@Test void aScaledGramsPerUnitBelowThreeStillPricesADose()          // stored 5, x0.40 -> 2.0, no refusal
@Test void anInvalidStoredGramsPerUnitIsStillRefused()              // stored 2.0 -> DosingRefusedException
```
- [ ] **Step 2:** run -> FAIL. **Step 3:** implement. Fix any test that constructs `UserSettingsService` positionally. **Step 4:** `./gradlew test --tests '*UserSettings*' --tests '*InsulinCalculatorServiceTest' --tests '*GlucoseCalculationsServiceTest'` -> PASS.
- [ ] **Step 5: Commit** `feat(sick): sick multiplier on effective ISF and grams-per-unit`.

### Task 4: Forecast - basal need, and proof the model stays consistent

**Files:**
- Modify: `hovorka/HovorkaGlucosePredictionService.java` (`integrate`, L463-465: the `usualDailyUnits` reference)
- Test: `src/test/java/che/glucosemonitorbe/hovorka/HovorkaSickModeTest.java`

**Interfaces - Consumes:** `settings.getSickProfile().basalReferenceUnits(double liveUsual, LocalDateTime now)`; pass its result as `referenceUnits` to `basalResolver.resolveEgpSuppression(notes, now, durationHours, referenceUnits)`. `settings == null` -> unchanged behaviour. The existing "no active dose -> steady state" guard stays as is.

- [ ] **Step 1: Failing tests** (build the predictor as `HovorkaGlucosePredictionServiceTest` does; settings via `withSickProfile`):
```java
@Test void aDoseTakenWhileSickLowersGlucoseLess()        // 4h landing higher than healthy by about ISF x units x (1 - 0.70)
@Test void aDoseTakenBeforeAStepUpKeepsItsOwnMultiplier() // MODERATE dose then SEVERE span: effect equals MODERATE-only run
@Test void carbRiseIsUnchangedBySickMode()                // carbs only, no insulin: curves equal within 0.05
@Test void anUnchangedLongActingDoseDriftsUpWhileSick()   // 20 U logged, healthy 20, MODERATE: 4h landing > healthy run
@Test void aRaisedLongActingDoseDoesNotDrift()            // 23 U logged, healthy 20, MODERATE: equals healthy run within 0.1
@Test void withoutALongActingNoteThereIsNoDrift()
```
- [ ] **Step 2:** run -> FAIL. **Step 3:** implement the reference change. **Step 4:** rerun -> PASS; also `./gradlew test --tests '*hovorka*'`.
- [ ] **Step 5: Report, do not tune.** Print the 4 h drift of `anUnchangedLongActingDoseDriftsUpWhileSick` at MODERATE and SEVERE into the task report. If SEVERE drifts more than 4 mmol/L over 4 h, stop and report (spec: "report back rather than tune silently").
- [ ] **Step 6: Commit** `feat(sick): raised basal need in the forecast`.

### Task 5: Commands, status, lapse, API

**Files:**
- Create: `sick/SickModeService.java`, `sick/SickModeStatusDTO.java`, `sick/SickModeController.java`
- Modify: `entity/Note.java` (add `isCorrectionBolus()`), `service/DigitalTwinCalibrationService.java` (`toEvent` uses it)
- Test: `src/test/java/che/glucosemonitorbe/sick/SickModeServiceTest.java`, `SickModeControllerTest.java` (pattern: an existing `@WebMvcTest`/controller test in the repo)

**Interfaces - Produces:**
```java
public enum Since { NOW, THIS_MORNING, YESTERDAY }
public record SickModeStatusDTO(boolean active, SickSeverity severity, LocalDateTime since, Integer dayNumber,
        Integer isfPercent, Integer basalNeedPercent, boolean confirmationDue, StepUp stepUp) {
    public record StepUp(UUID noteId, SickSeverity toSeverity, boolean showHelpLine) {} }   // toSeverity null at the top level

@Service public class SickModeService {
    public SickModeStatusDTO status(UUID userId);                       // stepUp always null here (Task 6 fills it)
    public SickModeStatusDTO start(UUID userId, SickSeverity severity, Since since);
    public SickModeStatusDTO changeSeverity(UUID userId, SickSeverity severity);
    public SickModeStatusDTO confirm(UUID userId);                      // last_confirmed_at = now
    public SickModeStatusDTO end(UUID userId, String reason); }         // USER or MORNING_NO
// Note
public boolean isCorrectionBolus();   // "Correction".equalsIgnoreCase(meal) && insulin > 0 && carbs == 0 && !isLongActing()
```
Rules:
- `start`: reject (`IllegalArgumentException` -> 400) when a non-lapsed segment is open, when `severity.selectable()` is false, or when the backdated start is before the previous episode's effective end. New `episode_id`. `healthy_basal_units` = `BasalInsulinResolver.usualDailyUnits(longActingNotes before started_at within LOOKBACK_HOURS, started_at)`, null when it is 0.
- `changeSeverity`: closes the open segment (`ended_at = now`, `SEVERITY_CHANGE`) and opens one with the same `episode_id`, `healthy_basal_units` and `last_confirmed_at = now`. `selectable()` is enforced here too; Task 6 uses an internal overload that may set SEVERE_UNRESPONSIVE.
- Lapse: every method first closes an open segment whose `last_confirmed_at + 48 h <= now` with `ended_at = last_confirmed_at + 48 h`, `LAPSED`.
- `status`: `since` is the episode's first segment start; `dayNumber` = whole days since it + 1; `isfPercent` = `round((multiplier - 1) * 100)` (e.g. -30); `confirmationDue` = `last_confirmed_at.toLocalDate()` is before today.
- Every command calls `userSettingsService.evict(userId)`.
- Controller `/api/sick-mode`: `GET` -> status; `POST /start` `{severity, since}`; `PUT /severity` `{severity}`; `POST /confirm`; `POST /end` `{reason}`. Resolve the user as `DigitalTwinController` does.

- [ ] **Step 1: Failing tests** (fixed `Clock`, mocked repositories):
```java
@Test void startOpensASegmentAndFreezesTheHealthyBasal()
@Test void startBackdatedToThisMorningBeginsAtSixToday()
@Test void startCannotReachBeforeThePreviousEpisodesEnd()
@Test void startRejectsTheUnselectableLevel()
@Test void changeSeverityClosesAndReopensWithinTheSameEpisode()
@Test void anUnconfirmedSegmentLapsesAt48hAndANewStartThenSucceeds()   // ended_at == last_confirmed_at + 48h, LAPSED
@Test void confirmationIsDueOnTheFirstReadOfANewDay()
@Test void everyCommandEvictsTheCachedSettings()                        // verify(userSettingsService).evict(userId) for start/change/confirm/end
@Test void statusReportsDayNumberAndPercentages()                       // MODERATE day 2: isfPercent -30, basalNeedPercent 15
```
- [ ] **Step 2:** run -> FAIL. **Step 3:** implement. **Step 4:** `./gradlew test --tests '*sick*' --tests '*CorrectionLabelPropagationTest'` -> PASS.
- [ ] **Step 5: Commit** `feat(sick): sick mode commands, status and REST resource`.

### Task 6: Stubborn-high proposal

**Files:**
- Create: `sick/StubbornHighEvaluator.java`
- Modify: `sick/SickModeService.java` (`decideStepUp`), `sick/SickModeController.java` (`POST /step-up`), `dto/GlucoseCalculationsResponse.java` (field `sickMode`), `service/GlucoseCalculationsService.java` (~L146, next to `watchStripService.evaluate`)
- Test: `src/test/java/che/glucosemonitorbe/sick/StubbornHighEvaluatorTest.java`, addition to `SickModeServiceTest`

**Interfaces - Produces:**
```java
@Component public class StubbornHighEvaluator {
    static final int MIN_MINUTES = 120; static final double HIGH_MMOL = 13.0; static final double MIN_DROP_MMOL = 1.0;
    static final int CGM_MATCH_MINUTES = 15;
    public Optional<SickModeStatusDTO.StepUp> evaluate(UUID userId, SickSegment open, LocalDateTime episodeStart,
                                                       LocalDateTime now, double readingNow); }
// SickModeService
public SickModeStatusDTO statusForDashboard(UUID userId, double readingNow);   // status + stepUp
public SickModeStatusDTO decideStepUp(UUID userId, UUID noteId, boolean accept);
```
- "The correction" = newest note with `isCorrectionBolus()` and `timestamp >= episodeStart`. Reading at the correction = `note.glucoseValue`, else the CGM reading nearest within 15 min, else no proposal.
- `StepUp.toSeverity` = `open.severity.next()` or null; `showHelpLine` = the level in force is SEVERE or SEVERE_UNRESPONSIVE.
- `decideStepUp`: records a `SickStepUpDecision` (unique per user + note; a second call is a no-op), and on `accept` with a next level changes severity to it.
- `GlucoseCalculationsService` sets `response.sickMode = sickModeService.statusForDashboard(userId, reading)` when sick mode is active or a confirmation is due; otherwise leaves it null.

- [ ] **Step 1: Failing tests**
```java
@Test void proposesTheNextLevelWhenAHighDoesNotMove()          // 14.5 at correction, 14.2 now, 125 min later -> MODERATE->SEVERE
@Test void staysSilentBeforeTwoHours()                          // 119 min
@Test void staysSilentWhenGlucoseFellByOneOrMore()              // 15.0 -> 14.0
@Test void staysSilentAtOrBelowThirteen()
@Test void carbsLoggedAfterTheCorrectionSuppressIt()
@Test void aDecidedCorrectionIsNeverProposedAgain()
@Test void atTheTopLevelItOffersNoFurtherStepButShowsTheHelpLine() // toSeverity null, showHelpLine true
@Test void aCorrectionWithoutAnyReadingYieldsNoProposal()
// SickModeServiceTest
@Test void acceptingMovesExactlyOneLevelAndRecordsTheDecision() // MILD -> MODERATE; SEVERE -> SEVERE_UNRESPONSIVE allowed here
@Test void decliningOnlyRecordsTheDecision()
```
- [ ] **Step 2:** run -> FAIL. **Step 3:** implement. **Step 4:** `./gradlew test --tests '*sick*' --tests '*GlucoseCalculationsServiceTest'` -> PASS.
- [ ] **Step 5: Commit** `feat(sick): stubborn-high step-up proposal`.

### Task 7: Keep sick time out of learning

**Files:**
- Modify: `service/DigitalTwinCalibrationService.java` (`calibrateUser`, after `excludeFlaggedWindows`), `service/IsfMealWindowProfileService.java` (bolus loop ~L165), `assistant/mealplan/HistoryDoseService.java` (~L100)
- Test: additions to `DigitalTwinActivityStageTest`-style test `DigitalTwinSickExclusionTest.java`, `IsfMealWindowProfileServiceTest`, the existing `HistoryDoseService` test

**Interfaces - Consumes:** `settings.getSickProfile().excludedFromLearning(LocalDateTime)`; `settings.withSickProfile(SickProfile.EMPTY)`.
- Calibration: drop CGM readings whose wall time is excluded (convert with `PredictionReplayEngine.toLdt(epochMs, userZone)`). Unconditional: if fewer than `MIN_CGM_READINGS` remain, return `null` (skip). Pass `settings.withSickProfile(SickProfile.EMPTY)` to both replay engines and to the curve stage.
- ISF profile: skip a bolus whose timestamp is excluded. History dose: skip an episode whose `mealAt` is excluded.

- [ ] **Step 1: Failing tests**
```java
@Test void readingsInsideASickEpisodeAndItsSixHourTailAreNotFitted()   // trainSamples smaller than without the episode
@Test void calibrationIsSkippedWhenSickTimeLeavesTooLittleData()        // calibrateUser returns null
@Test void replayRunsOnHealthySettings()                                // captured settings have an EMPTY profile
@Test void isfProfileIgnoresBolusesTakenWhileSick()
@Test void historyDoseIgnoresMealsEatenWhileSick()
```
- [ ] **Step 2:** run -> FAIL. **Step 3:** implement. **Step 4:** `./gradlew test --tests '*DigitalTwin*' --tests '*IsfMealWindowProfileServiceTest' --tests '*HistoryDose*'` -> PASS.
- [ ] **Step 5: Commit** `feat(sick): exclude sick time from twin, ISF profile and history dose`.

### Task 8: Chat

**Files:**
- Create: `assistant/chat/SickModeFlow.java`
- Modify: `assistant/chat/IntentClassifier.java` (L35 enum, prompt, parser ~L229), `assistant/chat/AssistantService.java` (`handle`, before the `EVENT` branch), `assistant/chat/AssistantEvent.java`
- Test: additions to the existing `IntentClassifier` and `AssistantServiceTest` tests; `SickModeFlowTest.java`

**Interfaces - Produces:**
```java
// IntentClassifier
public enum Intent { MEAL_PREP, EVENT, REMOVE, QUESTION, SICK_MODE }
public record SickCommand(Action action, SickSeverity severity) { public enum Action { START, END } }  // severity may be null
// Classification gains: SickCommand sick()
// AssistantEvent (wire name "sickMode")
record SickMode(UUID messageId, String action, SickSeverity severity, SickModeStatusDTO current) implements AssistantEvent {}
// SickModeFlow
public void propose(UUID userId, ChatMessage userMessage, IntentClassifier.SickCommand cmd, String language,
                    Consumer<AssistantEvent> sink);
```
- Classifier prompt: a present-tense statement about the user's own state ("I'm sick", "I have a fever", "у меня температура", "I'm better", "выздоровел") is `SICK_MODE`; a remark about a past moment ("felt sick after lunch") stays `EVENT` with tag `ILLNESS`. A severity is returned only when the message states one; SEVERE_UNRESPONSIVE is never returned (parser maps it to null).
- `propose` changes nothing. It appends an assistant message, emits `SickMode` then `Done`. The card's buttons call the REST resource from Task 5.

- [ ] **Step 1: Failing tests**
```java
@Test void presentTenseIllnessIsASickModeIntent()          // classifier JSON {"intent":"SICK_MODE","sick":{"action":"START","severity":"MODERATE"}}
@Test void aPastRemarkStaysATaggedEvent()
@Test void anUnselectableSeverityFromTheModelIsDropped()   // "SEVERE_UNRESPONSIVE" -> severity null
@Test void theFlowProposesAndNeverSwitchesTheMode()        // verifyNoInteractions(sickModeService commands); events: SickMode, Done
@Test void sickModeEventSerialisesWithItsTypeName()        // "type":"sickMode"
```
- [ ] **Step 2:** run -> FAIL. **Step 3:** implement. **Step 4:** `./gradlew test --tests '*assistant*'` -> PASS.
- [ ] **Step 5: Commit** `feat(sick): chat proposes starting and ending sick mode`.

### Task 9: iPhone - models, API, state

Work in `/Users/vlad/IdeaProjects/glucose-monitor-project/glucose-monitor-iphone`. New Swift files must be added to `GlucoseMonitor.xcodeproj/project.pbxproj` (file reference, group, build file, sources phase) for the `GlucoseMonitor` target and tests to `GlucoseMonitorTests`.

**Files:**
- Create: `GlucoseMonitor/SickMode.swift`, `GlucoseMonitor/BackendAPI+SickMode.swift`, `GlucoseMonitor/AppState+SickMode.swift`
- Modify: `GlucoseMonitor/BackendAPI.swift` (calculations response L315-342: add `sickMode`, decoded with `try? decodeIfPresent` like `watch`)
- Test: `GlucoseMonitorTests/SickModeDecodingTests.swift`, `GlucoseMonitorTests/SickModeAppStateTests.swift`

**Interfaces - Produces:**
```swift
enum SickSeverity: String, Codable, CaseIterable { case mild = "MILD", moderate = "MODERATE", severe = "SEVERE", severeUnresponsive = "SEVERE_UNRESPONSIVE"
    var selectable: Bool }
enum SickSince: String, Encodable { case now = "NOW", thisMorning = "THIS_MORNING", yesterday = "YESTERDAY" }
struct SickModeStatus: Decodable, Equatable { let active: Bool; let severity: SickSeverity?; let since: Date?; let dayNumber: Int?
    let isfPercent: Int?; let basalNeedPercent: Int?; let confirmationDue: Bool; let stepUp: StepUp?
    struct StepUp: Decodable, Equatable { let noteId: UUID; let toSeverity: SickSeverity?; let showHelpLine: Bool } }
extension BackendAPI { func sickModeStatus() async throws -> SickModeStatus
    func startSickMode(_ s: SickSeverity, since: SickSince) async throws -> SickModeStatus
    func changeSickSeverity(_ s: SickSeverity) async throws -> SickModeStatus
    func confirmStillSick() async throws -> SickModeStatus
    func endSickMode(reason: String) async throws -> SickModeStatus
    func decideSickStepUp(noteId: UUID, accept: Bool) async throws -> SickModeStatus }
extension AppState { var sickMode: SickModeStatus? { get }   // from the latest calculations, replaced by any command's result
    func startSickMode(_:since:) async; func changeSickSeverity(_:) async; func confirmStillSick() async
    func endSickMode(reason:) async; func decideSickStepUp(accept:) async }
```
An unknown severity string decodes the whole `sickMode` to nil (same tolerance as `watch`). Each command refreshes the dashboard calculations afterwards.

- [ ] **Step 1: Failing tests:** `testDecodesActiveStatusWithStepUp`, `testUnknownSeverityDropsTheStatusNotTheDashboard`, `testAbsentSickModeIsNil`, `testCommandResultReplacesTheCachedStatus`.
- [ ] **Step 2: Run** (log to a file; check for `** TEST SUCCEEDED **`):
```bash
xcodebuild test -scheme GlucoseMonitor -project GlucoseMonitor.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 16,OS=18.3.1' -derivedDataPath /tmp/gm-derived CODE_SIGNING_ALLOWED=NO -only-testing:GlucoseMonitorTests/SickModeDecodingTests -only-testing:GlucoseMonitorTests/SickModeAppStateTests > /tmp/gm-sick.log 2>&1; echo $?
```
Expected: FAIL to compile. **Step 3:** implement. **Step 4:** rerun -> exit 0.
- [ ] **Step 5: Commit** `feat(sick): sick mode models, API and app state`.

### Task 10: iPhone - dashboard

**Files:**
- Create: `GlucoseMonitor/SickModeViews.swift`
- Modify: `GlucoseMonitor/ContentView.swift` (`DashboardView`, above the watch strip at ~L158)
- Test: `GlucoseMonitorTests/SickModeTextTests.swift`

**Interfaces - Produces:**
```swift
enum SickModeText {   // pure strings, unit-tested
    static func title(_ s: SickModeStatus) -> String        // "Sick · moderate · day 2"
    static func detail(_ s: SickModeStatus) -> String       // "ISF −30% · basal need +15%"
    static func symptoms(_ s: SickSeverity) -> String       // the spec's three descriptions
    static func stepUpQuestion(_ to: SickSeverity?) -> String }
struct SickModeCard: View      // active state: title, detail, [Change/End]
struct SickModeEntry: View     // off state: small "I'm sick" row
struct SickSeverityPicker: View // sheet: three selectable levels with symptoms + "Since when?" (now / this morning / yesterday)
struct StillSickPrompt: View   // [Yes, same] [Change] [No]
struct SickStepUpCard: View    // question, "Check ketones if you can.", help line when showHelpLine, [Yes, raise] [Keep <level>]
```
Layout rules: follow `GlycemicEventPromptCard` / `WatchStripView` styling. Show order above the strip: `SickStepUpCard` (when `stepUp != nil`), else `StillSickPrompt` (when `confirmationDue`), else `SickModeCard`; `SickModeEntry` when inactive. With `stepUp.toSeverity == nil` the card shows only the hints and a single [OK] that records a decline. Help line, verbatim: "Vomiting, fast breathing, or highs that will not come down are reasons to get medical help."

- [ ] **Step 1: Failing tests:** `testTitleAndDetailForModerateDayTwo`, `testUnresponsiveLevelIsNotInThePicker`, `testStepUpQuestionNamesTheTargetLevel`.
- [ ] **Step 2:** run the `xcodebuild test` command from Task 9 with `-only-testing:GlucoseMonitorTests/SickModeTextTests` -> FAIL. **Step 3:** implement views and wire into `DashboardView`. **Step 4:** rerun -> exit 0; then a full simulator `build`.
- [ ] **Step 5: Commit** `feat(sick): dashboard sick mode card, picker, morning question and step-up`.

### Task 11: iPhone - chat card

**Files:**
- Create: `GlucoseMonitor/Chat/SickModeCardView.swift`
- Modify: `GlucoseMonitor/Chat/ChatItem.swift`, `Chat/ChatViewModel.swift`, `Chat/ChatMessageViews.swift` (follow how `EventProposal` is decoded and rendered)
- Test: addition to `GlucoseMonitorTests/AssistantEventDecodingTests.swift`

**Interfaces:** decode the `"sickMode"` stream event into `ChatItem.sickMode(messageId: UUID, action: String, severity: SickSeverity?, current: SickModeStatus?)`. START card: severity picker rows (preselected when given) + "Since when?" + [Turn on]; END card: [End sick mode]. Buttons call the `AppState` commands from Task 9. An unknown event type must keep being ignored as today.

- [ ] **Step 1: Failing test:** `testDecodesSickModeProposal` (JSON line with `"type":"sickMode","action":"START","severity":"MODERATE"`).
- [ ] **Step 2:** run -> FAIL. **Step 3:** implement. **Step 4:** rerun -> exit 0.
- [ ] **Step 5: Commit** `feat(sick): chat card to start and end sick mode`.

### Task 12: End-to-end check on the dev database

- [ ] **Step 1:** `./gradlew test` in `glucose-monitor-be` -> `BUILD SUCCESSFUL`.
- [ ] **Step 2:** start the backend; confirm `select max(version::int) from flyway_schema_history` is 21 and `sick_segment` exists.
- [ ] **Step 3:** with a logged-in session: `POST /api/sick-mode/start {"severity":"MODERATE","since":"NOW"}`; fetch the dashboard calculations with insulin on board and record the 4 h landing before and after - it must be higher while sick, and the strip's suggested units (when shown) larger.
- [ ] **Step 4:** `POST /api/sick-mode/end`; run the recalibration CLI (`SPRING_PROFILES_ACTIVE=recalibrate-cli ./gradlew bootRun`) and confirm the log shows fewer readings fitted than before the episode existed, or a skip.
- [ ] **Step 5:** simulator build of the iPhone app (`** BUILD SUCCEEDED **`); open the dashboard and walk: entry -> picker -> card -> End.
- [ ] **Step 6:** write the Task 4 drift figures and anything that could not be verified into the final report.
