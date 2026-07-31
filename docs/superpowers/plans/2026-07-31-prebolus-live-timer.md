# Pre-bolus Live Timer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `POST /api/predict` use the pre-bolus interval the iOS app already measures, instead of double-counting the logged dose and recommending an inverted pause.

**Architecture:** Trust the logged history and suppress synthesis. The Hovorka ODE already models bolus-to-meal timing correctly from real timestamps, so the fix is largely subtractive — stop appending a dose that is already in the notes, and stop searching for a pause that has already been measured. A new `PreBolusResolver` decides which of two paths a request takes; a new `NoteToCarbsEntryMapper` gives the prediction path the macro hydration the legacy path has always had.

**Tech Stack:** Java 21, Spring Boot, Gradle, JUnit 5, Mockito, AssertJ, Lombok, Jackson.

## Global Constraints

- Java 21 toolchain. Build and test with `./gradlew test` from `glucose-monitor-be/`.
- TDD London School (mock-first): mock collaborators, assert on captured arguments.
- Keep files under 500 lines.
- Contract changes are **additive only**. No existing field changes name or type.
- Invariant: exactly one of `preBolusMinutes` and `observedPreBolusMinutes` is non-null in any response.
- Resolver failures degrade to "no context", never to an HTTP error.
- Dose mismatch tolerance: **0.05 U**. Explicit-timestamp match tolerance: **±1 minute**.
- Pre-bolus detection window: **2 hours**. Aliases: `pre-bolus`, `prebolus` (case-insensitive). Meal labels that do *not* stop the timer: `correction`, `pre-bolus`, `prebolus`.
- Elapsed time is computed from two `LocalDateTime` values in the server zone. `PredictRequest.clientTimezone` is **never** consulted for it.
- Never commit real glucose data.

---

## File Structure

**Create:**

| Path | Responsibility |
|---|---|
| `src/main/java/che/glucosemonitorbe/service/nutrition/NoteToCarbsEntryMapper.java` | Sole owner of `Note` → `CarbsEntry` mapping, including `nutrition_profile` hydration |
| `src/main/java/che/glucosemonitorbe/service/prebolus/PreBolusContext.java` | Immutable result record |
| `src/main/java/che/glucosemonitorbe/service/prebolus/PreBolusResolver.java` | Decides whether a pre-bolus is in flight. Pure function of its arguments |
| `src/test/java/che/glucosemonitorbe/service/nutrition/NoteToCarbsEntryMapperTest.java` | Hydration, malformed JSON, toggle off |
| `src/test/java/che/glucosemonitorbe/service/prebolus/PreBolusResolverTest.java` | Ported from `PreBolusTimerTests.swift`, plus explicit-field cases |

**Modify:**

| Path | Change |
|---|---|
| `src/main/java/che/glucosemonitorbe/service/GlucoseCalculationsService.java:526-564` | `convertNoteToCarbsEntry` delegates to the mapper |
| `src/test/java/che/glucosemonitorbe/service/GlucoseCalculationsServiceTest.java:48,57,268` | Constructor arity 8 → 9 |
| `src/main/java/che/glucosemonitorbe/dto/PredictRequest.java` | `+ insulinLoggedAt` |
| `src/main/java/che/glucosemonitorbe/dto/PredictResponse.java` | `+ observedPreBolusMinutes` |
| `src/main/java/che/glucosemonitorbe/service/GlucosePredictService.java` | Mapper use, context branch, advisory sign fix |
| `src/test/java/che/glucosemonitorbe/service/GlucosePredictServiceTest.java:80` | Constructor arity 4 → 6 |

---

### Task 1: Extract `NoteToCarbsEntryMapper`

Pure extraction. No behaviour changes anywhere — `GlucoseCalculationsServiceTest` is the characterisation net that proves it.

**Files:**
- Create: `src/main/java/che/glucosemonitorbe/service/nutrition/NoteToCarbsEntryMapper.java`
- Create: `src/test/java/che/glucosemonitorbe/service/nutrition/NoteToCarbsEntryMapperTest.java`
- Modify: `src/main/java/che/glucosemonitorbe/service/GlucoseCalculationsService.java:526-564`
- Modify: `src/test/java/che/glucosemonitorbe/service/GlucoseCalculationsServiceTest.java:48,57,268`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `NoteToCarbsEntryMapper.toCarbsEntry(Note) -> CarbsEntry`, a Spring `@Component` with constructor `(FeatureToggleConfig, ObjectMapper)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/che/glucosemonitorbe/service/nutrition/NoteToCarbsEntryMapperTest.java`:

```java
package che.glucosemonitorbe.service.nutrition;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.entity.Note;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NoteToCarbsEntryMapperTest {

    private FeatureToggleConfig    toggles;
    private NoteToCarbsEntryMapper sut;

    @BeforeEach
    void setUp() {
        toggles = new FeatureToggleConfig();
        toggles.setNutritionAwarePredictionEnabled(true);
        sut = new NoteToCarbsEntryMapper(toggles, new ObjectMapper());
    }

    private Note note(String nutritionProfile) {
        Note n = new Note();
        n.setId(UUID.randomUUID());
        n.setUserId(UUID.randomUUID());
        n.setTimestamp(LocalDateTime.of(2026, 7, 31, 12, 0));
        n.setCarbs(45.0);
        n.setInsulin(4.0);
        n.setMeal("lunch");
        n.setNutritionProfile(nutritionProfile);
        return n;
    }

    @Test
    @DisplayName("hydrates macros and glycemic fields from nutrition_profile")
    void hydratesMacros() {
        CarbsEntry entry = sut.toCarbsEntry(
                note("{\"estimatedGi\":42.0,\"glycemicLoad\":18.9,\"fiber\":6.0,"
                   + "\"protein\":22.0,\"fat\":15.0}"));

        assertThat(entry.getEstimatedGi()).isEqualTo(42.0);
        assertThat(entry.getGlycemicLoad()).isEqualTo(18.9);
        assertThat(entry.getFiber()).isEqualTo(6.0);
        assertThat(entry.getProtein()).isEqualTo(22.0);
        assertThat(entry.getFat()).isEqualTo(15.0);
        assertThat(entry.getCarbs()).isEqualTo(45.0);
        assertThat(entry.getMealType()).isEqualTo("lunch");
    }

    @Test
    @DisplayName("malformed nutrition_profile falls back to DEFAULT_DECAY without throwing")
    void malformedJson() {
        CarbsEntry entry = sut.toCarbsEntry(note("{not json"));

        assertThat(entry.getAbsorptionMode()).isEqualTo("DEFAULT_DECAY");
        assertThat(entry.getProtein()).isNull();
        assertThat(entry.getCarbs()).isEqualTo(45.0);
    }

    @Test
    @DisplayName("nutrition toggle off leaves macros unhydrated")
    void toggleOff() {
        toggles.setNutritionAwarePredictionEnabled(false);

        CarbsEntry entry = sut.toCarbsEntry(
                note("{\"estimatedGi\":42.0,\"protein\":22.0}"));

        assertThat(entry.getAbsorptionMode()).isEqualTo("DEFAULT_DECAY");
        assertThat(entry.getProtein()).isNull();
        assertThat(entry.getEstimatedGi()).isNull();
    }

    @Test
    @DisplayName("null nutrition_profile yields a bare entry")
    void nullProfile() {
        CarbsEntry entry = sut.toCarbsEntry(note(null));

        assertThat(entry.getAbsorptionMode()).isEqualTo("DEFAULT_DECAY");
        assertThat(entry.getProtein()).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*NoteToCarbsEntryMapperTest"`
