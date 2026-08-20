# Hypo Rescue-Carb Logging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prompt the user when CGM glucose drops below 3.9 mmol/L and let them log a fast-acting rescue carb in one tap, modelled with a genuinely fast absorption curve.

**Architecture:** The backend anomaly observer (repointed from notes to the real CGM table) opens a `hypo_events` row when glucose goes low. iOS polls for OPEN events on its existing dashboard-refresh cycle and renders a confirm sheet; confirming creates a `hypo_treatment` note whose carbs decay over 45 minutes instead of 240. Rescue carbs feed COB and the prediction path but are excluded from the two parameter-titration loops they would otherwise corrupt.

**Tech Stack:** Java 21, Spring Boot 3.5.5, JPA/Hibernate, Flyway, PostgreSQL, JUnit 5 + Mockito + AssertJ. iOS: Swift 5, SwiftUI, async/await.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-20-hypo-rescue-carb-logging-design.md`
- Branch: `feat/hypo-rescue-carb-logging` (already created; the spec is committed on it)
- Backend stores and computes glucose in **mmol/L** everywhere. mg/dL is display-only, converted on iOS via the existing `GlucoseUnit`. Never introduce a second conversion constant.
- Hypo threshold: **3.9 mmol/L**. Recovery threshold: **4.5 mmol/L**. Re-prompt suppression: **15 minutes**.
- Rescue absorption: half-life **15 min**, max duration **45 min**, GI **100**.
- `grams` validation range: `(0, 100]`.
- Schema rule: never `ALTER TABLE` in a new migration. New tables get a new `V{n+1}__` file; changes to existing tables edit the migration that owns them. `V11` is the next free number.
- Run backend tests with `./gradlew test --tests '<pattern>'`. Do not run the full suite for a single-test check.
- Every task ends with a commit. Do not push.

---

### Task 1: Repoint the anomaly detector at real CGM data

The detector reads `notes.glucose_value`, which no CGM sync path ever writes, so all four alert types are dormant. This is a prerequisite: the hypo prompt is built on this detector. The class has **no existing tests**, so this task adds the first ones.

**Files:**
- Create: `src/main/java/che/glucosemonitorbe/domain/GlucoseConversion.java`
- Modify: `src/main/java/che/glucosemonitorbe/service/observer/GlucoseAnomalyDetector.java`
- Modify: `src/main/java/che/glucosemonitorbe/hovorka/learning/ReplayMetrics.java:23`
- Modify: `src/main/java/che/glucosemonitorbe/service/UnloggedEventDetectionService.java:62`
- Modify: `src/main/java/che/glucosemonitorbe/service/DigitalTwinCalibrationService.java:58`
- Test: `src/test/java/che/glucosemonitorbe/service/observer/GlucoseAnomalyDetectorTest.java`

**Interfaces:**
- Produces: `GlucoseConversion.MGDL_PER_MMOL` (double, 18.0182) and `GlucoseConversion.mgdlToMmol(double)`; `GlucoseAnomalyDetector.GlucosePoint(long epochMs, double mmol)` record; `static double GlucoseAnomalyDetector.computeRoc(List<GlucosePoint>)`.
- Consumes: `CgmReadingRepository.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(UUID, Long, Long)` (already exists).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/che/glucosemonitorbe/service/observer/GlucoseAnomalyDetectorTest.java`:

```java
package che.glucosemonitorbe.service.observer;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The detector previously read glucose from notes.glucose_value - a column no CGM sync path
 * writes - so it never fired in production. These tests pin it to cgm_readings.
 */
class GlucoseAnomalyDetectorTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private CgmReadingRepository cgmRepo;
    private NoteRepository noteRepo;
    private UserRepository userRepo;
    private GlucoseAlertService alertService;
    private GlucoseAnomalyDetector detector;

    @BeforeEach
    void setUp() {
        cgmRepo = mock(CgmReadingRepository.class);
        noteRepo = mock(NoteRepository.class);
        userRepo = mock(UserRepository.class);
        alertService = mock(GlucoseAlertService.class);
        detector = new GlucoseAnomalyDetector(cgmRepo, noteRepo, userRepo, alertService);
        when(noteRepo.findByUserIdAndTimestampBetween(any(), any(), any())).thenReturn(List.of());
    }

    /** Regression test for the dormancy bug: zero glucose-bearing notes, yet the detector must fire. */
    @Test
    void evaluatesFromCgmReadingsWithNoNotesPresent() {
        long now = System.currentTimeMillis();
        when(cgmRepo.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(eq(USER_ID), any(), any()))
                .thenReturn(List.of(
                        reading(now - 600_000, 126),  // 7.0 mmol/L
                        reading(now - 300_000, 117),  // 6.5 mmol/L
                        reading(now,           108)   // 6.0 mmol/L
                ));

        detector.evaluateUser(USER_ID, "tester");

        verify(alertService).evaluateAll(eq(USER_ID), eq("tester"), anyDouble(), anyDouble(), any());
        verify(noteRepo, never()).findByUserIdAndTimestampBetween(eq(USER_ID), any(), any());
    }

    @Test
    void computesNegativeSlopeForFallingGlucose() {
        long t0 = 1_000_000_000_000L;
        double roc = GlucoseAnomalyDetector.computeRoc(List.of(
                new GlucoseAnomalyDetector.GlucosePoint(t0,               7.0),
                new GlucoseAnomalyDetector.GlucosePoint(t0 + 600_000,     6.4),
                new GlucoseAnomalyDetector.GlucosePoint(t0 + 1_200_000,   5.8)
        ));
        // −1.2 mmol/L over 20 min = −0.06 mmol/L/min
        assertThat(roc).isCloseTo(-0.06, within(1e-9));
    }

    @Test
    void returnsZeroRocWhenAllReadingsShareATimestamp() {
        long t0 = 1_000_000_000_000L;
        double roc = GlucoseAnomalyDetector.computeRoc(List.of(
                new GlucoseAnomalyDetector.GlucosePoint(t0, 7.0),
                new GlucoseAnomalyDetector.GlucosePoint(t0, 5.0)
        ));
        assertThat(roc).isZero();
    }

    private static CgmReading reading(long epochMs, int sgvMgdl) {
        CgmReading r = new CgmReading();
        r.setUserId(USER_ID);
        r.setDateTimestamp(epochMs);
        r.setSgv(sgvMgdl);
        return r;
    }
}
```

Add the static import `org.mockito.ArgumentMatchers.anyDouble` alongside the others.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'che.glucosemonitorbe.service.observer.GlucoseAnomalyDetectorTest'`
Expected: FAIL — compilation error, `GlucosePoint` does not exist and the constructor takes no `CgmReadingRepository`.

- [ ] **Step 3: Create the shared conversion constant**

Create `src/main/java/che/glucosemonitorbe/domain/GlucoseConversion.java`:

```java
package che.glucosemonitorbe.domain;

/**
 * Single definition of the mg/dL to mmol/L glucose conversion.
 *
 * <p>This factor previously appeared as a private constant in three unrelated classes. Glucose
 * conversion is a medical calculation; one definition removes the chance of them drifting apart.
 */
public final class GlucoseConversion {

    private GlucoseConversion() {}

    /** mg/dL per mmol/L of glucose. */
    public static final double MGDL_PER_MMOL = 18.0182;

    public static double mgdlToMmol(double mgdl) {
        return mgdl / MGDL_PER_MMOL;
    }

    public static double mmolToMgdl(double mmol) {
        return mmol * MGDL_PER_MMOL;
    }
}
```

- [ ] **Step 4: Repoint the three existing private constants at it**

In each of the three files, delete the private declaration and reference the shared one instead.

`ReplayMetrics.java:23`, `UnloggedEventDetectionService.java:62`, `DigitalTwinCalibrationService.java:58` — replace:

```java
private static final double MGDL_PER_MMOL = 18.0182;
```

with:

```java
private static final double MGDL_PER_MMOL = che.glucosemonitorbe.domain.GlucoseConversion.MGDL_PER_MMOL;
```

Keeping the local name means no call sites change. Value is identical, so behaviour is unchanged.

- [ ] **Step 5: Rewrite the detector's glucose source**

In `GlucoseAnomalyDetector.java`:

Replace the imports block additions — add `che.glucosemonitorbe.domain.CgmReading`, `che.glucosemonitorbe.domain.GlucoseConversion`, `che.glucosemonitorbe.repository.CgmReadingRepository`, `java.time.ZoneOffset`.

Replace the field declarations:

```java
    private final CgmReadingRepository cgmReadingRepository;
    private final NoteRepository noteRepository;
    private final UserRepository userRepository;
    private final GlucoseAlertService alertService;
```

Add the record just above `evaluateUser`:

```java
    /** A CGM sample reduced to what the rate-of-change maths needs. */
    public record GlucosePoint(long epochMs, double mmol) {}
```

Replace the whole body of `evaluateUser` (change its visibility to package-private so the test can call it directly):

```java
    void evaluateUser(UUID userId, String username) {
        LocalDateTime now = LocalDateTime.now();

        // 1. Collect recent CGM readings from the shared CGM cache. This used to read
        //    notes.glucose_value, which only ever holds client-supplied values from a logged
        //    note - neither sync scheduler writes it - so the observer never fired on real data.
        LocalDateTime rocStart = now.minusMinutes(ROC_WINDOW_MINUTES);
        long startMs = rocStart.toInstant(ZoneOffset.UTC).toEpochMilli();
        long endMs   = now.toInstant(ZoneOffset.UTC).toEpochMilli();

        List<GlucosePoint> recentReadings = cgmReadingRepository
                .findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(userId, startMs, endMs)
                .stream()
                .filter(r -> r.getDateTimestamp() != null && r.getSgv() != null && r.getSgv() > 0)
                .map(r -> new GlucosePoint(r.getDateTimestamp(),
                                           GlucoseConversion.mgdlToMmol(r.getSgv())))
                .toList();

        if (recentReadings.size() < MIN_READINGS_FOR_ROC) {
            // Not enough data to compute a meaningful ROC - skip this cycle.
            return;
        }

        double currentGlucose = recentReadings.get(recentReadings.size() - 1).mmol();
        double roc = computeRoc(recentReadings);

        // 2. Find minutes since the last carb note. Still note-based - correctly so.
        LocalDateTime mealWindow = now.minusMinutes(UNLOGGED_MEAL_WINDOW_MINUTES);
        List<Note> recentNotes = noteRepository.findByUserIdAndTimestampBetween(userId, mealWindow, now);
        Integer minutesSinceLastMeal = recentNotes.stream()
                .filter(n -> n.getCarbs() != null && n.getCarbs() > 0)
                .max(Comparator.comparing(Note::getTimestamp))
                .map(n -> (int) ChronoUnit.MINUTES.between(n.getTimestamp(), now))
                .orElse(null);

        // 3. Dispatch async evaluation (non-blocking)
        alertService.evaluateAll(userId, username, currentGlucose, roc, minutesSinceLastMeal);
    }