Expected: FAIL — compilation error, `NoteToCarbsEntryMapper` does not exist.

- [ ] **Step 3: Create the mapper**

Create `src/main/java/che/glucosemonitorbe/service/nutrition/NoteToCarbsEntryMapper.java`. The body is lifted verbatim from `GlucoseCalculationsService.convertNoteToCarbsEntry` — do not "improve" it, since Step 5 relies on it being identical:

```java
package che.glucosemonitorbe.service.nutrition;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.entity.Note;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Maps a {@link Note} to a {@link CarbsEntry}, hydrating macronutrient and glycemic
 * fields from the note's {@code nutrition_profile} JSON.
 *
 * <p>Extracted from {@code GlucoseCalculationsService.convertNoteToCarbsEntry} so the
 * Hovorka prediction path gets the same hydration the legacy path has always had.
 * Behaviour is intentionally identical to the original.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NoteToCarbsEntryMapper {

    private final FeatureToggleConfig featureToggleConfig;
    private final ObjectMapper        objectMapper;

    public CarbsEntry toCarbsEntry(Note note) {
        CarbsEntry entry = CarbsEntry.builder()
            .id(note.getId())
            .timestamp(note.getTimestamp())
            .carbs(note.getCarbs())
            .insulin(note.getInsulin() != null ? note.getInsulin() : 0.0)
            .mealType(note.getMeal())
            .comment(note.getComment())
            .glucoseValue(note.getGlucoseLevel())
            .originalCarbs(note.getCarbs())
            .userId(note.getUserId())
            .build();
        entry.setAbsorptionMode(note.getAbsorptionMode() != null ? note.getAbsorptionMode() : "DEFAULT_DECAY");
        if (!featureToggleConfig.isNutritionAwarePredictionEnabled()) {
            entry.setAbsorptionMode("DEFAULT_DECAY");
            return entry;
        }
        if (note.getNutritionProfile() != null && !note.getNutritionProfile().isBlank()) {
            try {
                NutritionSnapshot snapshot = objectMapper.readValue(note.getNutritionProfile(), NutritionSnapshot.class);
                entry.setEstimatedGi(snapshot.getEstimatedGi());
                entry.setGlycemicLoad(snapshot.getGlycemicLoad());
                entry.setFiber(snapshot.getFiber());
                entry.setProtein(snapshot.getProtein());
                entry.setFat(snapshot.getFat());
                entry.setAbsorptionSpeedClass(snapshot.getAbsorptionSpeedClass());
                if (snapshot.getAbsorptionMode() != null) {
                    entry.setAbsorptionMode(snapshot.getAbsorptionMode());
                }
                entry.setBolusStrategy(snapshot.getBolusStrategy());
                entry.setSuggestedDurationHours(snapshot.getSuggestedDurationHours());
                entry.setPatternName(snapshot.getPatternName());
            } catch (Exception e) {
                log.warn("Failed to parse nutritionProfile for note {}: {}", note.getId(), e.getMessage());
                entry.setAbsorptionMode("DEFAULT_DECAY");
            }
        }
        return entry;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "*NoteToCarbsEntryMapperTest"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Delegate from `GlucoseCalculationsService`**

Add the field next to the existing `private final` block (after line 33-34):

```java
    private final NoteToCarbsEntryMapper noteToCarbsEntryMapper;
```

Add the import:

```java
import che.glucosemonitorbe.service.nutrition.NoteToCarbsEntryMapper;
```

Replace the whole body of `convertNoteToCarbsEntry` (lines 526-564) with:

```java
    private CarbsEntry convertNoteToCarbsEntry(Note note) {
        return noteToCarbsEntryMapper.toCarbsEntry(note);
    }
```

Leave the `objectMapper` and `featureToggleConfig` fields in place — both are still used elsewhere in the class.

- [ ] **Step 6: Fix the three constructor call sites in `GlucoseCalculationsServiceTest`**

The class uses `@RequiredArgsConstructor`, so the new field appends one positional argument. Three call sites break.

Add the mock field beside the others (near line 42):

```java
    @Mock private NoteToCarbsEntryMapper noteToCarbsEntryMapper;
```

Add the import:

```java
import che.glucosemonitorbe.service.nutrition.NoteToCarbsEntryMapper;
```

Line 48 — `setUp()`:

```java
        service = new GlucoseCalculationsService(
                cobService, insulinCalculatorService, noteRepository,
                userService, userInsulinPreferencesService, objectMapper,
                featureToggleConfig, userSettingsService, noteToCarbsEntryMapper);
```

Line 57 — the reflection test needs one more `null`:

```java
        GlucoseCalculationsService svc = new GlucoseCalculationsService(null, null, null, null, null, null, null, null, null);
```

Line 268 — the real-cobService instance:

```java
        GlucoseCalculationsService svc = new GlucoseCalculationsService(
                realCobService, insulinCalculatorService, noteRepository,
                userService, userInsulinPreferencesService, objectMapper,
                featureToggleConfig, userSettingsMock, noteToCarbsEntryMapper);
```

- [ ] **Step 7: Run the full suite to verify no behaviour changed**

Run: `./gradlew test`
Expected: PASS. `GlucoseCalculationsServiceTest` is the characterisation net — if any of its assertions move, the extraction was not verbatim.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/che/glucosemonitorbe/service/nutrition/NoteToCarbsEntryMapper.java \
        src/test/java/che/glucosemonitorbe/service/nutrition/NoteToCarbsEntryMapperTest.java \
        src/main/java/che/glucosemonitorbe/service/GlucoseCalculationsService.java \
        src/test/java/che/glucosemonitorbe/service/GlucoseCalculationsServiceTest.java
git commit -m "refactor: extract NoteToCarbsEntryMapper from GlucoseCalculationsService"
```

---

### Task 2: Hydrate historical meals in the prediction path

Fixes defect 3. Before this, every historical meal reached the ODE as pure carbohydrate at the default GI of 70.

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/service/GlucosePredictService.java:271-283`
- Modify: `src/test/java/che/glucosemonitorbe/service/GlucosePredictServiceTest.java:80`

**Interfaces:**
- Consumes: `NoteToCarbsEntryMapper.toCarbsEntry(Note) -> CarbsEntry` from Task 1.
- Produces: `GlucosePredictService` constructor gains a 5th parameter, `NoteToCarbsEntryMapper`, appended last.

- [ ] **Step 1: Write the failing test**

Add to `GlucosePredictServiceTest`. Note the existing test class already imports `ArgumentCaptor`, `CarbsEntry`, `Note` and AssertJ:

```java
    @Test
    @DisplayName("historical meals reach the ODE carrying protein, fat and GI")
    void historicalMealsCarryMacros() {
        LocalDateTime mealTime = LocalDateTime.now().minusMinutes(90);
        Note past = new Note();
        past.setId(UUID.randomUUID());
        past.setUserId(USER_ID);
        past.setTimestamp(mealTime);
        past.setCarbs(60.0);
        past.setInsulin(0.0);
        past.setMeal("lunch");

        CarbsEntry hydrated = CarbsEntry.builder()
                .id(past.getId())
                .timestamp(mealTime)
                .carbs(60.0)
                .mealType("lunch")
                .originalCarbs(60.0)
                .userId(USER_ID)
                .build();
        hydrated.setProtein(30.0);
        hydrated.setFat(20.0);
        hydrated.setEstimatedGi(35.0);

        when(noteRepository.findByUserIdAndTimestampBetween(eq(USER_ID), any(), any()))
                .thenReturn(List.of(past));
        when(noteToCarbsEntryMapper.toCarbsEntry(past)).thenReturn(hydrated);

        PredictRequest req = PredictRequest.builder()
                .currentGlucose(7.0)
                .carbs(0.0)
                .build();

        sut.predict(req, USERNAME);

        ArgumentCaptor<List<CarbsEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(hovorkaService, atLeastOnce()).buildPredictionPath(
                any(HovorkaParameters.class), anyDouble(), any(LocalDateTime.class),
                captor.capture(), anyList(), anyList(), eq(USER_ID), anyInt());

        CarbsEntry passed = captor.getValue().stream()
                .filter(e -> mealTime.equals(e.getTimestamp()))
                .findFirst()
                .orElseThrow();
        assertThat(passed.getProtein()).isEqualTo(30.0);
        assertThat(passed.getFat()).isEqualTo(20.0);
        assertThat(passed.getEstimatedGi()).isEqualTo(35.0);
    }
```

Also add the mock field and wire it into `setUp()`:

```java
    private NoteToCarbsEntryMapper noteToCarbsEntryMapper;
```

```java
        noteToCarbsEntryMapper = mock(NoteToCarbsEntryMapper.class);

        sut = new GlucosePredictService(hovorkaService, paramService, userService,
                                        noteRepository, noteToCarbsEntryMapper);
```

Add imports:

```java
import che.glucosemonitorbe.service.nutrition.NoteToCarbsEntryMapper;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*GlucosePredictServiceTest"`
Expected: FAIL — compilation error, the 5-arg constructor does not exist.

- [ ] **Step 3: Write the implementation**

In `GlucosePredictService`, add the field to the existing `private final` block (after line 70):

```java
    private final NoteToCarbsEntryMapper               noteToCarbsEntryMapper;
```

Add the import:

```java
import che.glucosemonitorbe.service.nutrition.NoteToCarbsEntryMapper;
```

Replace `toCarbsEntries` (lines 271-283) with:

```java
    private List<CarbsEntry> toCarbsEntries(List<Note> notes) {
        return notes.stream()
                .filter(n -> n.getCarbs() != null && n.getCarbs() > 0)
                .map(noteToCarbsEntryMapper::toCarbsEntry)
                .collect(java.util.stream.Collectors.toList());
    }
```

The `userId` parameter is dropped — the mapper reads `note.getUserId()`, which is the same
value, and this is a private method with a single caller. Update that caller at
`GlucosePredictService.java:87`:

```java
        List<CarbsEntry>  pastCarbs = toCarbsEntries(recentNotes);
```

Leave `toInsulinDoses(recentNotes, userId)` on the next line untouched — it still uses its
`userId` argument.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "*GlucosePredictServiceTest"`
Expected: PASS, including the pre-existing tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/che/glucosemonitorbe/service/GlucosePredictService.java \
        src/test/java/che/glucosemonitorbe/service/GlucosePredictServiceTest.java
git commit -m "fix: hydrate historical meals with macros and GI in /api/predict"
```

---

### Task 3: `PreBolusContext` and `PreBolusResolver`

Standalone component, not yet wired into anything. The test suite is a port of the iOS logic this must mirror.

**Files:**
- Create: `src/main/java/che/glucosemonitorbe/service/prebolus/PreBolusContext.java`
- Create: `src/main/java/che/glucosemonitorbe/service/prebolus/PreBolusResolver.java`
- Create: `src/test/java/che/glucosemonitorbe/service/prebolus/PreBolusResolverTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `PreBolusContext(LocalDateTime bolusTime, double units, int elapsedMinutes, PreBolusContext.Source source)` with `Source ∈ {EXPLICIT, DETECTED}`.
  - `PreBolusResolver.resolve(LocalDateTime insulinLoggedAt, List<Note> recentNotes, LocalDateTime now) -> Optional<PreBolusContext>`, a Spring `@Component` with a no-arg constructor.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/che/glucosemonitorbe/service/prebolus/PreBolusResolverTest.java`. Cases 1-7 are ported one-for-one from `glucose-monitor-iphone/GlucoseMonitorTests/PreBolusTimerTests.swift`:

```java
package che.glucosemonitorbe.service.prebolus;

import che.glucosemonitorbe.entity.Note;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PreBolusResolverTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 31, 12, 0);

    private PreBolusResolver sut;

    @BeforeEach
    void setUp() {
        sut = new PreBolusResolver();
    }

    private Note note(String meal, double insulin, double carbs, LocalDateTime at) {
        Note n = new Note();
        n.setId(UUID.randomUUID());
        n.setUserId(UUID.randomUUID());
        n.setTimestamp(at);
        n.setMeal(meal);
        n.setInsulin(insulin);
        n.setCarbs(carbs);
        n.setType(Note.TYPE_NORMAL);
        return n;
    }

    // -- Ported from PreBolusTimerTests.swift ---------------------------------

    @Test
    @DisplayName("no notes -> no context")
    void noNotes() {
        assertThat(sut.resolve(null, List.of(), NOW)).isEmpty();
    }

    @Test
    @DisplayName("pre-bolus note with zero insulin -> no context")
    void zeroInsulin() {
        List<Note> notes = List.of(note("pre-bolus", 0.0, 0.0, NOW.minusMinutes(5)));
        assertThat(sut.resolve(null, notes, NOW)).isEmpty();
    }

    @Test
    @DisplayName("pre-bolus note with null timestamp -> no context")
    void nullTimestamp() {
        assertThat(sut.resolve(null, List.of(note("pre-bolus", 3.0, 0.0, null)), NOW)).isEmpty();
    }

    @Test
    @DisplayName("meal logged after the pre-bolus -> no context")
    void mealAfterPreBolus() {
        List<Note> notes = List.of(
                note("pre-bolus", 4.0, 0.0, NOW.minusMinutes(30)),
                note("lunch",     0.0, 50.0, NOW.minusMinutes(10)));
        assertThat(sut.resolve(null, notes, NOW)).isEmpty();
    }

    @Test
    @DisplayName("a correction note after the pre-bolus does not stop the timer")
    void correctionDoesNotStop() {
        List<Note> notes = List.of(
                note("pre-bolus",  4.0, 0.0,  NOW.minusMinutes(30)),
                note("correction", 2.0, 10.0, NOW.minusMinutes(10)));
        assertThat(sut.resolve(null, notes, NOW)).isPresent();
    }

    @Test
    @DisplayName("plain pre-bolus note -> context with elapsed minutes")
    void plainPreBolus() {
        List<Note> notes = List.of(note("pre-bolus", 3.0, 0.0, NOW.minusMinutes(12)));

        PreBolusContext ctx = sut.resolve(null, notes, NOW).orElseThrow();

        assertThat(ctx.elapsedMinutes()).isEqualTo(12);
        assertThat(ctx.units()).isEqualTo(3.0);
        assertThat(ctx.source()).isEqualTo(PreBolusContext.Source.DETECTED);
    }

    @Test
    @DisplayName("prebolus alias and mixed case both resolve")
    void aliasAndCase() {
        assertThat(sut.resolve(null, List.of(note("prebolus",  3.0, 0.0, NOW.minusMinutes(5))), NOW)).isPresent();
        assertThat(sut.resolve(null, List.of(note("Pre-Bolus", 3.0, 0.0, NOW.minusMinutes(5))), NOW)).isPresent();
    }

    @Test
    @DisplayName("pre-bolus older than 2 hours has expired")
    void expiredAfterTwoHours() {
        List<Note> notes = List.of(note("pre-bolus", 3.0, 0.0, NOW.minusMinutes(121)));
        assertThat(sut.resolve(null, notes, NOW)).isEmpty();
    }

    // -- Backend-only cases ---------------------------------------------------

    @Test
    @DisplayName("long-acting note is never a pre-bolus")
    void longActingExcluded() {
        Note n = note("pre-bolus", 20.0, 0.0, NOW.minusMinutes(10));
        n.setType(Note.TYPE_LONG_ACTING);
        assertThat(sut.resolve(null, List.of(n), NOW)).isEmpty();
    }

    @Test
    @DisplayName("latest of several pre-bolus notes wins")
    void latestWins() {
        List<Note> notes = List.of(
                note("pre-bolus", 2.0, 0.0, NOW.minusMinutes(50)),
                note("pre-bolus", 5.0, 0.0, NOW.minusMinutes(8)));

        PreBolusContext ctx = sut.resolve(null, notes, NOW).orElseThrow();

        assertThat(ctx.units()).isEqualTo(5.0);
        assertThat(ctx.elapsedMinutes()).isEqualTo(8);
    }

    @Test
    @DisplayName("explicit insulinLoggedAt matches a bolus note without the pre-bolus label")
    void explicitMatch() {
        LocalDateTime at = NOW.minusMinutes(15);
        List<Note> notes = List.of(note("dinner", 6.0, 0.0, at));

        PreBolusContext ctx = sut.resolve(at, notes, NOW).orElseThrow();

        assertThat(ctx.source()).isEqualTo(PreBolusContext.Source.EXPLICIT);
        assertThat(ctx.units()).isEqualTo(6.0);
        assertThat(ctx.elapsedMinutes()).isEqualTo(15);
    }

    @Test
    @DisplayName("explicit insulinLoggedAt tolerates a 1-minute skew")
    void explicitTolerance() {
        List<Note> notes = List.of(note("dinner", 6.0, 0.0, NOW.minusMinutes(15)));
        assertThat(sut.resolve(NOW.minusMinutes(15).plusSeconds(40), notes, NOW)).isPresent();
    }

    @Test
    @DisplayName("explicit insulinLoggedAt in the future falls back to detection")
    void explicitFutureFallsBack() {
        List<Note> notes = List.of(note("pre-bolus", 3.0, 0.0, NOW.minusMinutes(10)));

        PreBolusContext ctx = sut.resolve(NOW.plusMinutes(5), notes, NOW).orElseThrow();

        assertThat(ctx.source()).isEqualTo(PreBolusContext.Source.DETECTED);
    }

    @Test
    @DisplayName("explicit insulinLoggedAt matching no note falls back to detection")
    void explicitUnmatchedFallsBack() {
        List<Note> notes = List.of(note("pre-bolus", 3.0, 0.0, NOW.minusMinutes(10)));

        PreBolusContext ctx = sut.resolve(NOW.minusMinutes(47), notes, NOW).orElseThrow();

        assertThat(ctx.source()).isEqualTo(PreBolusContext.Source.DETECTED);
    }

    @Test
    @DisplayName("elapsed minutes come from the two LocalDateTime arguments and nothing else")
    void elapsedIsZoneFree() {
        List<Note> notes = List.of(note("pre-bolus", 3.0, 0.0, NOW.minusMinutes(23)));
        assertThat(sut.resolve(null, notes, NOW).orElseThrow().elapsedMinutes()).isEqualTo(23);
    }
}
```

The last test documents the timezone convention from the spec. The guarantee is structural: `resolve` takes `LocalDateTime` and never touches a `ZoneId`, so no ambient default can shift the result. Any future change that introduces a zone conversion has to break this assertion first.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*PreBolusResolverTest"`
Expected: FAIL — compilation error, `PreBolusResolver` and `PreBolusContext` do not exist.

- [ ] **Step 3: Write the record**

Create `src/main/java/che/glucosemonitorbe/service/prebolus/PreBolusContext.java`:

```java
package che.glucosemonitorbe.service.prebolus;

import java.time.LocalDateTime;

/**
 * A pre-bolus that is in flight: insulin has been logged and the meal has not yet
 * been recorded.
 *
 * @param bolusTime      when the dose was administered
 * @param units          units from the logged note - the source of truth
 * @param elapsedMinutes minutes from {@code bolusTime} to the prediction anchor
 * @param source         how the context was established
 */
public record PreBolusContext(
        LocalDateTime bolusTime,
        double units,
        int elapsedMinutes,
        Source source) {

    public enum Source {
        /** Client supplied {@code insulinLoggedAt} and it matched a stored note. */
        EXPLICIT,
        /** Inferred from a {@code pre-bolus} note with no meal after it. */
        DETECTED
    }
}
```