```

Replace `computeRoc` entirely:

```java
    /**
     * Ordinary least-squares slope over the provided readings.
     * Returns mmol/L per minute; negative = falling.
     *
     * <p>OLS is more robust than a simple first-last delta because a single
     * sensor glitch in the middle does not dominate the result.
     */
    static double computeRoc(List<GlucosePoint> readings) {
        if (readings.size() < 2) return 0.0;

        long t0 = readings.get(0).epochMs();
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        int n = readings.size();

        for (GlucosePoint r : readings) {
            double x = (r.epochMs() - t0) / 60_000.0;   // minutes since first reading
            double y = r.mmol();
            sumX  += x;
            sumY  += y;
            sumXY += x * y;
            sumX2 += x * x;
        }

        double denom = n * sumX2 - sumX * sumX;
        if (Math.abs(denom) < 1e-9) return 0.0;          // all readings at same timestamp
        return (n * sumXY - sumX * sumY) / denom;          // slope in mmol/L per minute
    }
```

Update the class javadoc: replace the `<h3>Rate-of-change calculation</h3>` paragraph's opening sentence with "Uses CGM readings from the last {@value #ROC_WINDOW_MINUTES} minutes (read from {@code cgm_readings}) to compute a least-squares slope in mmol/L per minute."

Delete the now-unused `Comparator` import only if the `minutesSinceLastMeal` block no longer uses it — it does use it, so keep it.

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew test --tests 'che.glucosemonitorbe.service.observer.GlucoseAnomalyDetectorTest'`
Expected: PASS, 3 tests.

- [ ] **Step 7: Verify nothing else broke**

Run: `./gradlew test`
Expected: PASS. The three `MGDL_PER_MMOL` edits are value-identical, so `ReplayMetrics`, `UnloggedEventDetectionService` and `DigitalTwinCalibrationService` tests must all still pass. If any fail, the constant was mistyped — compare against `18.0182`.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/che/glucosemonitorbe/domain/GlucoseConversion.java \
        src/main/java/che/glucosemonitorbe/service/observer/GlucoseAnomalyDetector.java \
        src/main/java/che/glucosemonitorbe/hovorka/learning/ReplayMetrics.java \
        src/main/java/che/glucosemonitorbe/service/UnloggedEventDetectionService.java \
        src/main/java/che/glucosemonitorbe/service/DigitalTwinCalibrationService.java \
        src/test/java/che/glucosemonitorbe/service/observer/GlucoseAnomalyDetectorTest.java
git commit -m "fix(observer): read glucose from cgm_readings, not notes

GlucoseAnomalyDetector sourced glucose from notes.glucose_value, which
only holds client-supplied values on logged notes - neither CGM sync
scheduler writes it. The detector needed two glucose-bearing notes in a
20-minute window, so all four alert types were dormant on real data.

Also promotes the mg/dL conversion factor, previously duplicated as a
private constant in three classes, to one shared definition."
```

---

### Task 2: Rescue note type and fast COB decay

**Files:**
- Create: `src/main/java/che/glucosemonitorbe/domain/RescueCarbProfile.java`
- Modify: `src/main/java/che/glucosemonitorbe/entity/Note.java` (constants near line 17-21; predicates near line 232-237)
- Modify: `src/main/java/che/glucosemonitorbe/service/CarbsOnBoardService.java`
- Test: `src/test/java/che/glucosemonitorbe/service/CarbsOnBoardServiceRescueTest.java`

**Interfaces:**
- Produces: `Note.TYPE_HYPO_TREATMENT` (String `"hypo_treatment"`), `Note.isHypoTreatment()` (boolean); `RescueCarbProfile.ABSORPTION_MODE` (String `"RESCUE"`), `.HALF_LIFE_MIN` (int 15), `.MAX_DURATION_MIN` (int 45), `.GI` (int 100), `.isRescue(String)` (boolean).
- Consumes: nothing from earlier tasks.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/che/glucosemonitorbe/service/CarbsOnBoardServiceRescueTest.java`:

```java
package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.RescueCarbProfile;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

/**
 * A rescue carb (glucose gel / dextrose) absorbs in ~20-30 min. Under the standard curve it
 * would sit on board for the user's full maxCOBDuration - up to 4 h of phantom carbs.
 */
class CarbsOnBoardServiceRescueTest {

    private CarbsOnBoardService service;
    private UserSettingsDTO settings;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        service = new CarbsOnBoardService(mock(UserSettingsService.class));
        now = LocalDateTime.now();
        // Deliberately slow user settings: the rescue curve must ignore both.
        settings = new UserSettingsDTO();
        settings.setCarbHalfLife(45);
        settings.setMaxCOBDuration(240);
    }

    @Test
    void rescueCarbsDecayByHalfEveryFifteenMinutes() {
        List<CarbsEntry> entries = List.of(rescue(15.0, now.minusMinutes(15)));
        double cob = service.calculateTotalCarbsOnBoard(entries, now, settings);
        assertThat(cob).isCloseTo(7.5, within(0.1));
    }

    @Test
    void rescueCarbsAreFullyAbsorbedByFortyFiveMinutes() {
        List<CarbsEntry> entries = List.of(rescue(15.0, now.minusMinutes(45)));
        double cob = service.calculateTotalCarbsOnBoard(entries, now, settings);
        assertThat(cob).isZero();
    }

    @Test
    void rescueCarbsIgnoreTheUsersSlowHalfLife() {
        // Under the standard 45-min half-life, 15 g at 30 min would leave ~9.4 g on board.
        List<CarbsEntry> entries = List.of(rescue(15.0, now.minusMinutes(30)));
        double cob = service.calculateTotalCarbsOnBoard(entries, now, settings);
        assertThat(cob).isLessThan(4.0);
    }

    @Test
    void nonRescueEntriesAreUnaffected() {
        CarbsEntry normal = CarbsEntry.builder()
                .carbs(15.0).timestamp(now.minusMinutes(45)).build();
        normal.setAbsorptionMode("DEFAULT_DECAY");
        double cob = service.calculateTotalCarbsOnBoard(List.of(normal), now, settings);
        assertThat(cob).isGreaterThan(4.0);   // still substantially on board at 45 min
    }

    private CarbsEntry rescue(double grams, LocalDateTime at) {
        CarbsEntry e = CarbsEntry.builder().carbs(grams).timestamp(at).build();
        e.setAbsorptionMode(RescueCarbProfile.ABSORPTION_MODE);
        return e;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'che.glucosemonitorbe.service.CarbsOnBoardServiceRescueTest'`
Expected: FAIL — compilation error, `RescueCarbProfile` does not exist.

- [ ] **Step 3: Create the rescue profile**

Create `src/main/java/che/glucosemonitorbe/domain/RescueCarbProfile.java`:

```java
package che.glucosemonitorbe.domain;

/**
 * The absorption profile of a fast-acting rescue carb (glucose gel, dextrose tablets, juice)
 * taken to treat a hypo.
 *
 * <p>A rescue carb is pure glucose and is clinically done inside half an hour. Running it through
 * the user's mixed-meal curve - a 45-minute half-life over a 4-hour window - would leave phantom
 * carbs on board long after the glucose had actually been absorbed, inflating both the displayed
 * COB and every prediction drawn from it.
 *
 * <p>These constants are the single definition shared by {@code CarbsOnBoardService} and the
 * Hovorka prediction path. Do not re-declare them: this repo has twice had an absorption curve
 * hand-copied into a second code path and silently drift.
 */
public final class RescueCarbProfile {

    private RescueCarbProfile() {}

    /** Marker written to {@link CarbsEntry#getAbsorptionMode()} for a rescue carb. */
    public static final String ABSORPTION_MODE = "RESCUE";

    /** Exponential half-life [min]. Glucose gel is substantially absorbed within 15 minutes. */
    public static final int HALF_LIFE_MIN = 15;

    /** Absorption is complete by this point [min]; COB is exactly zero from here on. */
    public static final int MAX_DURATION_MIN = 45;

    /** Minutes over which the tail tapers linearly to zero, so COB does not snap. */
    public static final int TAPER_MIN = 15;

    /** Glycemic index of pure glucose. */
    public static final int GI = 100;

    /** True when {@code absorptionMode} marks an entry as a rescue carb. */
    public static boolean isRescue(String absorptionMode) {
        return ABSORPTION_MODE.equalsIgnoreCase(absorptionMode);
    }
}
```

- [ ] **Step 4: Add the note type**

In `src/main/java/che/glucosemonitorbe/entity/Note.java`, alongside the existing type constants (near line 17-21):

```java
    /** A fast-acting rescue carb taken to treat a hypo (glucose gel, dextrose, juice). */
    public static final String TYPE_HYPO_TREATMENT = "hypo_treatment";
```

And alongside `isActivity()` (near line 237):

```java
    /** True when this note records a fast-acting rescue carb taken to treat a hypo. */
    public boolean isHypoTreatment() {
        return TYPE_HYPO_TREATMENT.equals(type);
    }
```

No migration is needed: `notes.type` is `VARCHAR(20)` and `"hypo_treatment"` is 14 characters.

- [ ] **Step 5: Branch the COB curve**

In `src/main/java/che/glucosemonitorbe/service/CarbsOnBoardService.java`, add the import `che.glucosemonitorbe.domain.RescueCarbProfile`.

Inside the private `calculateRemainingCarbs(CarbsEntry, LocalDateTime, UserSettingsDTO)` overload, insert immediately after the negative-`minutesSinceEntry` guard and **before** the `patternDuration` line:

```java
        // A rescue carb bypasses the user's meal-absorption settings entirely - it is pure
        // glucose, not a meal, and does not absorb at their mixed-meal rate.
        if (RescueCarbProfile.isRescue(entry.getAbsorptionMode())) {
            return rescueRemaining(entry.getCarbs(), minutesSinceEntry);
        }
```

Add this private method next to `calculateDefaultRemaining`:

```java
    /**
     * Remaining rescue carbs: a 15-minute exponential decay, linearly tapered to exactly zero
     * across the final {@link RescueCarbProfile#TAPER_MIN} minutes so COB does not step.
     * Mirrors the taper on the standard curve, scaled to the shorter rescue window.
     */
    private double rescueRemaining(double carbs, long minutesSinceEntry) {
        if (minutesSinceEntry > RescueCarbProfile.MAX_DURATION_MIN) {
            return 0.0;
        }
        double halfLives = (double) minutesSinceEntry / RescueCarbProfile.HALF_LIFE_MIN;
        double raw = carbs * Math.pow(0.5, halfLives);

        int taperStart = RescueCarbProfile.MAX_DURATION_MIN - RescueCarbProfile.TAPER_MIN;
        if (minutesSinceEntry >= taperStart) {
            double progress = (double) (minutesSinceEntry - taperStart) / RescueCarbProfile.TAPER_MIN;
            raw *= Math.max(0.0, 1.0 - progress);
        }
        return raw;
    }
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew test --tests 'che.glucosemonitorbe.service.CarbsOnBoardServiceRescueTest'`
Expected: PASS, 4 tests.