- [ ] **Step 4: Write the resolver**

Create `src/main/java/che/glucosemonitorbe/service/prebolus/PreBolusResolver.java`:

```java
package che.glucosemonitorbe.service.prebolus;

import che.glucosemonitorbe.entity.Note;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Decides whether a pre-bolus is currently in flight for a prediction request.
 *
 * <p>A pure function of its arguments - no repository, no clock. Elapsed time is the
 * difference between two server-zone {@link LocalDateTime} values; no {@code ZoneId}
 * is ever consulted.</p>
 *
 * <p><b>Mirrors {@code PreBolusTimer.state()} in
 * {@code glucose-monitor-iphone/GlucoseMonitor/DashboardExtras.swift}.</b> The 2-hour
 * window, the meal aliases, and the set of labels that do not stop the timer are
 * duplicated from Swift. Any change there must be mirrored here; this class's tests
 * are the only guard against drift.</p>
 */
@Slf4j
@Component
public class PreBolusResolver {

    /** Meal labels marking a note as a pre-bolus. */
    static final Set<String> PRE_BOLUS_ALIASES  = Set.of("pre-bolus", "prebolus");
    /** Carb-bearing notes with these labels do NOT stop the timer. */
    static final Set<String> NON_STOPPING_MEALS = Set.of("correction", "pre-bolus", "prebolus");
    /** A pre-bolus older than this is treated as abandoned. */
    static final Duration MAX_AGE          = Duration.ofHours(2);
    /** Skew tolerated when matching an explicit insulinLoggedAt to a stored note. */
    static final Duration MATCH_TOLERANCE  = Duration.ofMinutes(1);

    public Optional<PreBolusContext> resolve(LocalDateTime insulinLoggedAt,
                                             List<Note> recentNotes,
                                             LocalDateTime now) {
        if (recentNotes == null || recentNotes.isEmpty()) {
            return Optional.empty();
        }
        if (insulinLoggedAt != null) {
            Optional<PreBolusContext> explicit = matchExplicit(insulinLoggedAt, recentNotes, now);
            if (explicit.isPresent()) {
                return explicit;
            }
            log.debug("insulinLoggedAt={} matched no bolus note; falling back to detection", insulinLoggedAt);
        }
        return detect(recentNotes, now);
    }

    private Optional<PreBolusContext> matchExplicit(LocalDateTime loggedAt, List<Note> notes, LocalDateTime now) {
        if (loggedAt.isAfter(now) || Duration.between(loggedAt, now).compareTo(MAX_AGE) > 0) {
            return Optional.empty();
        }
        return notes.stream()
                .filter(this::isBolusNote)
                .filter(n -> Math.abs(Duration.between(loggedAt, n.getTimestamp()).toSeconds())
                             <= MATCH_TOLERANCE.toSeconds())
                .max(Comparator.comparing(Note::getTimestamp))
                .map(n -> toContext(n, now, PreBolusContext.Source.EXPLICIT));
    }

    private Optional<PreBolusContext> detect(List<Note> notes, LocalDateTime now) {
        Optional<Note> latest = notes.stream()
                .filter(this::isBolusNote)
                .filter(n -> PRE_BOLUS_ALIASES.contains(lower(n.getMeal())))
                .filter(n -> !n.getTimestamp().isAfter(now))
                .filter(n -> Duration.between(n.getTimestamp(), now).compareTo(MAX_AGE) <= 0)
                .max(Comparator.comparing(Note::getTimestamp));

        if (latest.isEmpty()) {
            return Optional.empty();
        }
        LocalDateTime bolusTime = latest.get().getTimestamp();

        boolean mealAfter = notes.stream()
                .filter(n -> n.getTimestamp() != null)
                .filter(n -> n.getCarbs() != null && n.getCarbs() > 0)
                .filter(n -> !NON_STOPPING_MEALS.contains(lower(n.getMeal())))
                .anyMatch(n -> !n.getTimestamp().isBefore(bolusTime));

        return mealAfter
                ? Optional.empty()
                : Optional.of(toContext(latest.get(), now, PreBolusContext.Source.DETECTED));
    }

    private boolean isBolusNote(Note n) {
        return n.getTimestamp() != null
                && n.getInsulin() != null && n.getInsulin() > 0
                && !n.isLongActing();
    }

    private PreBolusContext toContext(Note n, LocalDateTime now, PreBolusContext.Source source) {
        return new PreBolusContext(
                n.getTimestamp(),
                n.getInsulin(),
                (int) Duration.between(n.getTimestamp(), now).toMinutes(),
                source);
    }

    private String lower(String s) {
        return s == null ? "" : s.toLowerCase();
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "*PreBolusResolverTest"`
Expected: PASS, 15 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/che/glucosemonitorbe/service/prebolus/ \
        src/test/java/che/glucosemonitorbe/service/prebolus/
git commit -m "feat: add PreBolusResolver mirroring the iOS pre-bolus timer"
```

---

### Task 4: Wire the live-timer path

Fixes defect 2. The advisory path is left byte-identical here — Task 5 changes it.

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/dto/PredictRequest.java`
- Modify: `src/main/java/che/glucosemonitorbe/dto/PredictResponse.java`
- Modify: `src/main/java/che/glucosemonitorbe/service/GlucosePredictService.java:79-183`
- Modify: `src/test/java/che/glucosemonitorbe/service/GlucosePredictServiceTest.java`

**Interfaces:**
- Consumes: `PreBolusResolver.resolve(...)` and `PreBolusContext` from Task 3.
- Produces: `GlucosePredictService` constructor gains a 6th parameter, `PreBolusResolver`, appended last. `PredictRequest.insulinLoggedAt` (`LocalDateTime`). `PredictResponse.observedPreBolusMinutes` (`Integer`).

- [ ] **Step 1: Write the failing test**

Add to `GlucosePredictServiceTest`:

```java
    @Test
    @DisplayName("live timer: the logged dose is not duplicated and no optimiser runs")
    void liveTimer_doesNotDuplicateDose() {
        LocalDateTime bolusAt = LocalDateTime.now().minusMinutes(12);
        Note preBolus = new Note();
        preBolus.setId(UUID.randomUUID());
        preBolus.setUserId(USER_ID);
        preBolus.setTimestamp(bolusAt);
        preBolus.setMeal("pre-bolus");
        preBolus.setInsulin(5.0);
        preBolus.setCarbs(0.0);
        preBolus.setType(Note.TYPE_NORMAL);

        when(noteRepository.findByUserIdAndTimestampBetween(eq(USER_ID), any(), any()))
                .thenReturn(List.of(preBolus));
        when(preBolusResolver.resolve(any(), anyList(), any(LocalDateTime.class)))
                .thenReturn(Optional.of(new PreBolusContext(
                        bolusAt, 5.0, 12, PreBolusContext.Source.DETECTED)));

        PredictRequest req = PredictRequest.builder()
                .currentGlucose(7.0)
                .carbs(50.0)
                .insulinDose(5.0)
                .build();

        PredictResponse res = sut.predict(req, USERNAME);

        assertThat(res.getObservedPreBolusMinutes()).isEqualTo(12);
        assertThat(res.getPreBolusMinutes()).isNull();

        ArgumentCaptor<List<InsulinDose>> doses = ArgumentCaptor.forClass(List.class);
        verify(hovorkaService, times(1)).buildPredictionPath(
                any(HovorkaParameters.class), anyDouble(), any(LocalDateTime.class),
                anyList(), doses.capture(), anyList(), eq(USER_ID), anyInt());

        double totalUnits = doses.getValue().stream().mapToDouble(InsulinDose::getUnits).sum();
        assertThat(totalUnits).isEqualTo(5.0);
    }

    @Test
    @DisplayName("no pre-bolus in flight: advisory path still recommends a pause")
    void noContext_stillRecommends() {
        when(preBolusResolver.resolve(any(), anyList(), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());

        PredictRequest req = PredictRequest.builder()
                .currentGlucose(7.0)
                .carbs(50.0)
                .insulinDose(5.0)
                .build();

        PredictResponse res = sut.predict(req, USERNAME);

        assertThat(res.getPreBolusMinutes()).isNotNull();
        assertThat(res.getObservedPreBolusMinutes()).isNull();
    }
```

Add the mock field, the default stub, and the constructor call in `setUp()`:

```java
    private PreBolusResolver preBolusResolver;
```

```java
        preBolusResolver = mock(PreBolusResolver.class);
        when(preBolusResolver.resolve(any(), anyList(), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());

        sut = new GlucosePredictService(hovorkaService, paramService, userService,
                                        noteRepository, noteToCarbsEntryMapper, preBolusResolver);
```

Add imports:

```java
import che.glucosemonitorbe.service.prebolus.PreBolusContext;
import che.glucosemonitorbe.service.prebolus.PreBolusResolver;
import java.util.Optional;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*GlucosePredictServiceTest"`
Expected: FAIL — compilation error, the 6-arg constructor and `getObservedPreBolusMinutes()` do not exist.

- [ ] **Step 3: Add the contract fields**

In `PredictRequest.java`, after the `clientTimezone` field:

```java
    /**
     * When the pre-bolus note was written, if the client has already logged the dose
     * and its timer is running. When present and it matches a stored bolus note, the
     * server treats the dose as already in history and does not add it again.
     */
    private LocalDateTime insulinLoggedAt;
```

Add the import `java.time.LocalDateTime`.

In `PredictResponse.java`, after `preBolusMinutes`:

```java
    /**
     * Measured minutes between the already-logged pre-bolus and this prediction anchor.
     * Non-null only when a pre-bolus was in flight, in which case
     * {@link #preBolusMinutes} is null - no recommendation is computed.
     */
    private Integer observedPreBolusMinutes;
```

Update the `preBolusMinutes` javadoc to state it is null on the live-timer path:

```java
    /**
     * Recommended pre-bolus pause [min] that minimises ∫(G(t) − 5.5)² dt over the horizon.
     * 0 when no insulin dose was provided. Null when a pre-bolus is already in flight -
     * see {@link #observedPreBolusMinutes}.
     */
```

- [ ] **Step 4: Wire the branch into `GlucosePredictService`**

Add the field after `noteToCarbsEntryMapper`:

```java
    private final PreBolusResolver                     preBolusResolver;
```

Add the constant beside the other private statics:

```java
    /** Units of disagreement tolerated between the request dose and the logged note. */
    private static final double DOSE_MATCH_TOLERANCE_U = 0.05;
```

Add imports:

```java
import che.glucosemonitorbe.service.prebolus.PreBolusContext;
import che.glucosemonitorbe.service.prebolus.PreBolusResolver;
import java.util.Optional;
```

Replace the block from `// -- 4. Optimise pre-bolus pause` through the `PredictResponse.builder()` return (lines 145-182) with:

```java
        // -- 4. Pre-bolus: measured if one is in flight, otherwise recommended -----
        double insulinDose = safe(req.getInsulinDose());
        Optional<PreBolusContext> preBolus =
                preBolusResolver.resolve(req.getInsulinLoggedAt(), recentNotes, now);

        Integer recommendedPause = null;
        Integer observedPause    = null;
        List<InsulinDose> finalDoses = new ArrayList<>(pastDoses);

        if (preBolus.isPresent()) {
            PreBolusContext ctx = preBolus.get();
            observedPause = ctx.elapsedMinutes();
            if (insulinDose > 0 && Math.abs(insulinDose - ctx.units()) > DOSE_MATCH_TOLERANCE_U) {
                log.warn("predict user={} request insulinDose={} disagrees with logged pre-bolus units={}; using the note",
                        userId, insulinDose, ctx.units());
            }
            // The dose is already in pastDoses. Adding it again would double-count it.
        } else {
            int bestPause = optimisePreBolus(
                    req.getCurrentGlucose(), now,
                    carbsWithMeal, pastDoses, longActingNotes,
                    userId, mealParams, insulinDose, horizon);
            recommendedPause = bestPause;
            if (insulinDose > 0) {
                finalDoses.add(InsulinDose.builder()
                        .id(UUID.randomUUID())
                        .userId(userId)
                        .units(insulinDose)
                        .type(InsulinDose.InsulinType.BOLUS)
                        .timestamp(now.plusMinutes(bestPause))
                        .build());
            }
        }

        // -- 5. Final simulation ----------------------------------------------
        List<PredictionPointDTO> curve = hovorkaService.buildPredictionPath(
                mealParams,
                req.getCurrentGlucose(), now,
                carbsWithMeal, finalDoses, longActingNotes,
                userId, horizon);

        double betaWeighted = MacroNutrientGastricModel.weightedBeta(carbsG, proteinG, fatG);
        String strategy     = MacroNutrientGastricModel.bolusStrategy(fatG, proteinG);

        log.debug("predict user={} tMaxG={} beta={} recommendedPause={} observedPause={} strategy={}",
                userId, tMaxGMod, betaWeighted, recommendedPause, observedPause, strategy);

        return PredictResponse.builder()
                .curve(curve)
                .preBolusMinutes(recommendedPause)
                .observedPreBolusMinutes(observedPause)
                .bolusStrategy(strategy)
                .tMaxGUsed(Math.round(tMaxGMod * 10.0) / 10.0)
                .betaWeighted(Math.round(betaWeighted * 100.0) / 100.0)
                .build();
```

Note the removed `{:.1f}` placeholders in the log line — SLF4J does not support format specifiers, so the original line printed them literally.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "*GlucosePredictServiceTest"`
Expected: PASS. The pre-existing test asserting `preBolusMinutes` is one of the candidates still passes, because the default resolver stub returns empty.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/che/glucosemonitorbe/dto/PredictRequest.java \
        src/main/java/che/glucosemonitorbe/dto/PredictResponse.java \
        src/main/java/che/glucosemonitorbe/service/GlucosePredictService.java \
        src/test/java/che/glucosemonitorbe/service/GlucosePredictServiceTest.java
git commit -m "fix: stop double-counting an already-logged pre-bolus dose in /api/predict"
```

---

### Task 5: Correct the advisory path

Fixes defect 1. Bolus at `now`, meal at `now + pause`, and each candidate scored over its own post-meal window.

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/service/GlucosePredictService.java:113-143, 185-246`
- Modify: `src/test/java/che/glucosemonitorbe/service/GlucosePredictServiceTest.java`

**Interfaces:**
- Consumes: everything from Tasks 2-4.
- Produces: private helper `withProspectiveMeal(List<CarbsEntry> history, PredictRequest req, UUID userId, LocalDateTime mealTime) -> List<CarbsEntry>`.

- [ ] **Step 1: Write the failing test**

Add to `GlucosePredictServiceTest`:

```java
    @Test
    @DisplayName("advisory path: the bolus never lands after the meal")
    void advisory_bolusPrecedesMeal() {
        when(preBolusResolver.resolve(any(), anyList(), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());

        PredictRequest req = PredictRequest.builder()
                .currentGlucose(7.0)
                .carbs(50.0)
                .insulinDose(5.0)
                .build();

        sut.predict(req, USERNAME);

        ArgumentCaptor<List<CarbsEntry>>  meals = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<InsulinDose>> doses = ArgumentCaptor.forClass(List.class);
        verify(hovorkaService, atLeastOnce()).buildPredictionPath(
                any(HovorkaParameters.class), anyDouble(), any(LocalDateTime.class),
                meals.capture(), doses.capture(), anyList(), eq(USER_ID), anyInt());

        for (int i = 0; i < doses.getAllValues().size(); i++) {
            LocalDateTime bolusAt = doses.getAllValues().get(i).stream()
                    .map(InsulinDose::getTimestamp)
                    .max(LocalDateTime::compareTo).orElseThrow();
            LocalDateTime mealAt = meals.getAllValues().get(i).stream()
                    .filter(e -> "predict".equals(e.getMealType()))
                    .map(CarbsEntry::getTimestamp)
                    .findFirst().orElseThrow();
            assertThat(bolusAt)
                    .as("candidate %d: bolus must not follow the meal", i)
                    .isBeforeOrEqualTo(mealAt);
        }
    }

    @Test
    @DisplayName("advisory path: the PGN entry is anchored to the meal, not to now")
    void advisory_pgnFollowsMeal() {
        when(preBolusResolver.resolve(any(), anyList(), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());

        PredictRequest req = PredictRequest.builder()
                .currentGlucose(7.0)
                .carbs(50.0)
                .protein(40.0)
                .insulinDose(5.0)
                .fpuOnsetMin(120)
                .build();

        sut.predict(req, USERNAME);

        ArgumentCaptor<List<CarbsEntry>> meals = ArgumentCaptor.forClass(List.class);
        verify(hovorkaService, atLeastOnce()).buildPredictionPath(
                any(HovorkaParameters.class), anyDouble(), any(LocalDateTime.class),
                meals.capture(), anyList(), anyList(), eq(USER_ID), anyInt());

        for (List<CarbsEntry> entries : meals.getAllValues()) {
            LocalDateTime mealAt = entries.stream()
                    .filter(e -> "predict".equals(e.getMealType()))
                    .map(CarbsEntry::getTimestamp).findFirst().orElseThrow();
            LocalDateTime pgnAt = entries.stream()
                    .filter(e -> "fpu-equiv".equals(e.getMealType()))
                    .map(CarbsEntry::getTimestamp).findFirst().orElseThrow();
            assertThat(Duration.between(mealAt, pgnAt).toMinutes()).isEqualTo(120);
        }
    }

    @Test
    @DisplayName("advisory path: each candidate simulates horizon + its own pause")
    void advisory_horizonExtendedPerCandidate() {
        when(preBolusResolver.resolve(any(), anyList(), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());

        PredictRequest req = PredictRequest.builder()
                .currentGlucose(7.0)
                .carbs(50.0)
                .insulinDose(5.0)
                .horizonMinutes(300)
                .build();

        sut.predict(req, USERNAME);

        ArgumentCaptor<Integer> horizons = ArgumentCaptor.forClass(Integer.class);
        verify(hovorkaService, atLeastOnce()).buildPredictionPath(
                any(HovorkaParameters.class), anyDouble(), any(LocalDateTime.class),
                anyList(), anyList(), anyList(), eq(USER_ID), horizons.capture());

        assertThat(horizons.getAllValues()).contains(300, 305, 310, 315, 320, 325, 330);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*GlucosePredictServiceTest"`
Expected: FAIL — `advisory_bolusPrecedesMeal` fails because the bolus is currently placed at `now + pause` while the meal sits at `now`.

- [ ] **Step 3: Extract the meal-building helper**

Replace steps 3 and 3b in `predict()` (lines 113-143) with a call to a new helper. Delete the inline meal and PGN construction and put this in its place:

```java
        // -- 3. Prospective meal + protein-gluconeogenesis tail, anchored to the meal --
        List<CarbsEntry> carbsWithMeal = withProspectiveMeal(pastCarbs, req, userId, now);
```

Then add the helper alongside the other private methods:

```java
    /**
     * Returns {@code history} plus the prospective meal at {@code mealTime} and, when the
     * protein load warrants it, a slow-carb gluconeogenesis entry at
     * {@code mealTime + onset}.
     *
     * <p>Both entries are anchored to the meal rather than to "now", so a candidate
     * pre-bolus pause shifts them together.</p>
     */
    private List<CarbsEntry> withProspectiveMeal(List<CarbsEntry> history,
                                                 PredictRequest req,
                                                 UUID userId,
                                                 LocalDateTime mealTime) {
        double carbsG   = safe(req.getCarbs());
        double proteinG = safe(req.getProtein());
        double fatG     = safe(req.getFat());
        double fiberG   = safe(req.getFiber());

        List<CarbsEntry> entries = new ArrayList<>(history);

        if (carbsG > 0) {
            entries.add(CarbsEntry.builder()
                    .id(UUID.randomUUID())
                    .timestamp(mealTime)
                    .carbs(carbsG).protein(proteinG).fat(fatG).fiber(fiberG)
                    .mealType("predict").userId(userId)
                    .build());
        }

        // Protein converts to glucose via gluconeogenesis (~50 % glucogenic, 2-4 h onset).
        // Fat's effect on gastric emptying is already modelled by MacroNutrientGastricModel
        // via BETA_FAT = 2.20 in computeTMaxG(); adding fat kcal here caused +1.1 mmol/L
        // meal-tail overshoot in the backtest (Variant B).
        double glucFrac = req.getGluconeogenicFraction() != null
                ? req.getGluconeogenicFraction() : 0.50;
        int    pgnOnset = req.getFpuOnsetMin() != null
                ? req.getFpuOnsetMin() : PGN_ONSET_MIN;

        double pgnEquivCarbs = proteinG * glucFrac * PGN_CARB_FACTOR;
        if (pgnEquivCarbs >= FPU_MIN_EQUIV_G) {
            entries.add(CarbsEntry.builder()
                    .id(UUID.randomUUID())
                    .timestamp(mealTime.plusMinutes(pgnOnset))
                    .carbs(pgnEquivCarbs)
                    .mealType("fpu-equiv")
                    .userId(userId)
                    .build());
        }
        return entries;
    }
```

- [ ] **Step 4: Rewrite `optimisePreBolus`**

Replace the whole method (lines 194-246) with:

```java
    private int optimisePreBolus(
            double currentGlucose,
            LocalDateTime now,
            List<CarbsEntry> history,
            List<InsulinDose> baseDoses,
            List<Note> longActingNotes,
            UUID userId,
            HovorkaParameters params,
            PredictRequest req,
            double insulinDose,
            int horizon) {

        if (insulinDose <= 0) return 0;

        int    bestPause = 0;
        double bestCost  = Double.MAX_VALUE;

        for (int pause : PREBOLUS_CANDIDATES) {
            LocalDateTime mealTime = now.plusMinutes(pause);

            // Bolus now, meal after `pause` minutes - that is what a pre-bolus is.
            List<InsulinDose> doses = new ArrayList<>(baseDoses);
            doses.add(InsulinDose.builder()
                    .id(UUID.randomUUID())
                    .userId(userId)
                    .units(insulinDose)
                    .type(InsulinDose.InsulinType.BOLUS)
                    .timestamp(now)
                    .build());

            List<CarbsEntry> entries = withProspectiveMeal(history, req, userId, mealTime);

            // Extend the simulation by `pause` so every candidate is scored over an equal
            // post-meal window. With a fixed horizon a 30-min pause would see 30 fewer
            // minutes of post-meal curve than a 0-min pause and win on arithmetic alone.
            List<PredictionPointDTO> sim = hovorkaService.buildPredictionPath(
                    params, currentGlucose, now,
                    entries, doses, longActingNotes, userId, horizon + pause);

            double cost = sim.stream()
                    .filter(pt -> pt.getTimestamp() != null && !pt.getTimestamp().isBefore(mealTime))
                    .mapToDouble(pt -> {
                        double g   = pt.getPredictedGlucose() != null ? pt.getPredictedGlucose() : currentGlucose;
                        double err = g - TARGET_GLUCOSE;
                        double base = err * err;
                        // Asymmetric, clinically-weighted penalty: an aggressive pre-bolus that
                        // drives a predicted hypo is far more dangerous than mild residual
                        // hyperglycaemia, so a symmetric integral could happily recommend a
                        // pause that bottoms out at 3.0.
                        if (g < HYPO_THRESHOLD) {
                            double below = HYPO_THRESHOLD - g;
                            base += HYPO_PENALTY_WEIGHT * below * below;
                        } else if (g < TARGET_GLUCOSE) {
                            base *= LOW_SIDE_WEIGHT;
                        }
                        return base;
                    }).sum();

            if (cost < bestCost) {
                bestCost  = cost;
                bestPause = pause;
            }
        }
        return bestPause;
    }
```

Update the single call site in `predict()` to pass `req` and drop the pre-built meal list:

```java
            int bestPause = optimisePreBolus(
                    req.getCurrentGlucose(), now,
                    pastCarbs, pastDoses, longActingNotes,
                    userId, mealParams, req, insulinDose, horizon);
```

The final simulation on the advisory path must use the winning meal time, so replace the `carbsWithMeal` it passes:

```java
            recommendedPause = bestPause;
            carbsWithMeal    = withProspectiveMeal(pastCarbs, req, userId, now.plusMinutes(bestPause));
```

This requires `carbsWithMeal` to be declared non-final before the branch:

```java
        List<CarbsEntry> carbsWithMeal = withProspectiveMeal(pastCarbs, req, userId, now);
```

stays as the live-timer default, and the advisory branch reassigns it.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "*GlucosePredictServiceTest"`
Expected: PASS, including the three new advisory tests and all pre-existing ones.

- [ ] **Step 6: Run the full suite**

Run: `./gradlew test`
Expected: PASS. `horizon + pause` can reach 510 minutes, above the 480-minute request clamp; `buildPredictionPath` does not clamp `pathMinutes` and its emission schedule handles any length, so this is safe.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/che/glucosemonitorbe/service/GlucosePredictService.java \
        src/test/java/che/glucosemonitorbe/service/GlucosePredictServiceTest.java
git commit -m "fix: pre-bolus advisory places the bolus before the meal and scores per-meal windows"
```

---

## Self-Review

**Spec coverage.** Every section of the design maps to a task: `PreBolusContext` and `PreBolusResolver` → Task 3; `NoteToCarbsEntryMapper` → Task 1; contract changes → Task 4; live-timer data flow → Task 4; advisory data flow including meal-anchored scoring and PGN anchoring → Task 5; edge cases → Task 3 tests; timezone convention → Task 3 Step 1 final test plus the resolver javadoc; testing section → distributed across all five tasks. No spec requirement is unassigned.

**Placeholder scan.** No TBDs. Every code step contains complete, compilable code. No step says "handle edge cases" or "add validation" without showing the code.

**Type consistency.** `toCarbsEntry(Note)` is named identically in Tasks 1, 2 and their tests. `resolve(LocalDateTime, List<Note>, LocalDateTime)` is identical in Tasks 3 and 4. `PreBolusContext.Source.DETECTED` / `.EXPLICIT` are used consistently. Constructor arity is tracked explicitly: `GlucoseCalculationsService` 8 → 9 (Task 1); `GlucosePredictService` 4 → 5 (Task 2) → 6 (Task 4). `withProspectiveMeal` has one signature, introduced in Task 5 and used twice within it.

**Known ordering constraint.** Tasks must run in order. Task 2 depends on Task 1's mapper, Task 4 on Task 3's resolver, and Task 5 on Task 4's branch structure.