- [ ] **Step 7: Confirm the existing COB tests still pass**

Run: `./gradlew test --tests 'che.glucosemonitorbe.service.CarbsOnBoardServiceTest'`
Expected: PASS. No existing fixture sets `absorptionMode` to `RESCUE`, so the new branch is never taken by them.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/che/glucosemonitorbe/domain/RescueCarbProfile.java \
        src/main/java/che/glucosemonitorbe/entity/Note.java \
        src/main/java/che/glucosemonitorbe/service/CarbsOnBoardService.java \
        src/test/java/che/glucosemonitorbe/service/CarbsOnBoardServiceRescueTest.java
git commit -m "feat(cob): add hypo_treatment note type with a fast rescue absorption curve

15-min half-life over a 45-min window, bypassing the user's mixed-meal
settings. Constants live in one place so the COB path and the Hovorka
path cannot drift apart."
```

---

### Task 3: Wire rescue carbs into the prediction path

`NoteToCarbsEntryMapper` is the single conversion point feeding both the dashboard COB and the Hovorka path. Marking the entry here is what makes Task 2's curve actually apply to a saved note.

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/service/nutrition/NoteToCarbsEntryMapper.java`
- Test: `src/test/java/che/glucosemonitorbe/service/nutrition/NoteToCarbsEntryMapperRescueTest.java`

**Interfaces:**
- Consumes: `Note.isHypoTreatment()`, `RescueCarbProfile.ABSORPTION_MODE`, `RescueCarbProfile.GI` (Task 2).
- Produces: a `CarbsEntry` whose `absorptionMode` is `"RESCUE"` for any hypo-treatment note.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/che/glucosemonitorbe/service/nutrition/NoteToCarbsEntryMapperRescueTest.java`:

```java
package che.glucosemonitorbe.service.nutrition;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.RescueCarbProfile;
import che.glucosemonitorbe.entity.Note;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NoteToCarbsEntryMapperRescueTest {

    private FeatureToggleConfig config;
    private NoteToCarbsEntryMapper mapper;

    @BeforeEach
    void setUp() {
        config = new FeatureToggleConfig();
        config.setNutritionAwarePredictionEnabled(true);
        mapper = new NoteToCarbsEntryMapper(config, new ObjectMapper());
    }

    @Test
    void hypoTreatmentNoteMapsToRescueAbsorptionMode() {
        CarbsEntry entry = mapper.toCarbsEntry(rescueNote());
        assertThat(entry.getAbsorptionMode()).isEqualTo(RescueCarbProfile.ABSORPTION_MODE);
        assertThat(entry.getEstimatedGi()).isEqualTo((double) RescueCarbProfile.GI);
    }

    /** The rescue curve is physiology, not a nutrition-analysis nicety - the flag must not gate it. */
    @Test
    void rescueModeSurvivesNutritionAwarePredictionBeingOff() {
        config.setNutritionAwarePredictionEnabled(false);
        CarbsEntry entry = mapper.toCarbsEntry(rescueNote());
        assertThat(entry.getAbsorptionMode()).isEqualTo(RescueCarbProfile.ABSORPTION_MODE);
    }

    /** A stored nutrition profile must not be able to slow a rescue carb back down. */
    @Test
    void rescueModeIsNotOverriddenByAStoredNutritionProfile() {
        Note note = rescueNote();
        note.setNutritionProfile("{\"absorptionMode\":\"GI_GL_ENHANCED\",\"estimatedGi\":35.0}");
        CarbsEntry entry = mapper.toCarbsEntry(note);
        assertThat(entry.getAbsorptionMode()).isEqualTo(RescueCarbProfile.ABSORPTION_MODE);
    }

    @Test
    void ordinaryNoteIsUnaffected() {
        Note note = new Note(UUID.randomUUID(), LocalDateTime.now(), 40.0, 4.0, "Lunch");
        CarbsEntry entry = mapper.toCarbsEntry(note);
        assertThat(entry.getAbsorptionMode()).isEqualTo("DEFAULT_DECAY");
    }

    private Note rescueNote() {
        Note note = new Note(UUID.randomUUID(), LocalDateTime.now(), 15.0, 0.0, "Hypo treatment");
        note.setType(Note.TYPE_HYPO_TREATMENT);
        return note;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'che.glucosemonitorbe.service.nutrition.NoteToCarbsEntryMapperRescueTest'`
Expected: FAIL on the first three tests — `absorptionMode` is `"DEFAULT_DECAY"`, not `"RESCUE"`.

- [ ] **Step 3: Mark rescue entries in the mapper**

In `NoteToCarbsEntryMapper.toCarbsEntry`, add the import `che.glucosemonitorbe.domain.RescueCarbProfile`, then insert immediately after the existing `entry.setAbsorptionMode(note.getAbsorptionMode() != null ? ... : "DEFAULT_DECAY");` line and **before** the `if (!featureToggleConfig.isNutritionAwarePredictionEnabled())` check:

```java
        // A rescue carb has one fixed profile. Return before the nutrition-aware branch so that
        // neither the feature flag nor a stored nutrition profile can slow it back down to a
        // mixed-meal curve - the physiology is the same regardless of either.
        if (note.isHypoTreatment()) {
            entry.setAbsorptionMode(RescueCarbProfile.ABSORPTION_MODE);
            entry.setEstimatedGi((double) RescueCarbProfile.GI);
            entry.setAbsorptionSpeedClass("FAST");
            return entry;
        }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'che.glucosemonitorbe.service.nutrition.NoteToCarbsEntryMapperRescueTest'`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/che/glucosemonitorbe/service/nutrition/NoteToCarbsEntryMapper.java \
        src/test/java/che/glucosemonitorbe/service/nutrition/NoteToCarbsEntryMapperRescueTest.java
git commit -m "feat(prediction): route hypo_treatment notes through the rescue curve"
```

---

### Task 4: `hypo_events` schema, entity and repository

**Files:**
- Create: `src/main/resources/db/migration/V11__hypo_events.sql`
- Create: `src/main/java/che/glucosemonitorbe/entity/HypoEvent.java`
- Create: `src/main/java/che/glucosemonitorbe/repository/HypoEventRepository.java`
- Test: `src/test/java/che/glucosemonitorbe/repository/HypoEventRepositoryIntegrationTest.java`

**Interfaces:**
- Produces: `HypoEvent` entity with `State { OPEN, CONFIRMED, DISMISSED, EXPIRED }` and getters/setters for `id`, `userId`, `triggerGlucoseMmol`, `state`, `noteId`, `detectedAt`, `resolvedAt`, `updatedAt`; `HypoEventRepository` with `findByUserIdAndStateOrderByDetectedAtDesc`, `findByUserIdOrderByDetectedAtDesc`, `findFirstByUserIdAndStateOrderByDetectedAtDesc`, `findFirstByUserIdOrderByDetectedAtDesc`.

- [ ] **Step 1: Write the migration**

Create `src/main/resources/db/migration/V11__hypo_events.sql`:

```sql
-- Hypo prompt lifecycle: one row per detected sub-threshold glucose window, opened by
-- GlucoseAnomalyDetector and resolved by the user confirming a rescue carb or dismissing.
-- Mirrors unlogged_event_flags so both detect -> prompt -> resolve flows read alike.

CREATE TABLE IF NOT EXISTS hypo_events (
    id                    UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               UUID             NOT NULL,
    trigger_glucose_mmol  DOUBLE PRECISION NOT NULL,
    state                 VARCHAR(12)      NOT NULL DEFAULT 'OPEN',
    note_id               UUID,
    detected_at           TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    resolved_at           TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_hypo_events_user  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_hypo_events_note  FOREIGN KEY (note_id) REFERENCES notes(id) ON DELETE SET NULL,
    CONSTRAINT chk_hypo_event_state CHECK (state IN ('OPEN','CONFIRMED','DISMISSED','EXPIRED'))
);

CREATE INDEX IF NOT EXISTS idx_hypo_events_user_state ON hypo_events(user_id, state);
CREATE INDEX IF NOT EXISTS idx_hypo_events_detected   ON hypo_events(detected_at);

COMMENT ON TABLE hypo_events IS
    'Detected sub-3.9 mmol/L windows prompting the user to log a fast-acting rescue carb.';
COMMENT ON COLUMN hypo_events.note_id IS
    'The hypo_treatment note created on confirm. ON DELETE SET NULL: deleting the note must not erase the record that a hypo occurred and was prompted.';
```

- [ ] **Step 2: Write the entity**

Create `src/main/java/che/glucosemonitorbe/entity/HypoEvent.java`:

```java
package che.glucosemonitorbe.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A detected hypoglycaemia window prompting the user to log a fast-acting rescue carb.
 *
 * <p>Lifecycle: {@code OPEN} (detected) -> {@code CONFIRMED} (user logged a rescue),
 * {@code DISMISSED} (user declined) or {@code EXPIRED} (glucose recovered on its own).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "hypo_events")
public class HypoEvent {

    public enum State { OPEN, CONFIRMED, DISMISSED, EXPIRED }

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** The CGM reading that opened this event [mmol/L]. */
    @Column(name = "trigger_glucose_mmol", nullable = false)
    private Double triggerGlucoseMmol;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 12)
    @Builder.Default
    private State state = State.OPEN;

    /** The hypo_treatment note created when the user confirmed. Null until then. */
    @Column(name = "note_id")
    private UUID noteId;

    @CreationTimestamp
    @Column(name = "detected_at", nullable = false, updatable = false)
    private LocalDateTime detectedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();
}
```

- [ ] **Step 3: Write the repository**

Create `src/main/java/che/glucosemonitorbe/repository/HypoEventRepository.java`:

```java
package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.entity.HypoEvent;
import che.glucosemonitorbe.entity.HypoEvent.State;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HypoEventRepository extends JpaRepository<HypoEvent, UUID> {

    List<HypoEvent> findByUserIdOrderByDetectedAtDesc(UUID userId);

    List<HypoEvent> findByUserIdAndStateOrderByDetectedAtDesc(UUID userId, State state);

    /** The user's currently-open prompt, if any. Used to avoid opening a duplicate. */
    Optional<HypoEvent> findFirstByUserIdAndStateOrderByDetectedAtDesc(UUID userId, State state);

    /** The user's most recent event in any state. Used for the re-prompt suppression window. */
    Optional<HypoEvent> findFirstByUserIdOrderByDetectedAtDesc(UUID userId);
}
```

- [ ] **Step 4: Write the repository test**

Read `src/test/java/che/glucosemonitorbe/repository/CgmReadingRepositoryIntegrationTest.java` first and copy its annotation stack verbatim — `@SpringBootTest`, `@Testcontainers` with a `@Container PostgreSQLContainer` + `@ServiceConnection`, `@AutoConfigureTestDatabase`, `@ActiveProfiles`, `@Transactional`. Repository tests here run against a real Postgres container, not H2, and the annotations differ from the plain-Mockito service tests.

`hypo_events.user_id` is a FK to `users`, so the test must persist a real `User` in `@BeforeEach` before saving any event — copy that setup from the same file.

Create `src/test/java/che/glucosemonitorbe/repository/HypoEventRepositoryIntegrationTest.java` (matching the project's `...IntegrationTest` naming for repository tests) with these cases:

```java
    @Test
    void findsTheOpenEventForAUser() {
        repository.save(HypoEvent.builder()
                .userId(userId).triggerGlucoseMmol(3.4).state(State.OPEN).build());

        Optional<HypoEvent> open =
                repository.findFirstByUserIdAndStateOrderByDetectedAtDesc(userId, State.OPEN);

        assertThat(open).isPresent();
        assertThat(open.get().getTriggerGlucoseMmol()).isEqualTo(3.4);
    }

    @Test
    void doesNotReturnAResolvedEventAsOpen() {
        repository.save(HypoEvent.builder()
                .userId(userId).triggerGlucoseMmol(3.4).state(State.CONFIRMED).build());

        assertThat(repository.findFirstByUserIdAndStateOrderByDetectedAtDesc(userId, State.OPEN))
                .isEmpty();
    }

    @Test
    void mostRecentEventIsReturnedRegardlessOfState() {
        repository.save(HypoEvent.builder()
                .userId(userId).triggerGlucoseMmol(3.4).state(State.DISMISSED).build());

        assertThat(repository.findFirstByUserIdOrderByDetectedAtDesc(userId)).isPresent();
    }
```

- [ ] **Step 5: Run the test**

Run: `./gradlew test --tests 'che.glucosemonitorbe.repository.HypoEventRepositoryIntegrationTest'`
Expected: PASS, 3 tests. Flyway applies `V11` automatically against the test container.

If it fails with a Hibernate schema-validation error, the entity column names do not match the migration — `ddl-auto: validate` is deliberately strict. Compare each `@Column(name = ...)` against the SQL.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/V11__hypo_events.sql \
        src/main/java/che/glucosemonitorbe/entity/HypoEvent.java \
        src/main/java/che/glucosemonitorbe/repository/HypoEventRepository.java \
        src/test/java/che/glucosemonitorbe/repository/HypoEventRepositoryIntegrationTest.java
git commit -m "feat(hypo): add hypo_events table, entity and repository"
```

---

### Task 5: Hypo event detection — open, expire, suppress

**Files:**
- Create: `src/main/java/che/glucosemonitorbe/service/observer/HypoThresholds.java`
- Create: `src/main/java/che/glucosemonitorbe/service/HypoEventService.java`
- Modify: `src/main/java/che/glucosemonitorbe/service/observer/GlucoseAlertEvaluator.java:28` (use the shared constant)
- Modify: `src/main/java/che/glucosemonitorbe/service/observer/GlucoseAnomalyDetector.java` (call the new service)
- Modify: `src/main/java/che/glucosemonitorbe/config/FeatureToggleConfig.java`
- Modify: `src/main/java/che/glucosemonitorbe/service/FeatureToggleService.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/che/glucosemonitorbe/service/HypoEventServiceTest.java`

**Interfaces:**
- Consumes: `HypoEventRepository` (Task 4), `HypoEvent`/`State` (Task 4).
- Produces: `HypoThresholds.HYPO_MMOL` (3.9), `.RECOVERY_MMOL` (4.5), `.SUPPRESSION_MINUTES` (15); `HypoEventService.onGlucoseReading(UUID userId, double glucoseMmol)` (void).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/che/glucosemonitorbe/service/HypoEventServiceTest.java`:

```java
package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.entity.HypoEvent;
import che.glucosemonitorbe.entity.HypoEvent.State;
import che.glucosemonitorbe.repository.HypoEventRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HypoEventServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private HypoEventRepository repository;
    private NoteRepository noteRepository;
    private FeatureToggleConfig config;
    private HypoEventService service;

    @BeforeEach
    void setUp() {
        repository = mock(HypoEventRepository.class);
        noteRepository = mock(NoteRepository.class);
        config = new FeatureToggleConfig();
        config.setHypoRescueLoggingEnabled(true);
        service = new HypoEventService(repository, noteRepository, config);
        when(repository.findFirstByUserIdAndStateOrderByDetectedAtDesc(USER_ID, State.OPEN))
                .thenReturn(Optional.empty());
        when(repository.findFirstByUserIdOrderByDetectedAtDesc(USER_ID))
                .thenReturn(Optional.empty());
        when(repository.save(any(HypoEvent.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void opensAnEventWhenGlucoseGoesBelowThreshold() {
        service.onGlucoseReading(USER_ID, 3.4);

        ArgumentCaptor<HypoEvent> saved = ArgumentCaptor.forClass(HypoEvent.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getState()).isEqualTo(State.OPEN);
        assertThat(saved.getValue().getTriggerGlucoseMmol()).isEqualTo(3.4);
    }

    @Test
    void doesNotOpenAtOrAboveThreshold() {
        service.onGlucoseReading(USER_ID, 3.9);
        verify(repository, never()).save(any());
    }

    @Test
    void doesNotOpenASecondEventWhileOneIsOpen() {
        when(repository.findFirstByUserIdAndStateOrderByDetectedAtDesc(USER_ID, State.OPEN))
                .thenReturn(Optional.of(openEvent(LocalDateTime.now().minusMinutes(5))));

        service.onGlucoseReading(USER_ID, 3.2);

        verify(repository, never()).save(any());
    }

    @Test
    void expiresAnOpenEventOnceGlucoseRecovers() {
        HypoEvent open = openEvent(LocalDateTime.now().minusMinutes(10));
        when(repository.findFirstByUserIdAndStateOrderByDetectedAtDesc(USER_ID, State.OPEN))
                .thenReturn(Optional.of(open));

        service.onGlucoseReading(USER_ID, 4.6);

        assertThat(open.getState()).isEqualTo(State.EXPIRED);
        assertThat(open.getResolvedAt()).isNotNull();
        verify(repository).save(open);
    }

    /** Between 3.9 and 4.5 is the hysteresis band: neither open nor expire. */
    @Test
    void doesNotExpireInsideTheHysteresisBand() {
        HypoEvent open = openEvent(LocalDateTime.now().minusMinutes(10));
        when(repository.findFirstByUserIdAndStateOrderByDetectedAtDesc(USER_ID, State.OPEN))
                .thenReturn(Optional.of(open));

        service.onGlucoseReading(USER_ID, 4.2);

        assertThat(open.getState()).isEqualTo(State.OPEN);
        verify(repository, never()).save(any());
    }

    @Test
    void suppressesANewEventWithinFifteenMinutesOfTheLastResolution() {
        HypoEvent recent = openEvent(LocalDateTime.now().minusMinutes(5));
        recent.setState(State.DISMISSED);
        recent.setResolvedAt(LocalDateTime.now().minusMinutes(5));
        when(repository.findFirstByUserIdOrderByDetectedAtDesc(USER_ID))
                .thenReturn(Optional.of(recent));

        service.onGlucoseReading(USER_ID, 3.2);

        verify(repository, never()).save(any());
    }

    /** Rule of 15: still low after the window, so prompt again - that is correct, not a nag. */
    @Test
    void promptsAgainOnceTheSuppressionWindowHasElapsed() {
        HypoEvent old = openEvent(LocalDateTime.now().minusMinutes(30));
        old.setState(State.DISMISSED);
        old.setResolvedAt(LocalDateTime.now().minusMinutes(20));
        when(repository.findFirstByUserIdOrderByDetectedAtDesc(USER_ID))
                .thenReturn(Optional.of(old));

        service.onGlucoseReading(USER_ID, 3.2);

        verify(repository).save(any(HypoEvent.class));
    }

    @Test
    void doesNothingWhenTheFeatureIsDisabled() {
        config.setHypoRescueLoggingEnabled(false);
        service.onGlucoseReading(USER_ID, 3.2);
        verifyNoInteractions(repository);
    }

    private HypoEvent openEvent(LocalDateTime detectedAt) {
        HypoEvent e = HypoEvent.builder()
                .userId(USER_ID).triggerGlucoseMmol(3.4).state(State.OPEN).build();
        e.setId(UUID.randomUUID());
        e.setDetectedAt(detectedAt);
        return e;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'che.glucosemonitorbe.service.HypoEventServiceTest'`
Expected: FAIL — compilation error, `HypoEventService` and `setHypoRescueLoggingEnabled` do not exist.

- [ ] **Step 3: Add the shared thresholds**

Create `src/main/java/che/glucosemonitorbe/service/observer/HypoThresholds.java`:

```java
package che.glucosemonitorbe.service.observer;

/**
 * The glucose thresholds that define a hypo, shared by every path that needs them.
 *
 * <p>These were previously private to {@link GlucoseAlertEvaluator}. A second copy elsewhere would
 * make "is this a hypo" answerable two ways, which is the failure mode this codebase has already
 * paid for once (see the dual-computation audit of 2026-08-08).
 */
public final class HypoThresholds {

    private HypoThresholds() {}

    /** ADA/ATTD Level 1 hypoglycaemia [mmol/L]. At or above this is not a hypo. */
    public static final double HYPO_MMOL = 3.9;

    /**
     * Glucose must reach this before an open hypo is considered resolved [mmol/L].
     *
     * <p>The gap above {@link #HYPO_MMOL} is deliberate hysteresis: a reading hovering on the
     * threshold would otherwise open and close the prompt on alternate scans.
     */
    public static final double RECOVERY_MMOL = 4.5;

    /**
     * How long after resolving one hypo event before another may open [min].
     *
     * <p>Matches the clinical "rule of 15" - treat with 15 g, recheck after 15 minutes, re-treat if
     * still low. Without it, resolving an event leaves no OPEN row and the next 5-minute scan would
     * immediately re-open while the user is still low.
     */
    public static final int SUPPRESSION_MINUTES = 15;
}
```

In `GlucoseAlertEvaluator.java`, replace lines 28 and 31 so the evaluator consumes the shared values rather than its own copies:

```java
    private static final double HYPO_THRESHOLD       = HypoThresholds.HYPO_MMOL;
    private static final double HYPO_WARN_THRESHOLD  = HypoThresholds.RECOVERY_MMOL;
```

Both values are unchanged (3.9 and 4.5), so no evaluator behaviour changes.

- [ ] **Step 4: Add the feature flag**

In `src/main/java/che/glucosemonitorbe/config/FeatureToggleConfig.java`, alongside the other flags:

```java
    // Hypo rescue-carb logging: prompt the user to log fast carbs when glucose drops below
    // 3.9 mmol/L. Off here, enabled per-environment in application.yml, matching the convention
    // used by digital-twin-enabled and unlogged-event-detection-enabled.
    private boolean hypoRescueLoggingEnabled = false;
```

In `src/main/java/che/glucosemonitorbe/service/FeatureToggleService.java`, add a case to the `isEnabled` switch, immediately after the `unlogged-event-detection-enabled` line:

```java
            case "hypo-rescue-logging-enabled"  -> config.isHypoRescueLoggingEnabled();
```

In `src/main/resources/application.yml`, under `app.features`, immediately after the `unlogged-event-detection-enabled` entry:

```yaml
    # Hypo rescue-carb logging: prompt to log fast carbs when glucose drops below 3.9 mmol/L.
    hypo-rescue-logging-enabled: ${APP_FEATURES_HYPO_RESCUE_LOGGING_ENABLED:true}
```

- [ ] **Step 5: Write the service**

Create `src/main/java/che/glucosemonitorbe/service/HypoEventService.java`:

```java
package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.entity.HypoEvent;
import che.glucosemonitorbe.entity.HypoEvent.State;
import che.glucosemonitorbe.repository.HypoEventRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.service.observer.HypoThresholds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the hypo-prompt lifecycle: opening an event when glucose goes low, expiring it when glucose
 * recovers, and suppressing a re-prompt for a short window after the user resolves one.
 *
 * <p>Detection lives here rather than on the client so that "is this a hypo" has exactly one
 * answer, shared with {@code GlucoseAlertEvaluator} via {@link HypoThresholds}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HypoEventService {

    private final HypoEventRepository repository;
    private final NoteRepository noteRepository;
    private final FeatureToggleConfig featureToggleConfig;

    /**
     * Advance the hypo lifecycle for one user given their latest CGM reading.
     * Called on every observer scan; a no-op when the feature is disabled.
     */
    @Transactional
    public void onGlucoseReading(UUID userId, double glucoseMmol) {
        if (!featureToggleConfig.isHypoRescueLoggingEnabled()) {
            return;
        }

        Optional<HypoEvent> open =
                repository.findFirstByUserIdAndStateOrderByDetectedAtDesc(userId, State.OPEN);

        if (glucoseMmol >= HypoThresholds.RECOVERY_MMOL) {
            open.ifPresent(this::expire);
            return;
        }

        if (glucoseMmol >= HypoThresholds.HYPO_MMOL) {
            // Hysteresis band: not low enough to open, not recovered enough to close.
            return;
        }

        if (open.isPresent()) {
            return;   // already prompting
        }
        if (isSuppressed(userId)) {
            return;
        }

        HypoEvent event = HypoEvent.builder()
                .userId(userId)
                .triggerGlucoseMmol(glucoseMmol)
                .state(State.OPEN)
                .build();
        event.setUpdatedAt(LocalDateTime.now());
        repository.save(event);
        log.info("Hypo event opened for user={} at {} mmol/L", userId, glucoseMmol);
    }

    private void expire(HypoEvent event) {
        event.setState(State.EXPIRED);
        event.setResolvedAt(LocalDateTime.now());
        event.setUpdatedAt(LocalDateTime.now());
        repository.save(event);
        log.debug("Hypo event {} expired - glucose recovered", event.getId());
    }

    /**
     * True while the most recent event is still inside its post-resolution quiet window.
     * An EXPIRED event does not suppress: glucose recovered and then fell again, which is a
     * genuinely new hypo.
     */
    private boolean isSuppressed(UUID userId) {
        Optional<HypoEvent> latest = repository.findFirstByUserIdOrderByDetectedAtDesc(userId);
        if (latest.isEmpty()) {
            return false;
        }
        HypoEvent e = latest.get();
        if (e.getState() != State.CONFIRMED && e.getState() != State.DISMISSED) {
            return false;
        }
        LocalDateTime since = e.getResolvedAt() != null ? e.getResolvedAt() : e.getDetectedAt();
        if (since == null) {
            return false;
        }
        return Duration.between(since, LocalDateTime.now()).toMinutes()
                < HypoThresholds.SUPPRESSION_MINUTES;
    }
}
```

`noteRepository` is unused in this task; Task 6 adds `confirm` which needs it. Leaving the field in now keeps the constructor signature stable across both tasks so the test written in Step 1 does not change.

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew test --tests 'che.glucosemonitorbe.service.HypoEventServiceTest'`
Expected: PASS, 8 tests.

- [ ] **Step 7: Wire the detector to the service**

In `GlucoseAnomalyDetector.java`, add the field:

```java
    private final che.glucosemonitorbe.service.HypoEventService hypoEventService;
```

and in `evaluateUser`, immediately before the `alertService.evaluateAll(...)` dispatch:

```java
        // 3. Advance the hypo-prompt lifecycle from the same reading the alerts use.
        hypoEventService.onGlucoseReading(userId, currentGlucose);
```

Update `GlucoseAnomalyDetectorTest.setUp` to construct the detector with the extra collaborator:

```java
        hypoEventService = mock(HypoEventService.class);
        detector = new GlucoseAnomalyDetector(cgmRepo, noteRepo, userRepo, alertService, hypoEventService);
```

and add a test:

```java
    @Test
    void forwardsTheLatestReadingToTheHypoLifecycle() {
        long now = System.currentTimeMillis();
        when(cgmRepo.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(eq(USER_ID), any(), any()))
                .thenReturn(List.of(reading(now - 300_000, 90), reading(now, 63)));  // 5.0 -> 3.5

        detector.evaluateUser(USER_ID, "tester");

        verify(hypoEventService).onGlucoseReading(eq(USER_ID), doubleThat(v -> v < 3.6));
    }
```

Add the static import `org.mockito.ArgumentMatchers.doubleThat`.

- [ ] **Step 8: Run both test classes**

Run: `./gradlew test --tests 'che.glucosemonitorbe.service.HypoEventServiceTest' --tests 'che.glucosemonitorbe.service.observer.GlucoseAnomalyDetectorTest'`
Expected: PASS, 12 tests total.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/che/glucosemonitorbe/service/observer/HypoThresholds.java \
        src/main/java/che/glucosemonitorbe/service/HypoEventService.java \
        src/main/java/che/glucosemonitorbe/service/observer/GlucoseAlertEvaluator.java \
        src/main/java/che/glucosemonitorbe/service/observer/GlucoseAnomalyDetector.java \
        src/main/java/che/glucosemonitorbe/config/FeatureToggleConfig.java \
        src/main/java/che/glucosemonitorbe/service/FeatureToggleService.java \
        src/main/resources/application.yml \
        src/test/java/che/glucosemonitorbe/service/HypoEventServiceTest.java \
        src/test/java/che/glucosemonitorbe/service/observer/GlucoseAnomalyDetectorTest.java
git commit -m "feat(hypo): detect hypo events with hysteresis and re-prompt suppression

Thresholds are promoted out of GlucoseAlertEvaluator into one shared
holder so the alert path and the prompt path cannot disagree on what
counts as a hypo."
```

---

### Task 6: Confirm and dismiss

**Files:**
- Create: `src/main/java/che/glucosemonitorbe/dto/HypoEventDTO.java`
- Create: `src/main/java/che/glucosemonitorbe/dto/ConfirmHypoEventRequest.java`
- Modify: `src/main/java/che/glucosemonitorbe/service/HypoEventService.java`
- Test: `src/test/java/che/glucosemonitorbe/service/HypoEventServiceConfirmTest.java`

**Interfaces:**
- Consumes: `HypoEventService` constructor from Task 5 (unchanged), `Note.TYPE_HYPO_TREATMENT` (Task 2).
- Produces: `HypoEventDTO` record with `from(HypoEvent)`; `ConfirmHypoEventRequest(Double grams)`; `HypoEventService.list(UUID, State)` returning `List<HypoEventDTO>`; `HypoEventService.confirm(UUID userId, UUID eventId, Double grams)` and `HypoEventService.dismiss(UUID userId, UUID eventId)`, both returning `HypoEventDTO`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/che/glucosemonitorbe/service/HypoEventServiceConfirmTest.java`:

```java
package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.dto.HypoEventDTO;
import che.glucosemonitorbe.entity.HypoEvent;
import che.glucosemonitorbe.entity.HypoEvent.State;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.HypoEventRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HypoEventServiceConfirmTest {

    private static final UUID USER_ID  = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID NOTE_ID  = UUID.randomUUID();

    private HypoEventRepository repository;
    private NoteRepository noteRepository;
    private HypoEventService service;

    @BeforeEach
    void setUp() {
        repository = mock(HypoEventRepository.class);
        noteRepository = mock(NoteRepository.class);
        FeatureToggleConfig config = new FeatureToggleConfig();
        config.setHypoRescueLoggingEnabled(true);
        service = new HypoEventService(repository, noteRepository, config);
        when(repository.save(any(HypoEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(noteRepository.save(any(Note.class))).thenAnswer(inv -> {
            Note n = inv.getArgument(0);
            n.setId(NOTE_ID);
            return n;
        });
    }

    @Test
    void confirmCreatesAHypoTreatmentNoteAndLinksIt() {
        when(repository.findById(EVENT_ID)).thenReturn(Optional.of(openEvent()));

        HypoEventDTO dto = service.confirm(USER_ID, EVENT_ID, 15.0);

        ArgumentCaptor<Note> note = ArgumentCaptor.forClass(Note.class);
        verify(noteRepository).save(note.capture());
        assertThat(note.getValue().getType()).isEqualTo(Note.TYPE_HYPO_TREATMENT);
        assertThat(note.getValue().getCarbs()).isEqualTo(15.0);
        assertThat(note.getValue().getInsulin()).isZero();
        assertThat(dto.state()).isEqualTo("CONFIRMED");
        assertThat(dto.noteId()).isEqualTo(NOTE_ID);
    }

    /**
     * Double-logging rescue carbs is a safety problem, not just a data one: the phantom carbs
     * suppress the next genuine prompt and inflate the prediction while the user is still low.
     */
    @Test
    void confirmingTwiceCreatesExactlyOneNote() {
        HypoEvent alreadyConfirmed = openEvent();
        alreadyConfirmed.setState(State.CONFIRMED);
        alreadyConfirmed.setNoteId(NOTE_ID);
        when(repository.findById(EVENT_ID)).thenReturn(Optional.of(alreadyConfirmed));

        HypoEventDTO dto = service.confirm(USER_ID, EVENT_ID, 15.0);

        verify(noteRepository, never()).save(any());
        assertThat(dto.noteId()).isEqualTo(NOTE_ID);
        assertThat(dto.state()).isEqualTo("CONFIRMED");
    }

    @Test
    void confirmOnADismissedEventIsRejected() {
        HypoEvent dismissed = openEvent();
        dismissed.setState(State.DISMISSED);
        when(repository.findById(EVENT_ID)).thenReturn(Optional.of(dismissed));

        assertThatThrownBy(() -> service.confirm(USER_ID, EVENT_ID, 15.0))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    @Test
    void anotherUsersEventIsNotFound() {
        HypoEvent someoneElses = openEvent();
        someoneElses.setUserId(UUID.randomUUID());
        when(repository.findById(EVENT_ID)).thenReturn(Optional.of(someoneElses));

        assertThatThrownBy(() -> service.confirm(USER_ID, EVENT_ID, 15.0))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void gramsOutsideTheAllowedRangeIsRejected() {
        when(repository.findById(EVENT_ID)).thenReturn(Optional.of(openEvent()));

        assertThatThrownBy(() -> service.confirm(USER_ID, EVENT_ID, 0.0))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        assertThatThrownBy(() -> service.confirm(USER_ID, EVENT_ID, 101.0))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        assertThatThrownBy(() -> service.confirm(USER_ID, EVENT_ID, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void dismissResolvesWithoutCreatingANote() {
        when(repository.findById(EVENT_ID)).thenReturn(Optional.of(openEvent()));

        HypoEventDTO dto = service.dismiss(USER_ID, EVENT_ID);

        verify(noteRepository, never()).save(any());
        assertThat(dto.state()).isEqualTo("DISMISSED");
    }

    private HypoEvent openEvent() {
        HypoEvent e = HypoEvent.builder()
                .userId(USER_ID).triggerGlucoseMmol(3.4).state(State.OPEN).build();
        e.setId(EVENT_ID);
        e.setDetectedAt(LocalDateTime.now().minusMinutes(2));
        return e;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'che.glucosemonitorbe.service.HypoEventServiceConfirmTest'`
Expected: FAIL — compilation error, `HypoEventDTO` and `confirm`/`dismiss` do not exist.

- [ ] **Step 3: Create the DTOs**

Create `src/main/java/che/glucosemonitorbe/dto/HypoEventDTO.java`:

```java
package che.glucosemonitorbe.dto;

import che.glucosemonitorbe.entity.HypoEvent;

import java.time.LocalDateTime;
import java.util.UUID;

/** API view of a {@link HypoEvent}. Glucose is mmol/L; the client converts for display. */
public record HypoEventDTO(
        UUID id,
        Double triggerGlucoseMmol,
        String state,
        UUID noteId,
        LocalDateTime detectedAt,
        LocalDateTime resolvedAt) {

    public static HypoEventDTO from(HypoEvent e) {
        return new HypoEventDTO(
                e.getId(),
                e.getTriggerGlucoseMmol(),
                e.getState().name(),
                e.getNoteId(),
                e.getDetectedAt(),
                e.getResolvedAt());
    }
}
```

Create `src/main/java/che/glucosemonitorbe/dto/ConfirmHypoEventRequest.java`:

```java
package che.glucosemonitorbe.dto;

/**
 * Body for confirming a hypo event: the grams of fast-acting carb the user actually took.
 * Required - a confirm with no amount would log a rescue of unknown size.
 */
public record ConfirmHypoEventRequest(Double grams) {}
```

- [ ] **Step 4: Add list, confirm and dismiss to the service**

Append to `HypoEventService`, adding imports for `che.glucosemonitorbe.dto.HypoEventDTO`, `che.glucosemonitorbe.entity.Note`, `org.springframework.http.HttpStatus`, `org.springframework.web.server.ResponseStatusException`, and `java.util.List`:

```java
    /** Largest single rescue dose accepted [g]. Above this is a typo, not a treatment. */
    private static final double MAX_RESCUE_GRAMS = 100.0;

    @Transactional(readOnly = true)
    public List<HypoEventDTO> list(UUID userId, State stateFilter) {
        List<HypoEvent> events = stateFilter == null
                ? repository.findByUserIdOrderByDetectedAtDesc(userId)
                : repository.findByUserIdAndStateOrderByDetectedAtDesc(userId, stateFilter);
        return events.stream().map(HypoEventDTO::from).toList();
    }

    /**
     * Log a rescue carb against an open hypo event.
     *
     * <p>Idempotent by design: confirming an already-confirmed event returns the note that was
     * already created rather than logging a second one. Double-logged rescue carbs would suppress
     * the next genuine prompt and inflate the prediction while the user is still low.
     */
    @Transactional
    public HypoEventDTO confirm(UUID userId, UUID eventId, Double grams) {
        HypoEvent event = requireOwnEvent(userId, eventId);

        if (event.getState() == State.CONFIRMED) {
            return HypoEventDTO.from(event);
        }
        if (event.getState() != State.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "409 Hypo event is no longer open");
        }

        double amount = validateGrams(grams);

        Note note = new Note();
        note.setUserId(userId);
        note.setTimestamp(LocalDateTime.now());
        note.setCarbs(amount);
        note.setInsulin(0.0);
        note.setMeal("Hypo treatment");
        note.setType(Note.TYPE_HYPO_TREATMENT);
        note.setCreatedAt(LocalDateTime.now());
        note.setUpdatedAt(LocalDateTime.now());
        Note saved = noteRepository.save(note);

        event.setState(State.CONFIRMED);
        event.setNoteId(saved.getId());
        event.setResolvedAt(LocalDateTime.now());
        event.setUpdatedAt(LocalDateTime.now());
        log.info("Hypo event {} confirmed with {} g rescue carbs", eventId, amount);
        return HypoEventDTO.from(repository.save(event));
    }

    @Transactional
    public HypoEventDTO dismiss(UUID userId, UUID eventId) {
        HypoEvent event = requireOwnEvent(userId, eventId);
        if (event.getState() == State.DISMISSED) {
            return HypoEventDTO.from(event);
        }
        if (event.getState() != State.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "409 Hypo event is no longer open");
        }
        event.setState(State.DISMISSED);
        event.setResolvedAt(LocalDateTime.now());
        event.setUpdatedAt(LocalDateTime.now());
        return HypoEventDTO.from(repository.save(event));
    }

    /** 404 rather than 403 for another user's event - do not disclose that it exists. */
    private HypoEvent requireOwnEvent(UUID userId, UUID eventId) {
        HypoEvent event = repository.findById(eventId)
                .filter(e -> e.getUserId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "404 Hypo event not found"));
        return event;
    }

    private double validateGrams(Double grams) {
        if (grams == null || !Double.isFinite(grams) || grams <= 0 || grams > MAX_RESCUE_GRAMS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "400 grams must be greater than 0 and at most " + MAX_RESCUE_GRAMS);
        }
        return grams;
    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests 'che.glucosemonitorbe.service.HypoEventServiceConfirmTest'`
Expected: PASS, 6 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/che/glucosemonitorbe/dto/HypoEventDTO.java \
        src/main/java/che/glucosemonitorbe/dto/ConfirmHypoEventRequest.java \
        src/main/java/che/glucosemonitorbe/service/HypoEventService.java \
        src/test/java/che/glucosemonitorbe/service/HypoEventServiceConfirmTest.java
git commit -m "feat(hypo): idempotent confirm and dismiss for hypo events

Confirming twice returns the existing note rather than logging a second
rescue - double-logged carbs would suppress the next prompt and inflate
the prediction while the user is still low."
```

---

### Task 7: REST controller

**Files:**
- Create: `src/main/java/che/glucosemonitorbe/controller/HypoEventController.java`
- Test: `src/test/java/che/glucosemonitorbe/controller/HypoEventControllerTest.java`

**Interfaces:**
- Consumes: `HypoEventService.list/confirm/dismiss` (Task 6), `HypoEventDTO`, `ConfirmHypoEventRequest`, `FeatureToggleService.isEnabled("hypo-rescue-logging-enabled")` (Task 5).
- Produces: `GET /api/hypo-events`, `POST /api/hypo-events/{id}/confirm`, `POST /api/hypo-events/{id}/dismiss`.

- [ ] **Step 1: Write the controller**

Read `src/main/java/che/glucosemonitorbe/controller/UnloggedEventController.java` in full first — this controller mirrors it exactly, including the `requireFeature()` guard and the `userId(auth)` helper.

Create `src/main/java/che/glucosemonitorbe/controller/HypoEventController.java`:

```java
package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.ConfirmHypoEventRequest;
import che.glucosemonitorbe.dto.HypoEventDTO;
import che.glucosemonitorbe.entity.HypoEvent.State;
import che.glucosemonitorbe.service.FeatureToggleService;
import che.glucosemonitorbe.service.HypoEventService;
import che.glucosemonitorbe.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Read + resolve API for hypo events - windows where CGM glucose dropped below the hypo threshold.
 * Events are opened by the observer; this endpoint lets the app prompt the user to log a
 * fast-acting rescue carb, or dismiss the prompt.
 */
@Tag(name = "Hypo events",
        description = "Detected hypoglycaemia windows prompting a fast-carb rescue log")
@RestController
@RequestMapping("/api/hypo-events")
@RequiredArgsConstructor
public class HypoEventController {

    private final HypoEventService hypoEventService;
    private final FeatureToggleService featureToggleService;
    private final UserService userService;

    @Operation(summary = "List the authenticated user's hypo events (optionally filtered by state)")
    @GetMapping
    public ResponseEntity<List<HypoEventDTO>> list(Authentication auth,
                                                   @RequestParam(required = false) State state) {
        requireFeature();
        return ResponseEntity.ok(hypoEventService.list(userId(auth), state));
    }

    @Operation(summary = "Confirm a hypo event by logging the rescue carbs taken")
    @PostMapping("/{id}/confirm")
    public ResponseEntity<HypoEventDTO> confirm(Authentication auth, @PathVariable UUID id,
                                                @RequestBody ConfirmHypoEventRequest body) {
        requireFeature();
        Double grams = body != null ? body.grams() : null;
        return ResponseEntity.ok(hypoEventService.confirm(userId(auth), id, grams));
    }

    @Operation(summary = "Dismiss a hypo event without logging a rescue carb")
    @PostMapping("/{id}/dismiss")
    public ResponseEntity<HypoEventDTO> dismiss(Authentication auth, @PathVariable UUID id) {
        requireFeature();
        return ResponseEntity.ok(hypoEventService.dismiss(userId(auth), id));
    }

    // -- helpers ---------------------------------------------------------------

    private void requireFeature() {
        if (!featureToggleService.isEnabled("hypo-rescue-logging-enabled")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Hypo rescue logging is not enabled");
        }
    }

    private UUID userId(Authentication auth) {
        return userService.getUserByUsername(auth.getName()).getId();
    }
}
```

Confirm the `userId(Authentication)` helper body matches `UnloggedEventController`'s exactly. If that class resolves the user differently, copy its version verbatim rather than the one above.

- [ ] **Step 2: Write the controller test**

Read `src/test/java/che/glucosemonitorbe/controller/InsulinCalculatorControllerTest.java` first and copy its MockMvc setup, security configuration and `@WithMockUser` usage exactly.

Create `src/test/java/che/glucosemonitorbe/controller/HypoEventControllerTest.java` with that same setup and these cases:

```java
    @Test
    void listReturnsOpenEvents() throws Exception {
        when(featureToggleService.isEnabled("hypo-rescue-logging-enabled")).thenReturn(true);
        when(hypoEventService.list(any(), eq(State.OPEN))).thenReturn(List.of(
                new HypoEventDTO(EVENT_ID, 3.4, "OPEN", null,
                        LocalDateTime.now(), null)));

        mockMvc.perform(get("/api/hypo-events").param("state", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").value("OPEN"))
                .andExpect(jsonPath("$[0].triggerGlucoseMmol").value(3.4));
    }

    @Test
    void confirmPassesGramsThrough() throws Exception {
        when(featureToggleService.isEnabled("hypo-rescue-logging-enabled")).thenReturn(true);
        when(hypoEventService.confirm(any(), eq(EVENT_ID), eq(15.0))).thenReturn(
                new HypoEventDTO(EVENT_ID, 3.4, "CONFIRMED", NOTE_ID,
                        LocalDateTime.now(), LocalDateTime.now()));

        mockMvc.perform(post("/api/hypo-events/" + EVENT_ID + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grams\":15.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CONFIRMED"));
    }

    @Test
    void returnsNotFoundWhenTheFeatureIsDisabled() throws Exception {
        when(featureToggleService.isEnabled("hypo-rescue-logging-enabled")).thenReturn(false);

        mockMvc.perform(get("/api/hypo-events"))
                .andExpect(status().isNotFound());
    }
```

- [ ] **Step 3: Run the test**

Run: `./gradlew test --tests 'che.glucosemonitorbe.controller.HypoEventControllerTest'`
Expected: PASS, 3 tests.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/che/glucosemonitorbe/controller/HypoEventController.java \
        src/test/java/che/glucosemonitorbe/controller/HypoEventControllerTest.java
git commit -m "feat(hypo): add /api/hypo-events list, confirm and dismiss endpoints"
```

---

### Task 8: Isolate rescue carbs from the titration loops

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/service/VerificationService.java` (`preCheckEligibility`)
- Modify: `src/main/java/che/glucosemonitorbe/service/IsfMealWindowProfileService.java` (carb-window loop, around line 214-222)
- Test: `src/test/java/che/glucosemonitorbe/service/RescueCarbIsolationTest.java`

**Interfaces:**
- Consumes: `Note.isHypoTreatment()` (Task 2).
- Produces: no new public API.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/che/glucosemonitorbe/service/RescueCarbIsolationTest.java`. Read `IsfMealWindowProfileServiceTest.java` first and reuse its fixture-building helpers and mock setup for the ISF case — the service needs several collaborators and its own CGM fixtures.

Cases to cover:

```java
    /**
     * A rescue carb has no bolus behind it, so scoring it as a predicted-vs-actual meal would
     * feed unbolused carbs into the carb-ratio titration.
     */
    @Test
    void verificationSkipsHypoTreatmentNotes() {
        Note rescue = new Note(USER_ID, LocalDateTime.now(), 15.0, 0.0, "Hypo treatment");
        rescue.setType(Note.TYPE_HYPO_TREATMENT);
        rescue.setId(NOTE_ID);
        when(noteRepository.findById(NOTE_ID)).thenReturn(Optional.of(rescue));
        when(verificationEventRepository.findByNoteId(NOTE_ID)).thenReturn(Optional.empty());
        when(verificationEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        verificationService.enqueueNote(NOTE_ID, USER_ID);

        ArgumentCaptor<VerificationEvent> saved = ArgumentCaptor.forClass(VerificationEvent.class);
        verify(verificationEventRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(VerificationEvent.Status.SKIPPED);
        assertThat(saved.getValue().getSkipReason()).isEqualTo("hypo_treatment");
    }

    /**
     * A 15 g rescue inside the +/-2 h window crosses CARB_THRESHOLD_GRAMS, which would both
     * reclassify a correction bolus as meal-attached and subtract a phantom meal rise from the
     * ISF estimate.
     */
    @Test
    void isfEstimateIgnoresRescueCarbsNearACorrectionBolus() {
        // A correction bolus with no meal carbs, but a 15 g rescue logged 20 minutes later.
        // Expected: the bolus still scores with WEIGHT_CORRECTION and the ISF value matches
        // the no-rescue baseline.
    }
```

Fill the second test body using the fixture helpers from `IsfMealWindowProfileServiceTest`: build a correction bolus, a CGM pre/post pair, and a rescue note inside the window; assert the resulting weighted-sample mass and ISF equal those from an identical scenario with the rescue note absent.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'che.glucosemonitorbe.service.RescueCarbIsolationTest'`
Expected: FAIL — the verification skip reason is `"no_insulin"`, and the ISF estimate differs from the baseline.

- [ ] **Step 3: Make the verification skip explicit**

In `VerificationService.preCheckEligibility`, add as the **first** check, before the carb-range test:

```java
        // A rescue carb has no bolus behind it. Verification exists to titrate carbRatio from
        // bolused meals, so scoring one would feed unbolused carbs into that loop. The
        // no-insulin check below happens to catch this too, but only incidentally - state it.
        if (note.isHypoTreatment()) return "hypo_treatment";
```

- [ ] **Step 4: Exclude rescue carbs from the ISF window**

In `IsfMealWindowProfileService`, inside the carb-window loop (the `for (Note n : allNotes)` block that accumulates `carbsGrams` and `carbEntries`), add as the first statement in the loop body:

```java
            // A rescue carb is a hypo treatment, not part of this bolus's nutrient envelope.
            // Counting it would push carbsGrams past CARB_THRESHOLD_GRAMS - reclassifying a
            // correction bolus as meal-attached - and subtract a meal rise that never happened.
            if (n.isHypoTreatment()) continue;
```

Excluding it here removes it from both `carbsGrams` (the weight decision) and `carbEntries` (the expected-rise subtraction), which is why one guard is sufficient.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests 'che.glucosemonitorbe.service.RescueCarbIsolationTest'`
Expected: PASS, 2 tests.

- [ ] **Step 6: Confirm the loops still behave for ordinary notes**

Run: `./gradlew test --tests 'che.glucosemonitorbe.service.VerificationServiceTest' --tests 'che.glucosemonitorbe.service.IsfMealWindowProfileServiceTest'`
Expected: PASS. No existing fixture uses `TYPE_HYPO_TREATMENT`, so neither guard fires for them.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/che/glucosemonitorbe/service/VerificationService.java \
        src/main/java/che/glucosemonitorbe/service/IsfMealWindowProfileService.java \
        src/test/java/che/glucosemonitorbe/service/RescueCarbIsolationTest.java
git commit -m "fix(learning): keep rescue carbs out of carbRatio and ISF titration

A 15 g rescue inside the ISF window crossed CARB_THRESHOLD_GRAMS,
reclassifying a correction bolus as meal-attached and subtracting a meal
rise that never happened."
```

---

### Task 9: Full backend verification

**Files:** none modified.

- [ ] **Step 1: Run the whole backend suite**

Run: `./gradlew test`
Expected: PASS, all tests.

- [ ] **Step 2: Verify the coverage gate still holds**

Run: `./gradlew jacocoTestCoverageVerification`
Expected: PASS. `CarbsOnBoardService` is inside the 80 % line-coverage rule and gained a new branch in Task 2; the rescue tests must cover it.

If it fails on `CarbsOnBoardService`, add a test to `CarbsOnBoardServiceRescueTest` for the uncovered path — most likely `minutesSinceEntry` beyond `MAX_DURATION_MIN`, or a null/zero-carb rescue entry.

- [ ] **Step 3: Verify the build**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit only if step 2 required a new test**

```bash
git add src/test/java/che/glucosemonitorbe/service/CarbsOnBoardServiceRescueTest.java
git commit -m "test(cob): cover the remaining rescue-curve branches"
```

---

### Task 10: iOS API layer

**Files:**
- Modify: `glucose-monitor-iphone/GlucoseMonitor/BackendAPI.swift`

**Interfaces:**
- Consumes: `GET /api/hypo-events`, `POST /api/hypo-events/{id}/confirm`, `POST /api/hypo-events/{id}/dismiss` (Task 7).
- Produces: `BackendAPI.HypoEvent` (Decodable, Identifiable), `BackendAPI.NoteType.hypoTreatment`, `BackendAPI.fetchOpenHypoEvents()`, `BackendAPI.confirmHypoEvent(id:grams:)`, `BackendAPI.dismissHypoEvent(id:)`.

- [ ] **Step 1: Add the note type constant**

In `BackendAPI.swift`, in the `NoteType` enum (around line 10-15):

```swift
        static let hypoTreatment = "hypo_treatment"
```

- [ ] **Step 2: Add the model**

Add near the other model structs:

```swift
    /// A detected hypoglycaemia window prompting the user to log a fast-acting rescue carb.
    /// `triggerGlucoseMmol` is always mmol/L; convert with `GlucoseUnit.fromMmol` for display.
    struct HypoEvent: Decodable, Identifiable, Equatable {
        let id: String
        let triggerGlucoseMmol: Double
        let state: String
        let noteId: String?
        let detectedAt: String?
        let resolvedAt: String?
    }

    /// Preset rescue amounts offered on the hypo prompt [g].
    enum RescueCarbPresets {
        static let options: [Double] = [10, 15, 20]
    }

    private struct ConfirmHypoEventBody: Encodable {
        let grams: Double
    }
```

- [ ] **Step 3: Add the three calls**

Add a `// MARK: - Hypo events` section following the exact shape of the existing `fetchNotes` / `createNote` functions:

```swift
    static func fetchOpenHypoEvents() async throws -> [HypoEvent] {
        try await performWithRefresh {
            let req = try authorizedRequest(path: "/api/hypo-events?state=OPEN")
            let (data, resp) = try await URLSession.shared.data(for: req)
            try checkStatus(resp, data: data)
            return try GlucoseMonitorAPI.jsonDecoder().decode([HypoEvent].self, from: data)
        }
    }

    static func confirmHypoEvent(id: String, grams: Double) async throws -> HypoEvent {
        try await performWithRefresh {
            var req = try authorizedRequest(path: "/api/hypo-events/\(id)/confirm", method: "POST")
            req.httpBody = try JSONEncoder().encode(ConfirmHypoEventBody(grams: grams))
            let (data, resp) = try await URLSession.shared.data(for: req)
            try checkStatus(resp, data: data)
            return try GlucoseMonitorAPI.jsonDecoder().decode(HypoEvent.self, from: data)
        }
    }

    static func dismissHypoEvent(id: String) async throws -> HypoEvent {
        try await performWithRefresh {
            let req = try authorizedRequest(path: "/api/hypo-events/\(id)/dismiss", method: "POST")
            let (data, resp) = try await URLSession.shared.data(for: req)
            try checkStatus(resp, data: data)
            return try GlucoseMonitorAPI.jsonDecoder().decode(HypoEvent.self, from: data)
        }
    }
```

- [ ] **Step 4: Build**

Run the iOS build the way this project already does it (check `glucose-monitor-iphone` for an existing scheme or build script; if using xcodebuild, target the `GlucoseMonitor` scheme with an iOS Simulator destination).
Expected: BUILD SUCCEEDED.

- [ ] **Step 5: Commit**

```bash
git add glucose-monitor-iphone/GlucoseMonitor/BackendAPI.swift
git commit -m "feat(ios): add hypo-event API client"
```

---

### Task 11: iOS prompt UI

**Files:**
- Create: `glucose-monitor-iphone/GlucoseMonitor/HypoPromptView.swift`
- Modify: `glucose-monitor-iphone/GlucoseMonitor/AppState.swift`
- Modify: `glucose-monitor-iphone/GlucoseMonitor/ContentView.swift`
- Test: `glucose-monitor-iphone/GlucoseMonitorTests/HypoPromptTests.swift`

**Interfaces:**
- Consumes: `BackendAPI.HypoEvent`, `BackendAPI.fetchOpenHypoEvents()`, `BackendAPI.confirmHypoEvent(id:grams:)`, `BackendAPI.dismissHypoEvent(id:)`, `BackendAPI.RescueCarbPresets.options` (Task 10); `GlucoseUnit.fromMmol(_:displayUnit:)` (existing).
- Produces: `AppState.openHypoEvent` (`@Published BackendAPI.HypoEvent?`), `AppState.refreshHypoEvents()`, `AppState.confirmHypo(grams:)`, `AppState.dismissHypo()`; `HypoPromptView`.

- [ ] **Step 1: Add state and actions to AppState**

In `AppState.swift`, alongside the other `@Published` properties (near line 15-30):

```swift
    @Published var openHypoEvent: BackendAPI.HypoEvent?
```

Add these methods next to the other refresh helpers:

```swift
    /// Poll for an open hypo prompt. Failures are silent: a missing prompt must never
    /// surface an error banner over the dashboard.
    func refreshHypoEvents() async {
        do {
            openHypoEvent = try await BackendAPI.fetchOpenHypoEvents().first
        } catch {
            openHypoEvent = nil
        }
    }

    /// Log a rescue carb against the open prompt, then refresh so COB reflects it.
    func confirmHypo(grams: Double) async {
        guard let event = openHypoEvent else { return }
        do {
            _ = try await BackendAPI.confirmHypoEvent(id: event.id, grams: grams)
            openHypoEvent = nil
            await refreshAll()
        } catch {
            errorMessage = "Could not log rescue carbs. Please try again."
        }
    }

    func dismissHypo() async {
        guard let event = openHypoEvent else { return }
        openHypoEvent = nil
        _ = try? await BackendAPI.dismissHypoEvent(id: event.id)
    }
```

In `refreshAll()` (around line 324), add a call to `await refreshHypoEvents()` alongside the other refreshes so the prompt appears on the normal dashboard cycle.

- [ ] **Step 2: Create the prompt view**

Create `glucose-monitor-iphone/GlucoseMonitor/HypoPromptView.swift`:

```swift
import SwiftUI

/// Confirm sheet shown when the backend has detected glucose below the hypo threshold.
///
/// One tap logs a preset amount. During a hypo the user is cognitively impaired, so the
/// common path is deliberately a single tap with no typing.
struct HypoPromptView: View {

    let event: BackendAPI.HypoEvent
    let displayUnit: String
    let onConfirm: (Double) -> Void
    let onDismiss: () -> Void

    @State private var customGrams: String = ""

    private var displayedGlucose: String {
        let value = GlucoseUnit.fromMmol(event.triggerGlucoseMmol, displayUnit: displayUnit)
        return GlucoseUnit.isMgdl(displayUnit)
            ? String(format: "%.0f", value)
            : String(format: "%.1f", value)
    }

    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 44))
                .foregroundStyle(.orange)

            VStack(spacing: 6) {
                Text("Low glucose")
                    .font(.title2.weight(.semibold))
                Text("\(displayedGlucose) \(displayUnit)")
                    .font(.system(size: 40, weight: .bold, design: .rounded))
                Text("Did you take fast-acting carbs?")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            HStack(spacing: 12) {
                ForEach(BackendAPI.RescueCarbPresets.options, id: \.self) { grams in
                    Button {
                        onConfirm(grams)
                    } label: {
                        VStack(spacing: 2) {
                            Text("\(Int(grams))").font(.title3.weight(.bold))
                            Text("g").font(.caption2)
                        }
                        .frame(maxWidth: .infinity, minHeight: 64)
                    }
                    .buttonStyle(.borderedProminent)
                }
            }

            HStack {
                TextField("Other amount", text: $customGrams)
                    .keyboardType(.decimalPad)
                    .textFieldStyle(.roundedBorder)
                Button("Log") {
                    if let grams = Double(customGrams), grams > 0, grams <= 100 {
                        onConfirm(grams)
                    }
                }
                .disabled(Double(customGrams).map { $0 <= 0 || $0 > 100 } ?? true)
            }

            Button("Not now", role: .cancel) { onDismiss() }
                .padding(.top, 4)
        }
        .padding(24)
        .presentationDetents([.medium])
    }
}
```

- [ ] **Step 3: Present it from ContentView**

In `ContentView.swift`, add to the existing chain of `.sheet` modifiers (near line 215-236):

```swift
            .sheet(item: $appState.openHypoEvent) { event in
                HypoPromptView(
                    event: event,
                    displayUnit: appState.preferredGlucoseUnit,
                    onConfirm: { grams in Task { await appState.confirmHypo(grams: grams) } },
                    onDismiss: { Task { await appState.dismissHypo() } }
                )
            }
```

`sheet(item:)` requires `BackendAPI.HypoEvent` to be `Identifiable`, which it is via its `id` property.

- [ ] **Step 4: Write the tests**

Read `glucose-monitor-iphone/GlucoseMonitorTests/TestHelpers.swift` first for the project's fixture conventions.

Create `glucose-monitor-iphone/GlucoseMonitorTests/HypoPromptTests.swift`:

```swift
import XCTest
@testable import GlucoseMonitor

final class HypoPromptTests: XCTestCase {

    /// The backend speaks mmol/L only. A mg/dL user must see 63, not 3.5.
    func testTriggerGlucoseConvertsForMgdlDisplay() {
        let mmol = 3.5
        let displayed = GlucoseUnit.fromMmol(mmol, displayUnit: "mg/dL")
        XCTAssertEqual(displayed, 63.06, accuracy: 0.1)
    }

    func testTriggerGlucoseStaysUnchangedForMmolDisplay() {
        let displayed = GlucoseUnit.fromMmol(3.5, displayUnit: "mmol/L")
        XCTAssertEqual(displayed, 3.5, accuracy: 0.001)
    }

    func testPresetsAreTenFifteenTwenty() {
        XCTAssertEqual(BackendAPI.RescueCarbPresets.options, [10, 15, 20])
    }

    func testHypoEventDecodesFromBackendPayload() throws {
        let json = """
        {"id":"abc","triggerGlucoseMmol":3.4,"state":"OPEN",
         "noteId":null,"detectedAt":"2026-08-20T09:15:00","resolvedAt":null}
        """.data(using: .utf8)!
        let event = try JSONDecoder().decode(BackendAPI.HypoEvent.self, from: json)
        XCTAssertEqual(event.id, "abc")
        XCTAssertEqual(event.triggerGlucoseMmol, 3.4, accuracy: 0.001)
        XCTAssertEqual(event.state, "OPEN")
        XCTAssertNil(event.noteId)
    }
}
```

- [ ] **Step 5: Run the iOS tests**

Run the test target the way this project already does (check for an existing scheme or script under `glucose-monitor-iphone`).
Expected: PASS, 4 tests.

- [ ] **Step 6: Commit**

```bash
git add glucose-monitor-iphone/GlucoseMonitor/HypoPromptView.swift \
        glucose-monitor-iphone/GlucoseMonitor/AppState.swift \
        glucose-monitor-iphone/GlucoseMonitor/ContentView.swift \
        glucose-monitor-iphone/GlucoseMonitorTests/HypoPromptTests.swift
git commit -m "feat(ios): hypo prompt with one-tap rescue-carb logging"
```

---

### Task 12: Documentation

**Files:**
- Modify: `glucose-monitor-be/backend-functional-schema.md`

- [ ] **Step 1: Update the schema document**

Four edits:

1. **§3 migrations table** — add a row: `` `V11__hypo_events` `` | `hypo_events`.
2. **§2 API surface**, in the "Personalisation & learning" table — add:
   `GET` `/api/hypo-events`; `POST` `/{id}/confirm` · `/{id}/dismiss` | Hypo prompts and fast-carb rescue logging.
3. **§11 Alerting** — replace the sentence "`GlucoseAnomalyDetector` runs on the CGM sync cadence (5 min, `app.observer.interval-ms`)" with a note that it reads `cgm_readings`, and add a row to the scenario table: `Hypo prompt` | `latest CGM < 3.9 mmol/L; opens a hypo_events row, expires at ≥ 4.5, suppressed 15 min after resolution`.
4. **§17 Known gaps** — delete nothing, but amend the "Push notifications are a stub" bullet to note that the hypo prompt is foreground-only as a result. Do **not** claim the observer is still dormant — Task 1 fixed that; instead remove any such claim if present.

- [ ] **Step 2: Commit**

```bash
git add backend-functional-schema.md
git commit -m "docs: record hypo events and the observer CGM fix in the schema"
```

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
|---|---|
| §1 Note type | Task 2 |
| §2 `hypo_events` table | Task 4 |
| §3 Rescue absorption profile | Task 2 (curve), Task 3 (wiring) |
| §4 Detector prerequisite + mg/dL promotion | Task 1 |
| §5 Hypo event lifecycle, hysteresis, suppression | Task 5 |
| §6 API + idempotent confirm + validation | Tasks 6, 7 |
| §7 Learning-loop isolation | Task 8 |
| §8 iOS | Tasks 10, 11 |
| Error handling table | Task 6 (409/404/400), Task 7 (feature-flag 404) |
| Testing section | Tasks 1–8, 11; full-suite gate in Task 9 |

**Placeholders:** Two tasks intentionally defer to an existing file rather than inlining code — Task 4 Step 4 (repository test annotations) and Task 7 Step 2 (MockMvc setup), because both depend on project-specific Spring test scaffolding that must be copied exactly rather than guessed. Both name the precise file to read and give the full test case bodies. Task 8 Step 1's second test body is likewise specified by intent plus the fixture source, since it depends on `IsfMealWindowProfileServiceTest`'s private helpers.

**Type consistency:** `HypoEvent.State` is used identically in the entity, repository, service, DTO (`.name()`) and controller (`@RequestParam State`). `HypoEventService`'s constructor is `(HypoEventRepository, NoteRepository, FeatureToggleConfig)` in Tasks 5 and 6 alike — Task 5 deliberately introduces the unused `noteRepository` field so the Task 6 test needs no constructor change. `RescueCarbProfile.ABSORPTION_MODE` is the single string `"RESCUE"` used by both Task 2 and Task 3. `GlucosePoint` is declared `public` in Task 1 so the test can name it.
