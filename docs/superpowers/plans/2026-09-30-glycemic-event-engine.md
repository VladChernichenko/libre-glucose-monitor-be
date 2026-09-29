# Glycemic Event Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Evolve the unlogged-event detector into a glycemic event engine. It detects all unexplained glucose moves and puts each onset at its inflection point. It classifies them into ten categories with an IOB/COB/slope rule matrix, resolves tail-dependent patterns (resistance vs. absorption delay vs. lipodystrophy; unlogged insulin vs. cardio; anaerobic spike-then-drop), learns from the user's answers, and shows the events on the iOS forecast chart with a one-tap prompt.

**Architecture:**
- **Pure core.** A new package `che.glucosemonitorbe.glycemic` holds pure, Spring-free units:
  - `ResidualScanner` and `OnsetLocator` (detection)
  - `FeatureExtractor` and `EventClassifier` (description and rules)
  - `TailResolver` (resolution after the fact)
  - `EventLabelLearner` (per-user learning)
  - `GlycemicEventDetector`, the in-memory pipeline that composes them
- **Thin Spring shell.** `GlycemicEventScanService` loads data, runs the detector, and persists. `GlycemicTailService` claims and resolves provisional events. `GlycemicEventService` serves the user API.
- **iOS.** It adds a chart layer, an inline prompt card, a detail sheet, and a history view, all driven by server-provided labels and options.

**Tech Stack:** Spring Boot 3.5 / Java 21 / Hibernate 6 (JSONB via `@JdbcTypeCode(SqlTypes.JSON)`), Flyway, JUnit 5 + Mockito + AssertJ, Testcontainers (postgres:16-alpine); SwiftUI + Swift Charts (iOS 17 overlay APIs), XCTest.

**Spec:** `glucose-monitor-be/docs/superpowers/specs/2026-09-30-glycemic-event-engine-design.md`. Read it before starting any task. Section references below (§n) point into it.

**Repos:** Tasks 1–16 run in `glucose-monitor-be/`, and Tasks 17–21 run in `glucose-monitor-iphone/`. They are separate git repos, and `glucose-monitor-project/` itself is not one. Before Task 1, create branch `feat/glycemic-event-engine` in the backend repo from the branch holding this plan. Before Task 17, create a branch of the same name in the iOS repo from `main`.

## Global Constraints

- **Java toolchain:** `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home` before every Gradle command. The machine default is Java 25, which fails with "Unsupported class file major version 69".
- **Docker:** it must be running for the `*E2ETest` classes (Testcontainers).
- **Migrations** (repo rule, `glucose-monitor-be/CLAUDE.md`): never `ALTER TABLE` in a new migration. Edit the file that owns the table in place, and a new `V{n}` file is only for a brand-new table (§1.1).
- **Size:** every Java and Swift source file stays under 500 lines.
- **Dosing:** no code path writes `user_settings` dosing fields or issues dosing advice. User-facing text never recommends a dose.
- **Timestamps:** event timestamps are the user's local wall time (`LocalDateTime`, same as notes). Convert CGM epochs with `UserZones.toWallTime(epochMs, zone)` / `UserZones.toEpochMs(ldt, zone)`, and resolve the zone with `UserZones.resolve(settings.getTimezone(), settings.getUtcOffsetMinutes())`.
- **DTO time format:** every DTO `LocalDateTime` is annotated `@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")`.
- **Config:** all tunables live in `GlycemicEventProperties` (`app.glycemic-events.*`) with the defaults in §8.
- **Feature flag:** `app.features.glycemic-event-detection-enabled` (env `APP_FEATURES_GLYCEMIC_EVENT_DETECTION_ENABLED`, default `true`). With the flag off, scans are no-ops and the endpoints return 404.
- **Commits:** end every commit message with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Pre-existing iOS failures:** `HypoPromptTests` (3 tests) fail on clean `main`, and `BackendAPIBugTests.testJWTStorageUsesKeychain` fails under `CODE_SIGNING_ALLOWED=NO`. Neither counts as a regression.

## Review Focus

1. **Legacy or partial rows in the learner's training set.** Events with `features = null` (or ones written by an older `v`) must be skipped by the learner, never cause an NPE. Pinned in Task 12.
2. **A rescan over a window the user already answered.** A detection overlapping a `CONFIRMED`/`DISMISSED` event in the same direction must not create a fresh `OPEN` duplicate marker. Pinned in Task 8.
3. **A sensor gap during a provisional event's tail.** If fewer than half the expected CGM points exist over `[onset, resolves_at]`, no insulin-effect ratio is computed, and the event resolves `STRESS` with history reason `tail:insufficient-data`. It never resolves `LIPODYSTROPHY` on missing data. Pinned in Task 10.
4. **A user who isn't on UTC.** `/prompt` age and range queries use the user's clock, so an event 5 h old for a UTC+4 user is prompted, while a 7 h one is not. Pinned in Task 14.
5. **Events that straddle the chart edge on iOS.** An event whose onset is before the chart's x-domain, or whose `windowEnd` is after "now", is clipped to the domain, and its pill is placed at the clipped start. It is never dropped and never crashes. Pinned in Task 19.

---

## Phase 1 — Rename and split (behaviour-preserving)

### Task 1: Rename unlogged events to glycemic events

**Files:**
- Rename + rewrite: `src/main/resources/db/migration/V9__unlogged_event_flags.sql` → `V9__glycemic_events.sql`
- Rename: `entity/UnloggedEventFlag.java` → `entity/GlycemicEvent.java`
- Rename: `repository/UnloggedEventFlagRepository.java` → `repository/GlycemicEventRepository.java`
- Rename: `dto/UnloggedEventFlagDTO.java` → `dto/GlycemicEventDTO.java`
- Rename: `dto/ConfirmUnloggedEventRequest.java` → `dto/ConfirmGlycemicEventRequest.java`
- Rename: `controller/UnloggedEventController.java` → `controller/GlycemicEventController.java`
- Rename: `scheduler/UnloggedEventScanScheduler.java` → `scheduler/GlycemicEventScanScheduler.java`
- Rename: `service/UnloggedEventDetectionService.java` → `service/GlycemicEventScanService.java`
- Modify: `config/FeatureToggleConfig.java`, `service/FeatureToggleService.java`, `service/DigitalTwinCalibrationService.java`, `domain/RescueCarbProfile.java` (javadoc), `src/main/resources/application.yml`
- Rename tests: `integration/UnloggedEventE2ETest.java` → `integration/GlycemicEventE2ETest.java`, `integration/UnloggedEventFeatureFlagOffE2ETest.java` → `integration/GlycemicEventFeatureFlagOffE2ETest.java`, `service/UnloggedEventDetectionServiceTest.java` → `service/GlycemicEventScanServiceTest.java`
- Modify test: `service/RescueMarkerPropagationTest.java`

All paths below are relative to `glucose-monitor-be/`, and Java paths are under `src/main/java/che/glucosemonitorbe/` or `src/test/java/che/glucosemonitorbe/`.

**Interfaces:**
- Produces: entity `GlycemicEvent` with nested enums `Category`, `Direction`, `State` (same values as today); `GlycemicEventRepository` with the three existing finders; `GlycemicEventScanService` with `ScanSummary scanAllRealUsers()`, `Optional<GlycemicEvent> scanUser(UUID)`, `list/confirm/dismiss` (moved out in Task 2), `static CarbsEntry toCarbsEntry(Note)`; REST base path `/api/glycemic-events`; `FeatureToggleConfig.isGlycemicEventDetectionEnabled()`; config prefix `app.glycemic-events`.

- [ ] **Step 1: Record the baseline**

Run: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home && ./gradlew test > /tmp/gm-baseline.log 2>&1; echo exit=$?; grep -E "tests completed|FAILED" /tmp/gm-baseline.log | head -30`
Expected: note every test that already fails. Those are pre-existing and must not be attributed to this work. Save the list; later "full suite" steps compare against it.

- [ ] **Step 2: Move the files**

```bash
M=src/main/java/che/glucosemonitorbe; T=src/test/java/che/glucosemonitorbe
git mv src/main/resources/db/migration/V9__unlogged_event_flags.sql src/main/resources/db/migration/V9__glycemic_events.sql
git mv $M/entity/UnloggedEventFlag.java $M/entity/GlycemicEvent.java
git mv $M/repository/UnloggedEventFlagRepository.java $M/repository/GlycemicEventRepository.java
git mv $M/dto/UnloggedEventFlagDTO.java $M/dto/GlycemicEventDTO.java
git mv $M/dto/ConfirmUnloggedEventRequest.java $M/dto/ConfirmGlycemicEventRequest.java
git mv $M/controller/UnloggedEventController.java $M/controller/GlycemicEventController.java
git mv $M/scheduler/UnloggedEventScanScheduler.java $M/scheduler/GlycemicEventScanScheduler.java
git mv $M/service/UnloggedEventDetectionService.java $M/service/GlycemicEventScanService.java
git mv $T/integration/UnloggedEventE2ETest.java $T/integration/GlycemicEventE2ETest.java
git mv $T/integration/UnloggedEventFeatureFlagOffE2ETest.java $T/integration/GlycemicEventFeatureFlagOffE2ETest.java
git mv $T/service/UnloggedEventDetectionServiceTest.java $T/service/GlycemicEventScanServiceTest.java
```

- [ ] **Step 3: Rename identifiers (longest names first so shorter patterns don't clobber them)**

```bash
grep -rlE "Unlogged|unlogged" src/main/java src/test/java src/main/resources/application.yml | xargs sed -i '' \
  -e 's/UnloggedEventFlagRepository/GlycemicEventRepository/g' \
  -e 's/unloggedEventFlagRepository/glycemicEventRepository/g' \
  -e 's/UnloggedEventFlagDTO/GlycemicEventDTO/g' \
  -e 's/ConfirmUnloggedEventRequest/ConfirmGlycemicEventRequest/g' \
  -e 's/UnloggedEventDetectionServiceTest/GlycemicEventScanServiceTest/g' \
  -e 's/UnloggedEventDetectionService/GlycemicEventScanService/g' \
  -e 's/UnloggedEventScanScheduler/GlycemicEventScanScheduler/g' \
  -e 's/UnloggedEventController/GlycemicEventController/g' \
  -e 's/UnloggedEventFeatureFlagOffE2ETest/GlycemicEventFeatureFlagOffE2ETest/g' \
  -e 's/UnloggedEventE2ETest/GlycemicEventE2ETest/g' \
  -e 's/UnloggedEventFlag/GlycemicEvent/g' \
  -e 's#/api/unlogged-events#/api/glycemic-events#g' \
  -e 's/unlogged-event-detection-enabled/glycemic-event-detection-enabled/g' \
  -e 's/UnloggedEventDetectionEnabled/GlycemicEventDetectionEnabled/g' \
  -e 's/unloggedEventDetectionEnabled/glycemicEventDetectionEnabled/g' \
  -e 's/APP_FEATURES_UNLOGGED_EVENT_DETECTION_ENABLED/APP_FEATURES_GLYCEMIC_EVENT_DETECTION_ENABLED/g' \
  -e 's/app\.unlogged-events\./app.glycemic-events./g' \
  -e 's/\${app\.unlogged-events/${app.glycemic-events/g'
```

Then set the JPA table name: in `entity/GlycemicEvent.java` change `@Table(name = "unlogged_event_flags")` to `@Table(name = "glycemic_events")`. In `controller/GlycemicEventController.java` change the `@Tag(name = "Unlogged events", …)` to `@Tag(name = "Glycemic events", description = "Detected unexplained glucose moves, for user confirmation")`.

- [ ] **Step 4: Rewrite V9 for the renamed table**

Replace the whole content of `src/main/resources/db/migration/V9__glycemic_events.sql` with:

```sql
-- Glycemic events: windows where glucose moved in a way the logged inputs (COB/IOB) do not explain.
-- Originally created as "unlogged_event_flags"; edited in place per the repo's migration rule
-- (existing databases need the one-time reset described in the glycemic-event-engine spec, section 1.1).

CREATE TABLE IF NOT EXISTS glycemic_events (
    id                 UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID             NOT NULL,
    category           VARCHAR(28)      NOT NULL,
    direction          VARCHAR(8)       NOT NULL,
    window_start       TIMESTAMPTZ      NOT NULL,
    window_end         TIMESTAMPTZ      NOT NULL,
    mean_residual_mmol DOUBLE PRECISION NOT NULL,
    sigma_multiple     DOUBLE PRECISION,
    state              VARCHAR(12)      NOT NULL DEFAULT 'OPEN',
    detected_at        TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    resolved_at        TIMESTAMPTZ,
    CONSTRAINT fk_glycemic_events_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_glycemic_category CHECK (category IN
        ('UNLOGGED_FOOD','UNDER_ESTIMATED_FOOD','UNLOGGED_INSULIN','UNDER_ESTIMATED_INSULIN')),
    CONSTRAINT chk_glycemic_direction CHECK (direction IN ('RISE','FALL')),
    CONSTRAINT chk_glycemic_state     CHECK (state IN ('OPEN','CONFIRMED','DISMISSED'))
);

CREATE INDEX IF NOT EXISTS idx_glycemic_events_user       ON glycemic_events(user_id);
CREATE INDEX IF NOT EXISTS idx_glycemic_events_state      ON glycemic_events(state);
CREATE INDEX IF NOT EXISTS idx_glycemic_events_user_state ON glycemic_events(user_id, state);

COMMENT ON TABLE glycemic_events IS
    'Windows with a sustained unexplained residual - probable unlogged/under-estimated inputs or physiology.';
```

Leave `V11__hypo_events.sql` untouched. Its comment mentions `unlogged_event_flags`, but editing it would change V11's checksum for no functional gain.

- [ ] **Step 5: Verify nothing still uses the old identifiers**

Run: `grep -rnE "UnloggedEvent|unlogged_event|unlogged-event|unloggedEvent" src/ | grep -v "V11__hypo_events.sql"`
Expected: no output. (V11's comment is deliberately left alone. Prose like "unlogged food" doesn't match the pattern.)

- [ ] **Step 6: Run the renamed tests, then the full suite**

Run: `./gradlew test --tests '*GlycemicEvent*' --tests '*RescueMarkerPropagationTest' --tests '*DigitalTwinCalibration*'`
Expected: PASS. That's all 17 `GlycemicEventE2ETest` cases plus the flag-off and unit tests.
Run: `./gradlew test > /tmp/gm-t1.log 2>&1; grep -E "FAILED" /tmp/gm-t1.log`
Expected: the only failures are the Step 1 baseline list.

Local dev DB (native pg14) note: don't run this yourself. It goes in the task report for the user: `DROP TABLE IF EXISTS unlogged_event_flags CASCADE; DELETE FROM flyway_schema_history WHERE version = '9';`, then boot once with `SPRING_FLYWAY_OUT_OF_ORDER=true`.

- [ ] **Step 7: Commit**

```bash
git add -A src/
git commit -m "refactor: rename unlogged events to glycemic events

Table, entity, repository, DTOs, controller path (/api/glycemic-events),
scheduler, feature flag and config prefix. Behaviour unchanged.
V9 is edited in place per the migration rule; existing DBs need the
one-time reset from the spec (section 1.1).

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Split scanning from user operations

**Files:**
- Create: `service/GlycemicEventService.java`
- Modify: `service/GlycemicEventScanService.java` (remove `list`, `confirm`, `dismiss`, `requireOpenFlag`, `validateAmount`, the two backfill `@Value` fields)
- Modify: `controller/GlycemicEventController.java` (inject `GlycemicEventService`)
- Create test: `src/test/java/che/glucosemonitorbe/service/GlycemicEventServiceTest.java`

**Interfaces:**
- Consumes: `GlycemicEventRepository`, `NoteRepository` (Task 1).
- Produces: `GlycemicEventService` with `List<GlycemicEventDTO> list(UUID userId, State stateFilter)`, `GlycemicEventDTO confirm(UUID userId, UUID eventId, Double carbs, Double insulin)`, `GlycemicEventDTO dismiss(UUID userId, UUID eventId)`. `GlycemicEventScanService` keeps only scanning.

- [ ] **Step 1: Write the failing test (mock-first)**

```java
package che.glucosemonitorbe.service;

import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.Category;
import che.glucosemonitorbe.entity.GlycemicEvent.Direction;
import che.glucosemonitorbe.entity.GlycemicEvent.State;
import che.glucosemonitorbe.repository.GlycemicEventRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GlycemicEventServiceTest {

    private final GlycemicEventRepository events = mock(GlycemicEventRepository.class);
    private final NoteRepository notes = mock(NoteRepository.class);
    private GlycemicEventService service;
    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new GlycemicEventService(events, notes);
        ReflectionTestUtils.setField(service, "maxBackfillCarbs", 300.0);
        ReflectionTestUtils.setField(service, "maxBackfillInsulin", 50.0);
        when(events.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private GlycemicEvent open(UUID owner) {
        return GlycemicEvent.builder().id(UUID.randomUUID()).userId(owner)
                .category(Category.UNLOGGED_FOOD).direction(Direction.RISE)
                .windowStart(LocalDateTime.now().minusHours(1)).windowEnd(LocalDateTime.now())
                .meanResidualMmol(3.0).state(State.OPEN).build();
    }

    @Test
    void confirmingAnotherUsersEventIs404() {
        GlycemicEvent e = open(UUID.randomUUID());
        when(events.findById(e.getId())).thenReturn(Optional.of(e));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.confirm(user, e.getId(), null, null));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void confirmingAResolvedEventIs409() {
        GlycemicEvent e = open(user);
        e.setState(State.DISMISSED);
        when(events.findById(e.getId())).thenReturn(Optional.of(e));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.confirm(user, e.getId(), 30.0, null));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void backfillAboveCapIs422AndWritesNoNote() {
        GlycemicEvent e = open(user);
        when(events.findById(e.getId())).thenReturn(Optional.of(e));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.confirm(user, e.getId(), 400.0, null));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
        verify(notes, never()).save(any());
    }

    @Test
    void dismissMarksDismissed() {
        GlycemicEvent e = open(user);
        when(events.findById(e.getId())).thenReturn(Optional.of(e));
        assertEquals("DISMISSED", service.dismiss(user, e.getId()).state());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests '*GlycemicEventServiceTest'`
Expected: FAIL to compile, because `GlycemicEventService` doesn't exist.

- [ ] **Step 3: Create `GlycemicEventService` by moving the user operations**

```java
package che.glucosemonitorbe.service;

import che.glucosemonitorbe.dto.GlycemicEventDTO;
import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.State;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.GlycemicEventRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * User-facing operations on glycemic events: list, confirm (optionally backfilling the real amount as
 * a note) and dismiss. Detection lives in {@link GlycemicEventScanService}.
 */
@Service
@RequiredArgsConstructor
public class GlycemicEventService {

    private final GlycemicEventRepository eventRepository;
    private final NoteRepository noteRepository;

    @Value("${app.glycemic-events.backfill-max-carbs:300}")
    private double maxBackfillCarbs;
    @Value("${app.glycemic-events.backfill-max-insulin:50}")
    private double maxBackfillInsulin;

    @Transactional(readOnly = true)
    public List<GlycemicEventDTO> list(UUID userId, State stateFilter) {
        List<GlycemicEvent> found = stateFilter == null
                ? eventRepository.findByUserIdOrderByDetectedAtDesc(userId)
                : eventRepository.findByUserIdAndStateOrderByDetectedAtDesc(userId, stateFilter);
        return found.stream().map(GlycemicEventDTO::from).toList();
    }

    @Transactional
    public GlycemicEventDTO confirm(UUID userId, UUID eventId, Double carbs, Double insulin) {
        GlycemicEvent event = requireOpen(userId, eventId);
        boolean backfill = (carbs != null && carbs > 0) || (insulin != null && insulin > 0);
        if (backfill) {
            double c = validateAmount(carbs, maxBackfillCarbs, "carbs");
            double u = validateAmount(insulin, maxBackfillInsulin, "insulin");
            Note note = new Note();
            note.setUserId(userId);
            note.setTimestamp(event.getWindowStart());
            note.setCarbs(c);
            note.setInsulin(u);
            note.setMeal("Unlogged (backfilled)");
            note.setType(Note.TYPE_NORMAL);
            note.setCreatedAt(LocalDateTime.now());
            note.setUpdatedAt(LocalDateTime.now());
            noteRepository.save(note);
        }
        event.setState(State.CONFIRMED);
        event.setResolvedAt(LocalDateTime.now());
        event.setUpdatedAt(LocalDateTime.now());
        return GlycemicEventDTO.from(eventRepository.save(event));
    }

    @Transactional
    public GlycemicEventDTO dismiss(UUID userId, UUID eventId) {
        GlycemicEvent event = requireOpen(userId, eventId);
        event.setState(State.DISMISSED);
        event.setResolvedAt(LocalDateTime.now());
        event.setUpdatedAt(LocalDateTime.now());
        return GlycemicEventDTO.from(eventRepository.save(event));
    }

    GlycemicEvent requireOpen(UUID userId, UUID eventId) {
        GlycemicEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
        if (!event.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"); // don't leak existence
        }
        if (event.getState() != State.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Event already resolved");
        }
        return event;
    }

    double validateAmount(Double v, double max, String name) {
        double x = v == null ? 0.0 : v;
        if (x < 0 || x > max) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid backfill " + name + ": " + v);
        }
        return x;
    }
}
```

Then, in `GlycemicEventScanService`:
- delete the "List / confirm / dismiss" section (`list`, `confirm`, `dismiss`, `requireOpenFlag`, `validateAmount`), the `maxBackfillCarbs` / `maxBackfillInsulin` fields, and the imports that are now unused (`GlycemicEventDTO`, `HttpStatus`, `ResponseStatusException`)
- update the class javadoc's last sentence to say that user operations live in `GlycemicEventService`

In `GlycemicEventController`, replace the `GlycemicEventScanService detectionService` field with `GlycemicEventService eventService`, and call `eventService.list/confirm/dismiss` with the same arguments as before.

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests '*GlycemicEvent*'`
Expected: PASS, including all 17 E2E cases unchanged.

- [ ] **Step 5: Commit**

```bash
git add -A src/
git commit -m "refactor: split glycemic event scanning from user operations

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Phase 2 — New backend behaviour

### Task 3: Final schema, taxonomy, feature vector and history

**Files:**
- Modify: `src/main/resources/db/migration/V9__glycemic_events.sql`
- Create: `src/main/resources/db/migration/V12__glycemic_event_history.sql`
- Modify: `entity/GlycemicEvent.java`
- Create: `glycemic/EventFeatures.java`, `entity/GlycemicEventHistory.java`, `repository/GlycemicEventHistoryRepository.java`, `service/GlycemicEventHistoryRecorder.java`
- Modify: `repository/GlycemicEventRepository.java`
- Modify: `service/GlycemicEventScanService.java` (`persistFlag`: set new fields and record history), `service/GlycemicEventService.java` (record history on confirm/dismiss)
- Create tests: `src/test/java/che/glucosemonitorbe/glycemic/CategoryTest.java`, `src/test/java/che/glucosemonitorbe/glycemic/EventFeaturesBuilder.java` (test fixture), `src/test/java/che/glucosemonitorbe/integration/GlycemicEventSchemaE2ETest.java`

**Interfaces:**
- Produces:
  - `GlycemicEvent.Category` (10 values, each with `Direction direction()` and `static List<Category> forDirection(Direction)`); `GlycemicEvent.Phase { PROVISIONAL, FINAL }`.
  - New `GlycemicEvent` fields: `onsetAt`, `phase`, `resolvesAt`, `linkedEventId`, `detectorCategory`, `userLabel`, `confidence`, `suppressed`, `features` (`EventFeatures`).
  - `EventFeatures` record (fields below) with `EventFeatures withTail(Double insulinEffectRatio, Double tailMinResidual, Double minToTailDrop, Integer recentNoEffectCount14d)`.
  - `GlycemicEventHistory` (+ `Kind`, `Actor`), `GlycemicEventHistoryRepository.findByEventIdOrderByAtAsc(UUID)`.
  - `GlycemicEventHistoryRecorder.record(GlycemicEvent e, Kind kind, Category before, Actor actor, String reason)`.
  - New `GlycemicEventRepository` finders:
    - `findByUserIdAndStateInAndWindowEndAfter(UUID, Collection<State>, LocalDateTime)`
    - `findByLinkedEventId(UUID)`
    - `countByUserIdAndDetectorCategoryAndOnsetAtAfter(UUID, Category, LocalDateTime)`
    - `findByUserIdAndOnsetAtLessThanEqualAndWindowEndGreaterThanEqualOrderByOnsetAtAsc(UUID, LocalDateTime to, LocalDateTime from)`
  - Test fixture `EventFeaturesBuilder` (fluent, neutral defaults).

- [ ] **Step 1: Write the failing unit test for the taxonomy**

`src/test/java/che/glucosemonitorbe/glycemic/CategoryTest.java`:

```java
package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.entity.GlycemicEvent.Category;
import che.glucosemonitorbe.entity.GlycemicEvent.Direction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryTest {

    @Test
    void everyCategoryBelongsToExactlyOneDirection() {
        assertThat(Category.forDirection(Direction.RISE)).containsExactlyInAnyOrder(
                Category.UNLOGGED_FOOD, Category.UNDER_ESTIMATED_FOOD, Category.STRESS,
                Category.ABSORPTION_DELAY, Category.LIPODYSTROPHY, Category.DAWN_PHENOMENON,
                Category.ANAEROBIC_ACTIVITY);
        assertThat(Category.forDirection(Direction.FALL)).containsExactlyInAnyOrder(
                Category.UNLOGGED_INSULIN, Category.UNDER_ESTIMATED_INSULIN, Category.AEROBIC_ACTIVITY);
        assertThat(Category.values()).hasSize(10);
    }

    @Test
    void featuresWithTailKeepsEverythingElse() {
        EventFeatures f = new EventFeaturesBuilder().expectedDrop60(2.0).build();
        EventFeatures t = f.withTail(0.1, -2.5, 180.0, 3);
        assertThat(t.expectedDrop60()).isEqualTo(2.0);
        assertThat(t.insulinEffectRatio()).isEqualTo(0.1);
        assertThat(t.recentNoEffectCount14d()).isEqualTo(3);
    }
}
```

`src/test/java/che/glucosemonitorbe/glycemic/EventFeaturesBuilder.java` (test fixture, used by later tasks):

```java
package che.glucosemonitorbe.glycemic;

/** Fluent fixture for {@link EventFeatures}: neutral midday rise with nothing logged and nothing active. */
public class EventFeaturesBuilder {
    private double onsetHour = 12.0, durationMin = 60, meanResidual = 3.0, peakResidual = 4.0;
    private double residualSlope = 0.05, actualSlope = 0.10, predictedSlope = 0.0, glucoseAtOnset = 7.0;
    private double iobAtOnset = 0.0, expectedDrop60 = 0.0, cobAtOnset = 0.0, sigmaMultiple = 4.0;
    private boolean weekend, activityLogged, carbsLogged, insulinLogged;
    private Double minSinceCarbs, minSinceBolus;

    public EventFeaturesBuilder onsetHour(double v) { onsetHour = v; return this; }
    public EventFeaturesBuilder weekend(boolean v) { weekend = v; return this; }
    public EventFeaturesBuilder durationMin(double v) { durationMin = v; return this; }
    public EventFeaturesBuilder meanResidual(double v) { meanResidual = v; return this; }
    public EventFeaturesBuilder peakResidual(double v) { peakResidual = v; return this; }
    public EventFeaturesBuilder residualSlope(double v) { residualSlope = v; return this; }
    public EventFeaturesBuilder actualSlope(double v) { actualSlope = v; return this; }
    public EventFeaturesBuilder glucoseAtOnset(double v) { glucoseAtOnset = v; return this; }
    public EventFeaturesBuilder iobAtOnset(double v) { iobAtOnset = v; return this; }
    public EventFeaturesBuilder expectedDrop60(double v) { expectedDrop60 = v; return this; }
    public EventFeaturesBuilder cobAtOnset(double v) { cobAtOnset = v; return this; }
    public EventFeaturesBuilder minSinceCarbs(Double v) { minSinceCarbs = v; return this; }
    public EventFeaturesBuilder minSinceBolus(Double v) { minSinceBolus = v; return this; }
    public EventFeaturesBuilder activityLogged(boolean v) { activityLogged = v; return this; }
    public EventFeaturesBuilder carbsLogged(boolean v) { carbsLogged = v; return this; }
    public EventFeaturesBuilder insulinLogged(boolean v) { insulinLogged = v; return this; }
    public EventFeaturesBuilder sigmaMultiple(double v) { sigmaMultiple = v; return this; }

    public EventFeatures build() {
        return new EventFeatures(EventFeatures.VERSION, onsetHour, weekend, durationMin, meanResidual,
                peakResidual, residualSlope, actualSlope, predictedSlope, glucoseAtOnset, iobAtOnset,
                expectedDrop60, cobAtOnset, minSinceCarbs, minSinceBolus, activityLogged, carbsLogged,
                insulinLogged, sigmaMultiple, null, null, null, null);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests '*CategoryTest'`
Expected: FAIL to compile (`EventFeatures`, `forDirection` and the new categories are missing).

- [ ] **Step 3: Create `EventFeatures`**

`src/main/java/che/glucosemonitorbe/glycemic/EventFeatures.java`:

```java
package che.glucosemonitorbe.glycemic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Feature vector of a glycemic event at its onset (spec section 1.3). Stored as JSONB; unknown keys are
 * ignored on read so the set can grow. Tail fields are null until {@code TailResolver} sets them.
 * Units: mmol/L, mmol/L/min, minutes, grams, units of insulin.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventFeatures(
        int v,
        double onsetHour,
        boolean weekend,
        double durationMin,
        double meanResidual,
        double peakResidual,
        double residualSlope,
        double actualSlope,
        double predictedSlope,
        double glucoseAtOnset,
        double iobAtOnset,
        double expectedDrop60,
        double cobAtOnset,
        Double minSinceCarbs,
        Double minSinceBolus,
        boolean activityLogged,
        boolean carbsLogged,
        boolean insulinLogged,
        double sigmaMultiple,
        Double insulinEffectRatio,
        Double tailMinResidual,
        Double minToTailDrop,
        Integer recentNoEffectCount14d) {

    public static final int VERSION = 1;

    public EventFeatures withTail(Double effectRatio, Double tailMin, Double minToDrop, Integer recentNoEffect) {
        return new EventFeatures(v, onsetHour, weekend, durationMin, meanResidual, peakResidual,
                residualSlope, actualSlope, predictedSlope, glucoseAtOnset, iobAtOnset, expectedDrop60,
                cobAtOnset, minSinceCarbs, minSinceBolus, activityLogged, carbsLogged, insulinLogged,
                sigmaMultiple, effectRatio, tailMin, minToDrop, recentNoEffect);
    }
}
```

- [ ] **Step 4: Replace the entity**

Replace `entity/GlycemicEvent.java` entirely:

```java
package che.glucosemonitorbe.entity;

import che.glucosemonitorbe.glycemic.EventFeatures;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * A window in which glucose moved in a way the user's logged inputs (COB/IOB) do not explain, with the
 * engine's explanation. Serves the user-confirmation API, the chart, and the digital-twin calibration
 * (which down-weights OPEN/CONFIRMED windows except DAWN_PHENOMENON).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "glycemic_events")
public class GlycemicEvent {

    /** Direction of the unexplained move. */
    public enum Direction { RISE, FALL }

    /** What the residual most likely indicates. Each category belongs to one direction. */
    public enum Category {
        UNLOGGED_FOOD(Direction.RISE),
        UNDER_ESTIMATED_FOOD(Direction.RISE),
        STRESS(Direction.RISE),
        ABSORPTION_DELAY(Direction.RISE),
        LIPODYSTROPHY(Direction.RISE),
        DAWN_PHENOMENON(Direction.RISE),
        ANAEROBIC_ACTIVITY(Direction.RISE),
        UNLOGGED_INSULIN(Direction.FALL),
        UNDER_ESTIMATED_INSULIN(Direction.FALL),
        AEROBIC_ACTIVITY(Direction.FALL);

        private final Direction direction;

        Category(Direction direction) { this.direction = direction; }

        public Direction direction() { return direction; }

        public static List<Category> forDirection(Direction d) {
            return Arrays.stream(values()).filter(c -> c.direction == d).toList();
        }
    }

    /** PROVISIONAL events wait for tail evidence (spec section 4); FINAL events are settled. */
    public enum Phase { PROVISIONAL, FINAL }

    /** Lifecycle: OPEN (detected) -> CONFIRMED or DISMISSED (by the user). */
    public enum State { OPEN, CONFIRMED, DISMISSED }

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Effective category: {@link #userLabel} when set, else the detector/resolver result. */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 28)
    private Category category;

    /** What the engine concluded; never overwritten by the user. */
    @Enumerated(EnumType.STRING)
    @Column(name = "detector_category", nullable = false, length = 28)
    private Category detectorCategory;

    /** Category the user chose when it differs from the engine's. */
    @Enumerated(EnumType.STRING)
    @Column(name = "user_label", length = 28)
    private Category userLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 8)
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "phase", nullable = false, length = 12)
    @Builder.Default
    private Phase phase = Phase.FINAL;

    /** Inflection point where the move began - the chart marker position. */
    @Column(name = "onset_at", nullable = false)
    private LocalDateTime onsetAt;

    @Column(name = "window_start", nullable = false)
    private LocalDateTime windowStart;

    @Column(name = "window_end", nullable = false)
    private LocalDateTime windowEnd;

    /** End of the tail watch; non-null only while PROVISIONAL. */
    @Column(name = "resolves_at")
    private LocalDateTime resolvesAt;

    /** Mean residual (actual - predicted) over the window [mmol/L]. Sign matches direction. */
    @Column(name = "mean_residual_mmol", nullable = false)
    private Double meanResidualMmol;

    /** How many robust-sigma the mean residual is. */
    @Column(name = "sigma_multiple")
    private Double sigmaMultiple;

    /** 0-1 confidence for the effective category. */
    @Column(name = "confidence")
    private Double confidence;

    /** The learner judged this "not a real event": stored and drawn faintly, never prompted. */
    @Column(name = "suppressed", nullable = false)
    @Builder.Default
    private boolean suppressed = false;

    /** Parent event for a linked child (slingshot drop, anaerobic drop). */
    @Column(name = "linked_event_id")
    private UUID linkedEventId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "features", columnDefinition = "jsonb")
    private EventFeatures features;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 12)
    @Builder.Default
    private State state = State.OPEN;

    @CreationTimestamp
    @Column(name = "detected_at", nullable = false, updatable = false)
    private LocalDateTime detectedAt;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    /** Rows built without the new fields (older tests, manual inserts) get safe defaults. */
    @PrePersist
    void defaults() {
        if (onsetAt == null) onsetAt = windowStart;
        if (detectorCategory == null) detectorCategory = category;
    }
}
```

- [ ] **Step 5: Rewrite V9 to the final shape and add V12**

Replace `V9__glycemic_events.sql`'s `CREATE TABLE` and indexes with the version below. Keep the header comment.

```sql
CREATE TABLE IF NOT EXISTS glycemic_events (
    id                 UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID             NOT NULL,
    category           VARCHAR(28)      NOT NULL,
    detector_category  VARCHAR(28)      NOT NULL,
    user_label         VARCHAR(28),
    direction          VARCHAR(8)       NOT NULL,
    phase              VARCHAR(12)      NOT NULL DEFAULT 'FINAL',
    onset_at           TIMESTAMPTZ      NOT NULL,
    window_start       TIMESTAMPTZ      NOT NULL,
    window_end         TIMESTAMPTZ      NOT NULL,
    resolves_at        TIMESTAMPTZ,
    mean_residual_mmol DOUBLE PRECISION NOT NULL,
    sigma_multiple     DOUBLE PRECISION,
    confidence         DOUBLE PRECISION,
    suppressed         BOOLEAN          NOT NULL DEFAULT FALSE,
    linked_event_id    UUID,
    features           JSONB,
    state              VARCHAR(12)      NOT NULL DEFAULT 'OPEN',
    detected_at        TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    resolved_at        TIMESTAMPTZ,
    CONSTRAINT fk_glycemic_events_user   FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_glycemic_events_parent FOREIGN KEY (linked_event_id) REFERENCES glycemic_events(id) ON DELETE SET NULL,
    CONSTRAINT chk_glycemic_category CHECK (category IN
        ('UNLOGGED_FOOD','UNDER_ESTIMATED_FOOD','STRESS','ABSORPTION_DELAY','LIPODYSTROPHY','DAWN_PHENOMENON',
         'ANAEROBIC_ACTIVITY','UNLOGGED_INSULIN','UNDER_ESTIMATED_INSULIN','AEROBIC_ACTIVITY')),
    CONSTRAINT chk_glycemic_detector_category CHECK (detector_category IN
        ('UNLOGGED_FOOD','UNDER_ESTIMATED_FOOD','STRESS','ABSORPTION_DELAY','LIPODYSTROPHY','DAWN_PHENOMENON',
         'ANAEROBIC_ACTIVITY','UNLOGGED_INSULIN','UNDER_ESTIMATED_INSULIN','AEROBIC_ACTIVITY')),
    CONSTRAINT chk_glycemic_user_label CHECK (user_label IS NULL OR user_label IN
        ('UNLOGGED_FOOD','UNDER_ESTIMATED_FOOD','STRESS','ABSORPTION_DELAY','LIPODYSTROPHY','DAWN_PHENOMENON',
         'ANAEROBIC_ACTIVITY','UNLOGGED_INSULIN','UNDER_ESTIMATED_INSULIN','AEROBIC_ACTIVITY')),
    CONSTRAINT chk_glycemic_direction CHECK (direction IN ('RISE','FALL')),
    CONSTRAINT chk_glycemic_phase     CHECK (phase IN ('PROVISIONAL','FINAL')),
    CONSTRAINT chk_glycemic_state     CHECK (state IN ('OPEN','CONFIRMED','DISMISSED'))
);

CREATE INDEX IF NOT EXISTS idx_glycemic_events_user       ON glycemic_events(user_id);
CREATE INDEX IF NOT EXISTS idx_glycemic_events_state      ON glycemic_events(state);
CREATE INDEX IF NOT EXISTS idx_glycemic_events_user_state ON glycemic_events(user_id, state);
CREATE INDEX IF NOT EXISTS idx_glycemic_events_user_onset ON glycemic_events(user_id, onset_at);
CREATE INDEX IF NOT EXISTS idx_glycemic_events_user_phase ON glycemic_events(user_id, phase);
CREATE INDEX IF NOT EXISTS idx_glycemic_events_linked     ON glycemic_events(linked_event_id);
```

`src/main/resources/db/migration/V12__glycemic_event_history.sql`:

```sql
-- Append-only audit trail of every state/category change on a glycemic event (spec section 1.2).
-- Rows are never updated or deleted by application code.

CREATE TABLE IF NOT EXISTS glycemic_event_history (
    id              UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id        UUID             NOT NULL,
    user_id         UUID             NOT NULL,
    at              TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    kind            VARCHAR(12)      NOT NULL,
    category_before VARCHAR(28),
    category_after  VARCHAR(28),
    confidence      DOUBLE PRECISION,
    actor           VARCHAR(8)       NOT NULL,
    reason          VARCHAR(24),
    CONSTRAINT fk_glycemic_history_event FOREIGN KEY (event_id) REFERENCES glycemic_events(id) ON DELETE CASCADE,
    CONSTRAINT chk_glycemic_history_kind  CHECK (kind IN
        ('DETECTED','UPDATED','RESOLVED','CONFIRMED','RELABELLED','DISMISSED')),
    CONSTRAINT chk_glycemic_history_actor CHECK (actor IN ('SYSTEM','USER'))
);

CREATE INDEX IF NOT EXISTS idx_glycemic_history_event ON glycemic_event_history(event_id, at);
```

- [ ] **Step 6: History entity, repository, recorder; new finders**

`entity/GlycemicEventHistory.java`:

```java
package che.glucosemonitorbe.entity;

import che.glucosemonitorbe.entity.GlycemicEvent.Category;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/** One immutable row per state/category change on a {@link GlycemicEvent}. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "glycemic_event_history")
public class GlycemicEventHistory {

    public enum Kind { DETECTED, UPDATED, RESOLVED, CONFIRMED, RELABELLED, DISMISSED }

    public enum Actor { SYSTEM, USER }

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @CreationTimestamp
    @Column(name = "at", nullable = false, updatable = false)
    private LocalDateTime at;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 12)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "category_before", length = 28)
    private Category categoryBefore;

    @Enumerated(EnumType.STRING)
    @Column(name = "category_after", length = 28)
    private Category categoryAfter;

    @Column(name = "confidence")
    private Double confidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor", nullable = false, length = 8)
    private Actor actor;

    @Column(name = "reason", length = 24)
    private String reason;
}
```

`repository/GlycemicEventHistoryRepository.java`:

```java
package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.entity.GlycemicEventHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface GlycemicEventHistoryRepository extends JpaRepository<GlycemicEventHistory, UUID> {
    List<GlycemicEventHistory> findByEventIdOrderByAtAsc(UUID eventId);
}
```

`service/GlycemicEventHistoryRecorder.java`:

```java
package che.glucosemonitorbe.service;

import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.Category;
import che.glucosemonitorbe.entity.GlycemicEventHistory;
import che.glucosemonitorbe.entity.GlycemicEventHistory.Actor;
import che.glucosemonitorbe.entity.GlycemicEventHistory.Kind;
import che.glucosemonitorbe.repository.GlycemicEventHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Appends one history row. Call after the event is saved (it needs the id), in the same transaction. */
@Component
@RequiredArgsConstructor
public class GlycemicEventHistoryRecorder {

    private final GlycemicEventHistoryRepository historyRepository;

    public void record(GlycemicEvent e, Kind kind, Category before, Actor actor, String reason) {
        historyRepository.save(GlycemicEventHistory.builder()
                .eventId(e.getId()).userId(e.getUserId()).kind(kind)
                .categoryBefore(before).categoryAfter(e.getCategory())
                .confidence(e.getConfidence()).actor(actor).reason(reason)
                .build());
    }
}
```

Add to `GlycemicEventRepository` (with imports `java.time.LocalDateTime`, `che.glucosemonitorbe.entity.GlycemicEvent.Category`):

```java
    /** Events in the given states whose window ends after {@code after} - the scan's "already answered" check. */
    List<GlycemicEvent> findByUserIdAndStateInAndWindowEndAfter(UUID userId, Collection<State> states, LocalDateTime after);

    /** Linked children of a parent event. */
    List<GlycemicEvent> findByLinkedEventId(UUID parentId);

    /** Recurrence count for lipodystrophy confidence. */
    long countByUserIdAndDetectorCategoryAndOnsetAtAfter(UUID userId, Category category, LocalDateTime after);

    /** Events whose [onset_at, window_end] intersects [from, to] (note the argument order: to, then from). */
    List<GlycemicEvent> findByUserIdAndOnsetAtLessThanEqualAndWindowEndGreaterThanEqualOrderByOnsetAtAsc(
            UUID userId, LocalDateTime to, LocalDateTime from);
```

- [ ] **Step 7: Write history on detection, confirm and dismiss**

In `GlycemicEventScanService`:
- inject `private final GlycemicEventHistoryRecorder historyRecorder;`
- in `persistFlag`, before `return flagRepository.save(flag)` (the repository field is now named `glycemicEventRepository` or `flagRepository` depending on the sed; use whichever name it has), record whether the event is new and its category before
- set `flag.setOnsetAt(wStart); flag.setDetectorCategory(category); flag.setPhase(GlycemicEvent.Phase.FINAL);`
- save, then:

```java
        boolean isNew = flag.getId() == null;
        Category before = flag.getCategory();
        // ... existing setters, plus the three above ...
        GlycemicEvent saved = flagRepository.save(flag);
        if (isNew) {
            historyRecorder.record(saved, GlycemicEventHistory.Kind.DETECTED, null, GlycemicEventHistory.Actor.SYSTEM, "rule:legacy");
        } else if (before != category) {
            historyRecorder.record(saved, GlycemicEventHistory.Kind.UPDATED, before, GlycemicEventHistory.Actor.SYSTEM, "rule:legacy");
        }
        return saved;
```

(The `isNew`/`before` locals must be captured before the setters run.) `UPDATED` is written only when the category or phase changes. Geometry refreshes every 10 minutes would otherwise flood the audit trail.

In `GlycemicEventService`, add `private final GlycemicEventHistoryRecorder historyRecorder;` as the **last** field (Lombok's constructor follows field order), and update `GlycemicEventServiceTest.setUp` to `new GlycemicEventService(events, notes, mock(GlycemicEventHistoryRecorder.class))`. After the save in `confirm`, call `historyRecorder.record(saved, Kind.CONFIRMED, saved.getCategory(), Actor.USER, null)`. In `dismiss`, call `historyRecorder.record(saved, Kind.DISMISSED, saved.getCategory(), Actor.USER, null)`.

- [ ] **Step 8: Write the schema E2E test**

`src/test/java/che/glucosemonitorbe/integration/GlycemicEventSchemaE2ETest.java`:

```java
package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.*;
import che.glucosemonitorbe.entity.GlycemicEventHistory;
import che.glucosemonitorbe.domain.User;
import che.glucosemonitorbe.glycemic.EventFeaturesBuilder;
import che.glucosemonitorbe.repository.GlycemicEventHistoryRepository;
import che.glucosemonitorbe.repository.GlycemicEventRepository;
import che.glucosemonitorbe.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {"app.glucose-sync.enabled=false"})
@SuppressWarnings({"resource", "null"})
class GlycemicEventSchemaE2ETest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("testdb").withUsername("test").withPassword("test");

    @Autowired UserRepository users;
    @Autowired GlycemicEventRepository events;
    @Autowired GlycemicEventHistoryRepository history;

    private UUID newUser() {
        String name = "schema_" + UUID.randomUUID().toString().substring(0, 8);
        return users.save(User.builder().username(name).email(name + "@example.com").password("x")
                .fullName("Schema").role(User.Role.USER)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build()).getId();
    }

    @Test
    void everyCategoryPhaseAndStateRoundTripsWithFeatures() {
        UUID userId = newUser();
        LocalDateTime t = LocalDateTime.of(2026, 9, 30, 10, 0);
        for (Category c : Category.values()) {
            for (Phase p : Phase.values()) {
                GlycemicEvent saved = events.save(GlycemicEvent.builder().userId(userId)
                        .category(c).detectorCategory(c).userLabel(c).direction(c.direction()).phase(p)
                        .onsetAt(t).windowStart(t).windowEnd(t.plusHours(1)).resolvesAt(t.plusHours(6))
                        .meanResidualMmol(1.0).confidence(0.7).suppressed(true)
                        .features(new EventFeaturesBuilder().expectedDrop60(2.5).build().withTail(0.1, null, null, 2))
                        .state(State.CONFIRMED).build());
                GlycemicEvent back = events.findById(saved.getId()).orElseThrow();
                assertThat(back.getCategory()).isEqualTo(c);
                assertThat(back.getPhase()).isEqualTo(p);
                assertThat(back.getFeatures().expectedDrop60()).isEqualTo(2.5);
                assertThat(back.getFeatures().insulinEffectRatio()).isEqualTo(0.1);
            }
        }
    }

    @Test
    void prePersistDefaultsOnsetAndDetectorCategory() {
        UUID userId = newUser();
        LocalDateTime t = LocalDateTime.of(2026, 9, 30, 10, 0);
        GlycemicEvent saved = events.save(GlycemicEvent.builder().userId(userId)
                .category(Category.UNLOGGED_FOOD).direction(Direction.RISE)
                .windowStart(t).windowEnd(t.plusHours(1)).meanResidualMmol(1.0).build());
        assertThat(saved.getOnsetAt()).isEqualTo(t);
        assertThat(saved.getDetectorCategory()).isEqualTo(Category.UNLOGGED_FOOD);
    }

    @Test
    void childLinkAndHistoryPersist() {
        UUID userId = newUser();
        LocalDateTime t = LocalDateTime.of(2026, 9, 30, 10, 0);
        GlycemicEvent parent = events.save(GlycemicEvent.builder().userId(userId)
                .category(Category.ABSORPTION_DELAY).direction(Direction.RISE)
                .windowStart(t).windowEnd(t.plusHours(1)).meanResidualMmol(2.0).build());
        GlycemicEvent child = events.save(GlycemicEvent.builder().userId(userId)
                .category(Category.ABSORPTION_DELAY).direction(Direction.FALL).linkedEventId(parent.getId())
                .windowStart(t.plusHours(3)).windowEnd(t.plusHours(4)).meanResidualMmol(-2.0).build());
        history.save(GlycemicEventHistory.builder().eventId(parent.getId()).userId(userId)
                .kind(GlycemicEventHistory.Kind.RESOLVED).categoryAfter(Category.ABSORPTION_DELAY)
                .actor(GlycemicEventHistory.Actor.SYSTEM).reason("tail:slingshot").build());

        assertThat(events.findByLinkedEventId(parent.getId())).extracting(GlycemicEvent::getId).containsExactly(child.getId());
        assertThat(history.findByEventIdOrderByAtAsc(parent.getId())).hasSize(1);
    }
}
```

- [ ] **Step 9: Run the tests**

Run: `./gradlew test --tests '*CategoryTest' --tests '*GlycemicEventSchemaE2ETest' --tests '*GlycemicEvent*'`
Expected: PASS. The legacy T12 builder omits `onsetAt`/`detectorCategory`, and `@PrePersist` fills them in.

- [ ] **Step 10: Commit**

```bash
git add -A src/
git commit -m "feat(glycemic): final event schema, ten-category taxonomy, feature vector and history

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Configuration properties and the multi-run `ResidualScanner`

**Files:**
- Create: `config/GlycemicEventProperties.java`
- Create: `glycemic/ResidualPoint.java`, `glycemic/ResidualSeries.java`, `glycemic/ResidualRun.java`, `glycemic/ResidualScanner.java`
- Modify: `service/GlycemicEventScanService.java` (replace `@Value` fields with properties; use `ResidualScanner`, still picking the strongest run for now)
- Modify: `scheduler/GlycemicEventScanScheduler.java` (interval default 600000)
- Modify: `src/main/resources/application.yml` (add the `app.glycemic-events` block)
- Modify test: `service/GlycemicEventScanServiceTest.java` (delete the tests of the removed static `strongestRun`/`robustScale`; they move to `ResidualScannerTest`)
- Create test: `src/test/java/che/glucosemonitorbe/glycemic/ResidualScannerTest.java`

**Interfaces:**
- Produces:
  - `GlycemicEventProperties`: getters for every §8 key, plus `alignToleranceMs`, `cobZeroGrams`, `effectDenominatorFloor`, `dropResolveMinutes` and `initialDelayMs`.
  - `record ResidualPoint(long epochMs, double actualMmol, double predictedMmol)` with `double residual()`.
  - `record ResidualSeries(List<ResidualPoint> points, List<Double> smoothed, double sigma)` with `int size()`, `long epochMs(int)`, `double smoothed(int)`.
  - `record ResidualRun(int from, int to, int firstExceed, long startMs, long endMs, double mean, double peak)` with `Direction direction()` and `double durationMinutes()`.
  - `ResidualScanner(double sigmaMultiple, int persistenceMinutes, int maxGapMinutes, int minReadings)` with `List<ResidualRun> runs(ResidualSeries)`, `static ResidualSeries series(List<ResidualPoint>)`, `static Optional<ResidualRun> strongest(List<ResidualRun>)`, `static double robustScale(List<Double>)` and `static List<Double> median3(List<Double>)`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/che/glucosemonitorbe/glycemic/ResidualScannerTest.java`:

```java
package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.entity.GlycemicEvent.Direction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResidualScannerTest {

    private static final long STEP = 5 * 60_000L;
    private final ResidualScanner scanner = new ResidualScanner(2.0, 45, 15, 24);

    /** Builds points 5 min apart; residual = value (predicted 0). A NaN entry inserts a 20-min gap instead. */
    static ResidualSeries series(List<Double> residuals) {
        List<ResidualPoint> pts = new ArrayList<>();
        long t = 0;
        for (Double r : residuals) {
            if (r.isNaN()) { t += 3 * STEP; continue; }   // 20 min between neighbours instead of 5
            pts.add(new ResidualPoint(t, r, 0.0));
            t += STEP;
        }
        return ResidualScanner.series(pts);
    }

    static List<Double> noise(int n) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(i % 2 == 0 ? 0.1 : -0.1);
        return out;
    }

    static List<Double> flat(int n, double v) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(v);
        return out;
    }

    @SafeVarargs
    static List<Double> concat(List<Double>... parts) {
        List<Double> out = new ArrayList<>();
        for (List<Double> p : parts) out.addAll(p);
        return out;
    }

    @Test
    void returnsEveryQualifyingRunNotJustTheStrongest() {
        ResidualSeries s = series(concat(noise(20), flat(10, 3.0), noise(20), flat(10, -3.0), noise(10)));
        List<ResidualRun> runs = scanner.runs(s);
        assertThat(runs).hasSize(2);
        assertThat(runs.get(0).direction()).isEqualTo(Direction.RISE);
        assertThat(runs.get(1).direction()).isEqualTo(Direction.FALL);
    }

    @Test
    void oneOppositeSignReadingDoesNotSplitARun() {
        // Unsmoothed this is a 20-min and a 25-min run (both rejected); median-3 heals the blip.
        ResidualSeries s = series(concat(noise(20), flat(5, 3.0), List.of(-3.0), flat(6, 3.0), noise(20)));
        List<ResidualRun> runs = scanner.runs(s);
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).durationMinutes()).isGreaterThanOrEqualTo(45);
    }

    @Test
    void aCgmGapLongerThanTheLimitSplitsARun() {
        // 25 min + 20-min gap + 25 min would pass persistence as one run; split, neither half does.
        ResidualSeries s = series(concat(noise(20), flat(6, 3.0), List.of(Double.NaN), flat(6, 3.0), noise(20)));
        assertThat(scanner.runs(s)).isEmpty();
    }

    @Test
    void tooFewPointsYieldNothing() {
        assertThat(scanner.runs(series(flat(10, 5.0)))).isEmpty();
    }

    @Test
    void firstExceedIsTheFirstPointPastThreshold() {
        List<Double> ramp = List.of(0.1, 0.2, 0.4, 0.8, 1.2, 1.6, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0);
        ResidualSeries s = series(concat(noise(20), ramp, noise(10)));
        ResidualRun run = scanner.runs(s).get(0);
        // sigma floors at 0.3 -> threshold 0.6; the first smoothed value >= 0.6 is 0.8 (index 20 + 3)
        assertThat(run.firstExceed()).isEqualTo(23);
    }

    @Test
    void robustScaleIsMadFloored() {
        assertThat(ResidualScanner.robustScale(List.of(0.0, 0.0, 0.0, 0.0))).isEqualTo(0.3);
        assertThat(ResidualScanner.robustScale(List.of(1.0, 2.0, 3.0, 4.0, 5.0))).isCloseTo(1.4826, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void strongestPicksLargestAbsoluteMean() {
        ResidualSeries s = series(concat(noise(20), flat(10, 3.0), noise(20), flat(10, -4.0), noise(10)));
        assertThat(ResidualScanner.strongest(scanner.runs(s)).orElseThrow().direction()).isEqualTo(Direction.FALL);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests '*ResidualScannerTest'`
Expected: FAIL to compile.

- [ ] **Step 3: Implement the records and the scanner**

`glycemic/ResidualPoint.java`:

```java
package che.glucosemonitorbe.glycemic;

/** One CGM reading aligned to the model's prediction [mmol/L]. */
public record ResidualPoint(long epochMs, double actualMmol, double predictedMmol) {
    public double residual() { return actualMmol - predictedMmol; }
}
```

`glycemic/ResidualSeries.java`:

```java
package che.glucosemonitorbe.glycemic;

import java.util.List;

/** Aligned residual points, their median-3 smoothing, and the robust sigma of the raw residuals. */
public record ResidualSeries(List<ResidualPoint> points, List<Double> smoothed, double sigma) {
    public int size() { return points.size(); }
    public long epochMs(int i) { return points.get(i).epochMs(); }
    public double smoothed(int i) { return smoothed.get(i); }
}
```

`glycemic/ResidualRun.java`:

```java
package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.entity.GlycemicEvent.Direction;

/**
 * A sustained same-sign stretch of smoothed residuals [from, to] (inclusive indices into a series).
 * {@code firstExceed} is the first index whose |smoothed| reaches the detection threshold - where
 * onset backtracking starts. {@code mean} and {@code peak} are over the smoothed values; peak keeps its sign.
 */
public record ResidualRun(int from, int to, int firstExceed, long startMs, long endMs, double mean, double peak) {

    public Direction direction() { return mean >= 0 ? Direction.RISE : Direction.FALL; }

    public double durationMinutes() { return (endMs - startMs) / 60_000.0; }

    static ResidualRun of(ResidualSeries s, int from, int to, double threshold) {
        double sum = 0.0, peak = 0.0;
        int firstExceed = -1;
        for (int i = from; i <= to; i++) {
            double v = s.smoothed(i);
            sum += v;
            if (Math.abs(v) > Math.abs(peak)) peak = v;
            if (firstExceed < 0 && Math.abs(v) >= threshold) firstExceed = i;
        }
        return new ResidualRun(from, to, firstExceed < 0 ? from : firstExceed,
                s.epochMs(from), s.epochMs(to), sum / (to - from + 1), peak);
    }
}
```

`glycemic/ResidualScanner.java`:

```java
package che.glucosemonitorbe.glycemic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Finds every sustained, same-sign stretch of model residual that clears an adaptive threshold
 * (sigmaMultiple x robust sigma) for at least {@code persistenceMinutes} - the sensor-noise guard.
 * Residuals are median-3 smoothed first so a single noisy reading can't split a real run, and a run
 * never bridges a CGM gap longer than {@code maxGapMinutes}.
 */
public final class ResidualScanner {

    /** CGM sensor-noise floor for sigma [mmol/L] - matches the calibrator. */
    static final double SIGMA_FLOOR = 0.3;

    private final double sigmaMultiple;
    private final int persistenceMinutes;
    private final int maxGapMinutes;
    private final int minReadings;

    public ResidualScanner(double sigmaMultiple, int persistenceMinutes, int maxGapMinutes, int minReadings) {
        this.sigmaMultiple = sigmaMultiple;
        this.persistenceMinutes = persistenceMinutes;
        this.maxGapMinutes = maxGapMinutes;
        this.minReadings = minReadings;
    }

    public static ResidualSeries series(List<ResidualPoint> points) {
        List<Double> raw = new ArrayList<>(points.size());
        for (ResidualPoint p : points) raw.add(p.residual());
        return new ResidualSeries(List.copyOf(points), median3(raw), robustScale(raw));
    }

    public List<ResidualRun> runs(ResidualSeries s) {
        List<ResidualRun> out = new ArrayList<>();
        int n = s.size();
        if (n < minReadings) return out;
        double threshold = sigmaMultiple * s.sigma();
        long maxGapMs = maxGapMinutes * 60_000L;
        int i = 0;
        while (i < n) {
            boolean positive = s.smoothed(i) >= 0;
            int j = i + 1;
            while (j < n && (s.smoothed(j) >= 0) == positive
                    && s.epochMs(j) - s.epochMs(j - 1) <= maxGapMs) {
                j++;
            }
            ResidualRun run = ResidualRun.of(s, i, j - 1, threshold);
            if (run.durationMinutes() >= persistenceMinutes && Math.abs(run.mean()) >= threshold) {
                out.add(run);
            }
            i = j;
        }
        return out;
    }

    public static Optional<ResidualRun> strongest(List<ResidualRun> runs) {
        return runs.stream().max(Comparator.comparingDouble(r -> Math.abs(r.mean())));
    }

    /** Running median of three; endpoints keep their raw value. */
    static List<Double> median3(List<Double> v) {
        List<Double> out = new ArrayList<>(v);
        for (int i = 1; i < v.size() - 1; i++) {
            double a = v.get(i - 1), b = v.get(i), c = v.get(i + 1);
            out.set(i, Math.max(Math.min(a, b), Math.min(Math.max(a, b), c)));
        }
        return out;
    }

    /** Robust spread: 1.4826 x MAD, floored at the sensor-noise level. */
    public static double robustScale(List<Double> values) {
        if (values.isEmpty()) return SIGMA_FLOOR;
        double[] a = values.stream().mapToDouble(Double::doubleValue).toArray();
        double median = median(a.clone());
        double[] dev = new double[a.length];
        for (int i = 0; i < a.length; i++) dev[i] = Math.abs(a[i] - median);
        return Math.max(SIGMA_FLOOR, 1.4826 * median(dev));
    }

    private static double median(double[] a) {
        Arrays.sort(a);
        int n = a.length;
        if (n == 0) return 0.0;
        return (n % 2 == 1) ? a[n / 2] : 0.5 * (a[n / 2 - 1] + a[n / 2]);
    }
}
```

- [ ] **Step 4: Run the scanner tests**

Run: `./gradlew test --tests '*ResidualScannerTest'`
Expected: PASS.

- [ ] **Step 5: Add the properties class and YAML block**

`config/GlycemicEventProperties.java`:

```java
package che.glucosemonitorbe.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Every tunable of the glycemic event engine (spec section 8). Defaults here match application.yml. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.glycemic-events")
public class GlycemicEventProperties {
    private long scanIntervalMs = 600_000;
    private long initialDelayMs = 60_000;
    private int windowMinutes = 240;
    private int minReadings = 24;
    private double sigmaMultiple = 2.0;
    private int persistenceMinutes = 45;
    private int maxGapMinutes = 15;
    private long alignToleranceMs = 150_000;
    private int onsetMaxBacktrackMinutes = 60;
    private int matchLeadMinutes = 45;
    private double significantIobDropMmol = 1.5;
    private double cobZeroGrams = 5.0;
    private int dawnStartHour = 3;
    private int dawnEndHour = 8;
    private double dawnMaxSlope = 0.03;
    private double noEffectRatio = 0.25;
    private double effectDenominatorFloor = 0.5;
    private int aerobicMaxMinutes = 90;
    private int dropResolveMinutes = 120;
    private int anaerobicMaxRiseMinutes = 75;
    private int anaerobicMaxGapMinutes = 30;
    private double promptMinConfidence = 0.5;
    private int promptMaxAgeHours = 6;
    private int learnerK = 5;
    private double learnerPriorWeight = 10.0;
    private double learnerOverride = 0.6;
    private double backfillMaxCarbs = 300.0;
    private double backfillMaxInsulin = 50.0;
}
```

In `application.yml`, add a top-level `app.glycemic-events` block (sibling of `app.features`, under `app:`):

```yaml
  # Glycemic event engine (spec 2026-09-30). All keys optional - defaults live in GlycemicEventProperties.
  glycemic-events:
    scan-interval-ms: ${GLYCEMIC_EVENTS_SCAN_INTERVAL_MS:600000}
    window-minutes: 240
```

In `GlycemicEventScanScheduler`, change the annotation to:

```java
    @Scheduled(
            initialDelayString = "${app.glycemic-events.initial-delay-ms:60000}",
            fixedDelayString = "${app.glycemic-events.scan-interval-ms:600000}")
```

In `GlycemicEventService`, replace the two `@Value` backfill fields with `private final GlycemicEventProperties props;` declared as the **last** field, and use `props.getBackfillMaxCarbs()` / `props.getBackfillMaxInsulin()`. Update `GlycemicEventServiceTest.setUp` to `new GlycemicEventService(events, notes, mock(GlycemicEventHistoryRecorder.class), new GlycemicEventProperties())` and delete the two `ReflectionTestUtils.setField` lines.

- [ ] **Step 6: Use the scanner in the scan service**

In `GlycemicEventScanService`:
- replace every `@Value` field with an injected `private final GlycemicEventProperties props;`, reading `props.getWindowMinutes()`, `props.getMinReadings()`, `props.getSigmaMultiple()`, `props.getPersistenceMinutes()`, `props.getAlignToleranceMs()` and `props.getMatchLeadMinutes()`
- replace the section from "Align predicted -> actual" through `Run best = strongestRun(...)` with:

```java
        List<ResidualPoint> aligned = new ArrayList<>();
        for (CgmReading r : readings) {
            if (r.getSgv() == null || r.getDateTimestamp() == null) continue;
            Double p = nearest(predByMs, r.getDateTimestamp(), props.getAlignToleranceMs());
            if (p == null) continue;
            aligned.add(new ResidualPoint(r.getDateTimestamp(), r.getSgv() / MGDL_PER_MMOL, p));
        }
        ResidualSeries series = ResidualScanner.series(aligned);
        ResidualScanner scanner = new ResidualScanner(props.getSigmaMultiple(), props.getPersistenceMinutes(),
                props.getMaxGapMinutes(), props.getMinReadings());
        Optional<ResidualRun> best = ResidualScanner.strongest(scanner.runs(series));
        if (best.isEmpty()) return Optional.empty();
        return Optional.of(persistFlag(userId, best.get(), series.sigma(), notes, userZone));
```

Change `persistFlag`'s parameter type from the old `Run` to `ResidualRun`, using `run.startMs()`, `run.endMs()` and `run.mean()`. Delete the old `record Run`, `strongestRun` and `robustScale` from the service. In `GlycemicEventScanServiceTest`, delete the three tests of the removed statics; they now live in `ResidualScannerTest`. If that leaves the class empty, delete the file.

- [ ] **Step 7: Run the affected tests**

Run: `./gradlew test --tests '*ResidualScannerTest' --tests '*GlycemicEvent*'`
Expected: PASS, including all 17 legacy E2E cases. The step fixtures stay a single run each.

- [ ] **Step 8: Commit**

```bash
git add -A src/
git commit -m "feat(glycemic): multi-run residual scanner with median smoothing and gap splitting

Adds GlycemicEventProperties; scan cadence 10 min, window 240 min.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: `OnsetLocator`

**Files:**
- Create: `glycemic/OnsetLocator.java`
- Create test: `src/test/java/che/glucosemonitorbe/glycemic/OnsetLocatorTest.java`

**Interfaces:**
- Consumes: `ResidualSeries`, `ResidualRun` (Task 4).
- Produces: `static int OnsetLocator.locate(ResidualSeries s, ResidualRun run, int maxBacktrackMinutes)`, which returns an index into `s`.

- [ ] **Step 1: Write the failing test**

```java
package che.glucosemonitorbe.glycemic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static che.glucosemonitorbe.glycemic.ResidualScannerTest.*;
import static org.assertj.core.api.Assertions.assertThat;

class OnsetLocatorTest {

    private final ResidualScanner scanner = new ResidualScanner(2.0, 45, 15, 24);

    @Test
    void backtracksFromThresholdCrossingToLastNearZeroPoint() {
        // sigma floor 0.3 -> near-zero band 0.15, threshold 0.6. Ramp starts at index 20.
        List<Double> ramp = List.of(0.1, 0.2, 0.4, 0.8, 1.2, 1.6, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0);
        // Noise, not flat zeros, around the ramp: a zero residual counts as positive and would glue the
        // baseline onto the run.
        ResidualSeries s = series(concat(noise(20), ramp, noise(10)));
        ResidualRun run = scanner.runs(s).get(0);
        assertThat(OnsetLocator.locate(s, run, 60)).isEqualTo(20);   // the 0.1 point (smoothed 0.1 < 0.15)
    }

    @Test
    void backtrackIsCappedAtTheLimit() {
        // A very slow ramp: no near-zero point within 15 min of the crossing -> earliest point examined.
        List<Double> slow = List.of(0.2, 0.25, 0.3, 0.35, 0.4, 0.45, 0.5, 0.55, 0.6, 0.7, 0.8, 0.9, 1.0,
                1.5, 1.5, 1.5, 1.5);
        ResidualSeries s = series(concat(noise(20), slow, noise(5)));
        ResidualRun run = scanner.runs(s).get(0);
        int onset = OnsetLocator.locate(s, run, 15);
        assertThat(s.epochMs(run.firstExceed()) - s.epochMs(onset)).isEqualTo(15 * 60_000L);
    }

    @Test
    void backtrackStopsWhereAnOppositeMoveEnds() {
        // a rise immediately followed by a fall: the fall's onset is its own first point, not mid-rise
        ResidualSeries s = series(concat(noise(20), flat(11, 3.0), flat(11, -3.0), noise(10)));
        ResidualRun fall = scanner.runs(s).get(1);
        assertThat(OnsetLocator.locate(s, fall, 60)).isEqualTo(fall.from());
    }

    @Test
    void runAtSeriesStartReturnsFirstIndex() {
        ResidualSeries s = series(concat(flat(12, 3.0), noise(20)));
        ResidualRun run = scanner.runs(s).get(0);
        assertThat(OnsetLocator.locate(s, run, 60)).isZero();
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests '*OnsetLocatorTest'`
Expected: FAIL to compile.

- [ ] **Step 3: Implement**

```java
package che.glucosemonitorbe.glycemic;

/**
 * Finds the inflection point of a run: from the first point past the detection threshold, walk back to
 * the last point whose smoothed residual was within 0.5 sigma of zero - the last moment the model still
 * explained reality. The walk stops where an opposite-sign move ends (a drop straight after a rise has its
 * onset at its own first point, never inside the rise), and is bounded to {@code maxBacktrackMinutes};
 * if no near-zero point exists within those bounds, the earliest point examined is returned.
 */
public final class OnsetLocator {

    private OnsetLocator() {}

    public static int locate(ResidualSeries s, ResidualRun run, int maxBacktrackMinutes) {
        double near = 0.5 * s.sigma();
        int start = run.firstExceed();
        long limit = s.epochMs(start) - maxBacktrackMinutes * 60_000L;
        int earliest = start;
        boolean rising = run.mean() >= 0;
        for (int i = start - 1; i >= 0; i--) {
            if (s.epochMs(i) < limit) break;
            double v = s.smoothed(i);
            if (Math.abs(v) < near) return i;
            if ((v >= 0) != rising) return i + 1;   // crossed into the opposite move
            earliest = i;
        }
        return earliest;
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew test --tests '*OnsetLocatorTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A src/
git commit -m "feat(glycemic): locate event onset at the residual inflection point

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: `FeatureExtractor`

**Files:**
- Create: `glycemic/FeatureExtractor.java`
- Create test: `src/test/java/che/glucosemonitorbe/glycemic/FeatureExtractorTest.java`

**Interfaces:**
- Consumes: `ResidualSeries`, `ResidualRun`, `EventFeatures`; `Note` (`getTimestamp`, `getCarbs`, `getInsulin`, `isLongActing`, `isActivity`); `RapidInsulinIobParameters(diaHours, peakMinutes)`; `InsulinCalculatorService.iobOpenApsExponential(units, minsAgo, diaHours, peakMinutes)`; `UserZones.toWallTime`.
- Produces: `record FeatureExtractor.Input(ResidualSeries series, ResidualRun run, int onsetIndex, ZoneId zone, List<Note> notes, RapidInsulinIobParameters iob, double isfMmolPerU, double cobAtOnsetGrams, int matchLeadMinutes)`; `static EventFeatures extract(Input)`; `static double slopePerMinute(List<Long> epochMs, List<Double> values)`.

- [ ] **Step 1: Write the failing test**

```java
package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class FeatureExtractorTest {

    private static final long STEP = 5 * 60_000L;
    /** 2026-09-30T12:00Z, a Wednesday. */
    private static final long T0 = LocalDateTime.of(2026, 9, 30, 12, 0).toEpochSecond(ZoneOffset.UTC) * 1000;
    private final RapidInsulinIobParameters iob = new RapidInsulinIobParameters(5.0, 75.0);

    /** 20 flat points at 6.0 then 12 points rising 0.2 mmol/L per 5 min; predicted stays 6.0. */
    private ResidualSeries risingSeries() {
        List<ResidualPoint> pts = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            double actual = i < 20 ? 6.0 : 6.0 + 0.2 * (i - 19);
            pts.add(new ResidualPoint(T0 + i * STEP, actual, 6.0));
        }
        return ResidualScanner.series(pts);
    }

    private Note note(LocalDateTime ts, double carbs, double insulin, String type) {
        Note n = new Note();
        n.setTimestamp(ts);
        n.setCarbs(carbs);
        n.setInsulin(insulin);
        n.setType(type);
        return n;
    }

    private EventFeatures extract(List<Note> notes, double cob) {
        ResidualSeries s = risingSeries();
        ResidualRun run = new ResidualScanner(2.0, 45, 15, 24).runs(s).get(0);
        int onset = OnsetLocator.locate(s, run, 60);
        return FeatureExtractor.extract(new FeatureExtractor.Input(
                s, run, onset, ZoneOffset.UTC, notes, iob, 2.0, cob, 45));
    }

    @Test
    void expectedDrop60MatchesHandComputedIobCurve() {
        LocalDateTime onsetGuess = LocalDateTime.of(2026, 9, 30, 13, 35);
        EventFeatures f = extract(List.of(note(onsetGuess.minusMinutes(60), 0, 4.0, Note.TYPE_NORMAL)), 0);
        double minsAgo = f.minSinceBolus();
        double now = InsulinCalculatorService.iobOpenApsExponential(4.0, minsAgo, 5.0, 75.0);
        double later = InsulinCalculatorService.iobOpenApsExponential(4.0, minsAgo + 60, 5.0, 75.0);
        assertThat(f.iobAtOnset()).isCloseTo(now, within(1e-9));
        assertThat(f.expectedDrop60()).isCloseTo(2.0 * (now - later), within(1e-9));
        assertThat(f.expectedDrop60()).isGreaterThan(1.5);
    }

    @Test
    void longActingInsulinIsNotBolusIob() {
        EventFeatures f = extract(List.of(note(LocalDateTime.of(2026, 9, 30, 12, 30), 0, 20.0, Note.TYPE_LONG_ACTING)), 0);
        assertThat(f.iobAtOnset()).isZero();
        assertThat(f.minSinceBolus()).isNull();
        assertThat(f.insulinLogged()).isFalse();
    }

    @Test
    void minutesSinceAreNullWithoutPriorNotes() {
        EventFeatures f = extract(List.of(), 0);
        assertThat(f.minSinceCarbs()).isNull();
        assertThat(f.minSinceBolus()).isNull();
        assertThat(f.carbsLogged()).isFalse();
    }

    @Test
    void carbsInsideMatchWindowAreLogged() {
        EventFeatures f = extract(List.of(note(LocalDateTime.of(2026, 9, 30, 13, 20), 30, 0, Note.TYPE_NORMAL)), 12.0);
        assertThat(f.carbsLogged()).isTrue();
        assertThat(f.cobAtOnset()).isEqualTo(12.0);
    }

    @Test
    void activityWithin30MinutesOfOnsetIsLogged() {
        EventFeatures f = extract(List.of(note(LocalDateTime.of(2026, 9, 30, 13, 20), 0, 0, Note.TYPE_ACTIVITY)), 0);
        assertThat(f.activityLogged()).isTrue();
    }

    @Test
    void timingAndShape() {
        EventFeatures f = extract(List.of(), 0);
        assertThat(f.onsetHour()).isBetween(13.0, 14.0);
        assertThat(f.weekend()).isFalse();
        assertThat(f.actualSlope()).isCloseTo(0.04, within(0.01));     // 0.2 mmol/L per 5 min
        assertThat(f.predictedSlope()).isCloseTo(0.0, within(1e-9));
        assertThat(f.sigmaMultiple()).isGreaterThan(2.0);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests '*FeatureExtractorTest'`
Expected: FAIL to compile.

- [ ] **Step 3: Implement**

```java
package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.domain.UserZones;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.service.InsulinCalculatorService;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the {@link EventFeatures} vector for a run at its onset (spec sections 1.3, 2.3). Pure: uses only
 * the scan's already-loaded notes and residual series. "Significant IOB" is derived from
 * {@code expectedDrop60 = ISF x (IOB(onset) - IOB(onset + 60 min))} over logged rapid boluses.
 */
public final class FeatureExtractor {

    private static final int SLOPE_WINDOW_MIN = 30;
    private static final int ACTIVITY_WINDOW_MIN = 30;

    public record Input(ResidualSeries series, ResidualRun run, int onsetIndex, ZoneId zone,
                        List<Note> notes, RapidInsulinIobParameters iob, double isfMmolPerU,
                        double cobAtOnsetGrams, int matchLeadMinutes) {}

    private FeatureExtractor() {}

    public static EventFeatures extract(Input in) {
        ResidualSeries s = in.series();
        ResidualRun run = in.run();
        long onsetMs = s.epochMs(in.onsetIndex());
        LocalDateTime onset = UserZones.toWallTime(onsetMs, in.zone());
        LocalDateTime windowEnd = UserZones.toWallTime(run.endMs(), in.zone());
        LocalDateTime matchFrom = onset.minusMinutes(in.matchLeadMinutes());

        double iobAtOnset = 0.0, iobDrop = 0.0;
        Double minSinceCarbs = null, minSinceBolus = null;
        boolean carbsLogged = false, insulinLogged = false, activity = false;
        for (Note n : in.notes()) {
            LocalDateTime t = n.getTimestamp();
            if (t == null) continue;
            double minsAgo = Duration.between(t, onset).toSeconds() / 60.0;
            boolean inMatch = !t.isBefore(matchFrom) && !t.isAfter(windowEnd);
            if (n.isActivity()) {
                if (Math.abs(minsAgo) <= ACTIVITY_WINDOW_MIN) activity = true;
                continue;
            }
            if (n.getCarbs() != null && n.getCarbs() > 0) {
                if (inMatch) carbsLogged = true;
                if (minsAgo >= 0) minSinceCarbs = minSinceCarbs == null ? minsAgo : Math.min(minSinceCarbs, minsAgo);
            }
            if (!n.isLongActing() && n.getInsulin() != null && n.getInsulin() > 0) {
                if (inMatch) insulinLogged = true;
                if (minsAgo >= 0) {
                    minSinceBolus = minSinceBolus == null ? minsAgo : Math.min(minSinceBolus, minsAgo);
                    double now = InsulinCalculatorService.iobOpenApsExponential(
                            n.getInsulin(), minsAgo, in.iob().diaHours(), in.iob().peakMinutes());
                    double later = InsulinCalculatorService.iobOpenApsExponential(
                            n.getInsulin(), minsAgo + 60, in.iob().diaHours(), in.iob().peakMinutes());
                    iobAtOnset += now;
                    iobDrop += now - later;
                }
            }
        }

        List<Long> tRun = new ArrayList<>();
        List<Double> rRun = new ArrayList<>();
        for (int i = run.from(); i <= run.to(); i++) { tRun.add(s.epochMs(i)); rRun.add(s.smoothed(i)); }

        List<Long> tWin = new ArrayList<>();
        List<Double> actual = new ArrayList<>(), predicted = new ArrayList<>();
        for (int i = in.onsetIndex(); i < s.size() && s.epochMs(i) <= onsetMs + SLOPE_WINDOW_MIN * 60_000L; i++) {
            tWin.add(s.epochMs(i));
            actual.add(s.points().get(i).actualMmol());
            predicted.add(s.points().get(i).predictedMmol());
        }

        DayOfWeek dow = onset.getDayOfWeek();
        return new EventFeatures(
                EventFeatures.VERSION,
                onset.getHour() + onset.getMinute() / 60.0,
                dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY,
                run.durationMinutes(),
                run.mean(),
                run.peak(),
                slopePerMinute(tRun, rRun),
                slopePerMinute(tWin, actual),
                slopePerMinute(tWin, predicted),
                s.points().get(in.onsetIndex()).actualMmol(),
                iobAtOnset,
                in.isfMmolPerU() * iobDrop,
                in.cobAtOnsetGrams(),
                minSinceCarbs,
                minSinceBolus,
                activity,
                carbsLogged,
                insulinLogged,
                s.sigma() > 0 ? Math.abs(run.mean()) / s.sigma() : 0.0,
                null, null, null, null);
    }

    /** Least-squares slope in value units per minute; 0 with fewer than two points. */
    public static double slopePerMinute(List<Long> epochMs, List<Double> values) {
        int n = epochMs.size();
        if (n < 2) return 0.0;
        double mx = 0, my = 0;
        for (int i = 0; i < n; i++) { mx += epochMs.get(i) / 60_000.0; my += values.get(i); }
        mx /= n; my /= n;
        double sxy = 0, sxx = 0;
        for (int i = 0; i < n; i++) {
            double dx = epochMs.get(i) / 60_000.0 - mx;
            sxy += dx * (values.get(i) - my);
            sxx += dx * dx;
        }
        return sxx == 0 ? 0.0 : sxy / sxx;
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew test --tests '*FeatureExtractorTest'`
Expected: PASS. If `expectedDrop60MatchesHandComputedIobCurve` fails only on `isGreaterThan(1.5)`, raise the fixture bolus from 4.0 to 5.0 U. Its purpose is to prove the formula, and the other assertions pin it exactly.

- [ ] **Step 5: Commit**

```bash
git add -A src/
git commit -m "feat(glycemic): extract the event feature vector at onset

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: `EventClassifier` — the ordered rule matrix

**Files:**
- Create: `glycemic/EventClassifier.java`
- Create test: `src/test/java/che/glucosemonitorbe/glycemic/EventClassifierTest.java`

**Interfaces:**
- Consumes: `EventFeatures`, `Category`, `Direction`, `Phase`; test fixture `EventFeaturesBuilder`.
- Produces: `record EventClassifier.Config(double significantIobDropMmol, double cobZeroGrams, int dawnStartHour, int dawnEndHour, double dawnMaxSlope, int resistanceResolveMinutes, int dropResolveMinutes)`; `record EventClassifier.Classification(Category category, Phase phase, Integer resolveAfterMinutes, double baseConfidence, String reason)`; `Classification classify(Direction, EventFeatures)`; `static double confidence(double base, double sigmaMultiple)`; `static boolean isLoggedContextRule(String reason)` (true for `rule:R1`, `rule:R2`, `rule:F1`, `rule:F3`).

- [ ] **Step 1: Write the failing test**

```java
package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.entity.GlycemicEvent.Category;
import che.glucosemonitorbe.entity.GlycemicEvent.Direction;
import che.glucosemonitorbe.entity.GlycemicEvent.Phase;
import che.glucosemonitorbe.glycemic.EventClassifier.Classification;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EventClassifierTest {

    private final EventClassifier classifier = new EventClassifier(
            new EventClassifier.Config(1.5, 5.0, 3, 8, 0.03, 360, 120));

    private Classification rise(EventFeaturesBuilder b) { return classifier.classify(Direction.RISE, b.build()); }
    private Classification fall(EventFeaturesBuilder b) { return classifier.classify(Direction.FALL, b.build()); }

    @Test void r1_carbsLogged() {
        Classification c = rise(new EventFeaturesBuilder().carbsLogged(true));
        assertThat(c.category()).isEqualTo(Category.UNDER_ESTIMATED_FOOD);
        assertThat(c.reason()).isEqualTo("rule:R1");
        assertThat(c.baseConfidence()).isEqualTo(0.8);
    }
    @Test void r1_earlierMealStillAbsorbing() {
        assertThat(rise(new EventFeaturesBuilder().cobAtOnset(5.0)).category()).isEqualTo(Category.UNDER_ESTIMATED_FOOD);
    }
    @Test void r2_activityLogged() {
        assertThat(rise(new EventFeaturesBuilder().activityLogged(true)).category()).isEqualTo(Category.ANAEROBIC_ACTIVITY);
    }
    @Test void r3_gentleEarlyMorningRise() {
        Classification c = rise(new EventFeaturesBuilder().onsetHour(4.5).actualSlope(0.02));
        assertThat(c.category()).isEqualTo(Category.DAWN_PHENOMENON);
        assertThat(c.phase()).isEqualTo(Phase.FINAL);
    }
    @Test void r3_boundaries() {
        assertThat(rise(new EventFeaturesBuilder().onsetHour(3.0).actualSlope(0.03)).category()).isEqualTo(Category.DAWN_PHENOMENON);
        assertThat(rise(new EventFeaturesBuilder().onsetHour(8.0).actualSlope(0.02)).category()).isEqualTo(Category.UNLOGGED_FOOD);
        assertThat(rise(new EventFeaturesBuilder().onsetHour(4.0).actualSlope(0.031)).category()).isEqualTo(Category.UNLOGGED_FOOD);
        assertThat(rise(new EventFeaturesBuilder().onsetHour(4.0).actualSlope(0.0)).category()).isEqualTo(Category.UNLOGGED_FOOD);
    }
    @Test void r3_stepJumpAtDawnIsNotDawn() {
        // a step then plateau: residual slope ~0, but the actual slope right after onset is steep
        assertThat(rise(new EventFeaturesBuilder().onsetHour(5.0).residualSlope(0.0).actualSlope(0.17)).category())
                .isEqualTo(Category.UNLOGGED_FOOD);
    }
    @Test void r4_significantIobIsProvisionalStress() {
        Classification c = rise(new EventFeaturesBuilder().expectedDrop60(1.5));
        assertThat(c.category()).isEqualTo(Category.STRESS);
        assertThat(c.phase()).isEqualTo(Phase.PROVISIONAL);
        assertThat(c.resolveAfterMinutes()).isEqualTo(360);
        assertThat(c.baseConfidence()).isEqualTo(0.5);
    }
    @Test void r4_justBelowThresholdIsFood() {
        assertThat(rise(new EventFeaturesBuilder().expectedDrop60(1.49)).category()).isEqualTo(Category.UNLOGGED_FOOD);
    }
    @Test void r5_nothingExplainsIt() {
        Classification c = rise(new EventFeaturesBuilder());
        assertThat(c.category()).isEqualTo(Category.UNLOGGED_FOOD);
        assertThat(c.phase()).isEqualTo(Phase.FINAL);
        assertThat(c.baseConfidence()).isEqualTo(0.6);
    }
    @Test void ordering_loggedCarbsBeatDawnAndActivityBeatsStress() {
        assertThat(rise(new EventFeaturesBuilder().carbsLogged(true).onsetHour(4.0).actualSlope(0.02)).category())
                .isEqualTo(Category.UNDER_ESTIMATED_FOOD);
        assertThat(rise(new EventFeaturesBuilder().activityLogged(true).expectedDrop60(3.0)).category())
                .isEqualTo(Category.ANAEROBIC_ACTIVITY);
    }
    @Test void f1_insulinLoggedOrActive() {
        assertThat(fall(new EventFeaturesBuilder().insulinLogged(true)).category()).isEqualTo(Category.UNDER_ESTIMATED_INSULIN);
        assertThat(fall(new EventFeaturesBuilder().expectedDrop60(2.0)).category()).isEqualTo(Category.UNDER_ESTIMATED_INSULIN);
    }
    @Test void f3_activityLogged() {
        assertThat(fall(new EventFeaturesBuilder().activityLogged(true)).category()).isEqualTo(Category.AEROBIC_ACTIVITY);
    }
    @Test void f4_unexplainedDropIsProvisional() {
        Classification c = fall(new EventFeaturesBuilder());
        assertThat(c.category()).isEqualTo(Category.UNLOGGED_INSULIN);
        assertThat(c.phase()).isEqualTo(Phase.PROVISIONAL);
        assertThat(c.resolveAfterMinutes()).isEqualTo(120);
    }
    @Test void confidenceScalesWithSignalStrength() {
        assertThat(EventClassifier.confidence(0.8, 4.0)).isCloseTo(0.8, within(1e-9));
        assertThat(EventClassifier.confidence(0.8, 16.0)).isCloseTo(0.8, within(1e-9));
        assertThat(EventClassifier.confidence(0.8, 1.0)).isCloseTo(0.4, within(1e-9));
    }
    @Test void loggedContextRules() {
        assertThat(EventClassifier.isLoggedContextRule("rule:R1")).isTrue();
        assertThat(EventClassifier.isLoggedContextRule("rule:F3")).isTrue();
        assertThat(EventClassifier.isLoggedContextRule("rule:R5")).isFalse();
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests '*EventClassifierTest'`
Expected: FAIL to compile.

- [ ] **Step 3: Implement**

```java
package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.entity.GlycemicEvent.Category;
import che.glucosemonitorbe.entity.GlycemicEvent.Direction;
import che.glucosemonitorbe.entity.GlycemicEvent.Phase;

import java.util.Set;

/**
 * The ordered IOB/COB/slope rule matrix (spec section 3.1). First matching rule wins. Pure and
 * deterministic; the learner (EventLabelLearner) may adjust the result afterwards.
 */
public final class EventClassifier {

    public record Config(double significantIobDropMmol, double cobZeroGrams, int dawnStartHour, int dawnEndHour,
                         double dawnMaxSlope, int resistanceResolveMinutes, int dropResolveMinutes) {}

    public record Classification(Category category, Phase phase, Integer resolveAfterMinutes,
                                 double baseConfidence, String reason) {}

    private static final Set<String> LOGGED_CONTEXT = Set.of("rule:R1", "rule:R2", "rule:F1", "rule:F3");

    private final Config c;

    public EventClassifier(Config config) { this.c = config; }

    public Classification classify(Direction d, EventFeatures f) {
        boolean sigIob = f.expectedDrop60() >= c.significantIobDropMmol();
        if (d == Direction.RISE) {
            if (f.carbsLogged() || f.cobAtOnset() >= c.cobZeroGrams()) {
                return fin(Category.UNDER_ESTIMATED_FOOD, 0.8, "rule:R1");
            }
            if (f.activityLogged()) return fin(Category.ANAEROBIC_ACTIVITY, 0.8, "rule:R2");
            boolean dawnHour = f.onsetHour() >= c.dawnStartHour() && f.onsetHour() < c.dawnEndHour();
            if (dawnHour && !sigIob && f.actualSlope() > 0 && f.actualSlope() <= c.dawnMaxSlope()) {
                return fin(Category.DAWN_PHENOMENON, 0.6, "rule:R3");
            }
            if (sigIob) {
                return new Classification(Category.STRESS, Phase.PROVISIONAL, c.resistanceResolveMinutes(), 0.5, "rule:R4");
            }
            return fin(Category.UNLOGGED_FOOD, 0.6, "rule:R5");
        }
        if (f.insulinLogged() || sigIob) return fin(Category.UNDER_ESTIMATED_INSULIN, 0.8, "rule:F1");
        if (f.activityLogged()) return fin(Category.AEROBIC_ACTIVITY, 0.8, "rule:F3");
        return new Classification(Category.UNLOGGED_INSULIN, Phase.PROVISIONAL, c.dropResolveMinutes(), 0.5, "rule:F4");
    }

    /** Base prior scaled by signal strength: base x clamp(sqrt(sigmaMultiple / 4), 0.5, 1.0). */
    public static double confidence(double base, double sigmaMultiple) {
        return base * Math.min(1.0, Math.max(0.5, Math.sqrt(sigmaMultiple / 4.0)));
    }

    /** Rules driven by something the user logged - a later log may reclassify an existing event. */
    public static boolean isLoggedContextRule(String reason) { return LOGGED_CONTEXT.contains(reason); }

    private static Classification fin(Category cat, double base, String reason) {
        return new Classification(cat, Phase.FINAL, null, base, reason);
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew test --tests '*EventClassifierTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A src/
git commit -m "feat(glycemic): ordered IOB/COB/slope rule matrix

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: `GlycemicEventDetector` and the multi-event scan pipeline

**Files:**
- Create: `glycemic/GlycemicEventDetector.java`
- Modify: `service/GlycemicEventScanService.java` (becomes the thin DB shell: load, detect, classify, persist)
- Modify test: `service/RescueMarkerPropagationTest.java` (`toCarbsEntry` moves to the detector)
- Create test: `src/test/java/che/glucosemonitorbe/integration/AbstractGlycemicEventE2ETest.java`, `src/test/java/che/glucosemonitorbe/integration/GlycemicEventPipelineE2ETest.java`

**Interfaces:**
- Consumes: `ResidualScanner`, `OnsetLocator`, `FeatureExtractor`, `EventClassifier`, `GlycemicEventProperties`, `GlycemicEventHistoryRecorder`, repository finders (Tasks 3–7); `HovorkaGlucosePredictionService.buildPredictionPath(HovorkaParameters, RapidInsulinIobParameters, UserSettingsDTO, double g0, LocalDateTime anchor, List<CarbsEntry>, List<InsulinDose>, List<Note> longActing, UUID userId, int minutes, ActivityProvider)`; `CarbsOnBoardService.calculateTotalCarbsOnBoard(List<CarbsEntry>, LocalDateTime, UserSettingsDTO)`.
- Produces:
  - `GlycemicEventDetector(HovorkaGlucosePredictionService rawPredictor, GlycemicEventProperties props)`.
  - Records `CgmPoint(long epochMs, double mmol)`, `Input(UUID userId, ZoneId zone, LocalDateTime end, List<CgmPoint> cgm, List<Note> notes, HovorkaParameters params, RapidInsulinIobParameters iob, UserSettingsDTO settings, ToDoubleFunction<LocalDateTime> cobAt, boolean activityAware)` and `Detection(ResidualRun run, int onsetIndex, long onsetMs, EventFeatures features, ResidualSeries series)` with `Direction direction()`.
  - Methods `List<Detection> detect(Input)`, `List<PredictionPointDTO> predict(Input, LocalDateTime anchor, double g0, int minutes, Predicate<Note> dropBolus)`, `static List<ResidualPoint> align(List<PredictionPointDTO>, List<CgmPoint>, ZoneId, long tolMs)` and `static CarbsEntry toCarbsEntry(Note)`.
  - `GlycemicEventScanService`:
    - `List<GlycemicEvent> scanUserEvents(UUID)`
    - `Optional<GlycemicEvent> scanUser(UUID)`, which returns the event with the largest |meanResidual|
    - package-private `EventClassifier.Config classifierConfig(RapidInsulinIobParameters)`
    - package-private `GlycemicEvent persist(UUID userId, GlycemicEventDetector.Detection d, EventClassifier.Classification c, double confidence, boolean suppressed, String reason, List<GlycemicEvent> open, ZoneId zone)`
  - `AbstractGlycemicEventE2ETest` helpers: `registerAndLogin()`, `userId()`, `seed(UUID userId, long startMs, int... sgvMgdl)`, `note(UUID, LocalDateTime, double carbs, double insulin, String type)`, `toLdt(long)`, `nowMs()`, `STEP`.

- [ ] **Step 1: Create the shared E2E base (singleton container, helpers)**

`src/test/java/che/glucosemonitorbe/integration/AbstractGlycemicEventE2ETest.java`:

```java
package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.dto.AuthRequest;
import che.glucosemonitorbe.dto.AuthResponse;
import che.glucosemonitorbe.dto.RegisterRequest;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.*;
import che.glucosemonitorbe.service.GlycemicEventScanService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Shared fixture for the glycemic-event E2E suites. One Postgres container for the whole JVM (singleton
 * pattern) so every subclass shares one cached Spring context. Users default to UTC, so
 * {@link #toLdt(long)} converts CGM epochs to the same wall clock the engine uses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "app.features.glycemic-event-detection-enabled=true",
    "app.features.digital-twin-enabled=true",
    "app.features.activity-logging-enabled=true",
    "app.glucose-sync.enabled=false",
    "app.glycemic-events.initial-delay-ms=3600000"   // keep the scheduler out of the tests
})
@SuppressWarnings({"resource", "null"})
abstract class AbstractGlycemicEventE2ETest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("testdb").withUsername("test").withPassword("test");

    static { POSTGRES.start(); }

    protected static final long STEP = 5 * 60 * 1000L;

    @Autowired protected TestRestTemplate rest;
    @Autowired protected UserRepository userRepository;
    @Autowired protected CgmReadingRepository cgmReadingRepository;
    @Autowired protected NoteRepository noteRepository;
    @Autowired protected GlycemicEventRepository eventRepository;
    @Autowired protected GlycemicEventHistoryRepository historyRepository;
    @Autowired protected GlycemicEventScanService scanService;
    protected final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    protected HttpHeaders authHeaders;

    @BeforeEach
    void resetUsers() {
        userRepository.deleteAll();
        authHeaders = registerAndLogin();
    }

    protected static long nowMs() { return Instant.now().toEpochMilli(); }

    protected static LocalDateTime toLdt(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC);
    }

    protected static long ms(LocalDateTime ldt) { return ldt.toInstant(ZoneOffset.UTC).toEpochMilli(); }

    protected static int[] repeat(int n, int v) {
        int[] a = new int[n];
        java.util.Arrays.fill(a, v);
        return a;
    }

    protected static int[] concat(int[]... parts) {
        return java.util.Arrays.stream(parts).flatMapToInt(java.util.Arrays::stream).toArray();
    }

    /** One reading every 5 minutes from {@code startMs}; reading i is at {@code startMs + i * STEP}. */
    protected void seed(UUID userId, long startMs, int... sgvMgdl) {
        List<CgmReading> batch = new ArrayList<>();
        for (int i = 0; i < sgvMgdl.length; i++) {
            batch.add(CgmReading.builder().userId(userId).dataSource(CgmReading.DataSource.NIGHTSCOUT)
                    .externalId("g" + startMs + "_" + i).sgv(sgvMgdl[i]).dateTimestamp(startMs + i * STEP)
                    .lastUpdated(LocalDateTime.now()).build());
        }
        cgmReadingRepository.saveAll(batch);
    }

    protected Note note(UUID userId, LocalDateTime ts, double carbs, double insulin, String type) {
        Note n = new Note();
        n.setUserId(userId);
        n.setTimestamp(ts);
        n.setCarbs(carbs);
        n.setInsulin(insulin);
        n.setMeal("test");
        n.setType(type);
        n.setCreatedAt(LocalDateTime.now());
        n.setUpdatedAt(LocalDateTime.now());
        return noteRepository.save(n);
    }

    protected UUID userId() { return userRepository.findAll().get(0).getId(); }

    protected HttpHeaders registerAndLogin() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest reg = new RegisterRequest();
        reg.setUsername("gly_" + suffix);
        reg.setEmail("gly+" + suffix + "@example.com");
        reg.setFullName("Glycemic User");
        reg.setPassword("testpass123");
        rest.postForEntity("/api/auth/register", jsonEntity(reg), String.class);
        AuthRequest login = new AuthRequest();
        login.setUsername(reg.getUsername());
        login.setPassword(reg.getPassword());
        ResponseEntity<AuthResponse> resp = rest.postForEntity("/api/auth/login", jsonEntity(login), AuthResponse.class);
        assertNotNull(resp.getBody());
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(resp.getBody().getAccessToken());
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    protected <T> HttpEntity<T> jsonEntity(T body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }
}
```

- [ ] **Step 2: Write the failing pipeline E2E tests**

`src/test/java/che/glucosemonitorbe/integration/GlycemicEventPipelineE2ETest.java`:

```java
package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.*;
import che.glucosemonitorbe.entity.GlycemicEventHistory;
import che.glucosemonitorbe.entity.Note;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GlycemicEventPipelineE2ETest extends AbstractGlycemicEventE2ETest {

    private long seedStepRise(UUID u) {
        long start = nowMs() - 35 * STEP;
        seed(u, start, concat(repeat(24, 110), repeat(12, 200)));
        return start + 24 * STEP;
    }

    @Test
    @DisplayName("onset is marked where the ramp starts, not where it crosses the threshold")
    void onsetAtRampStart() {
        UUID u = userId();
        long start = nowMs() - 35 * STEP;
        seed(u, start, concat(repeat(24, 110), new int[]{120, 130, 140, 150, 160, 170, 180, 190, 200, 210, 220, 230}));
        GlycemicEvent e = scanService.scanUser(u).orElseThrow();
        assertThat(e.getCategory()).isEqualTo(Category.UNLOGGED_FOOD);
        assertThat(Math.abs(ms(e.getOnsetAt()) - (start + 24 * STEP))).isLessThanOrEqualTo(15 * 60_000L);
    }

    @Test
    @DisplayName("a rise and a later separate fall become two events")
    void twoRunsTwoEvents() {
        UUID u = userId();
        seed(u, nowMs() - 47 * STEP, concat(repeat(14, 150), repeat(11, 230), repeat(12, 150), repeat(11, 70)));
        List<GlycemicEvent> events = scanService.scanUserEvents(u);
        assertThat(events).extracting(GlycemicEvent::getDirection).containsExactly(Direction.RISE, Direction.FALL);
    }

    @Test
    @DisplayName("a window the user already answered is not re-created as a new OPEN event")
    void answeredWindowNotRecreated() {
        UUID u = userId();
        seedStepRise(u);
        GlycemicEvent e = scanService.scanUser(u).orElseThrow();
        e.setState(State.DISMISSED);
        eventRepository.save(e);

        assertThat(scanService.scanUserEvents(u)).isEmpty();
        assertThat(eventRepository.findByUserIdAndStateIn(u, List.of(State.OPEN))).isEmpty();
    }

    @Test
    @DisplayName("a rise under significant active insulin is provisional Resistance (STRESS)")
    void riseUnderActiveInsulinIsProvisionalStress() {
        UUID u = userId();
        long riseAt = seedStepRise(u);
        note(u, toLdt(riseAt - 30 * 60_000L), 0, 5.0, Note.TYPE_NORMAL);
        GlycemicEvent e = scanService.scanUserEvents(u).stream()
                .filter(x -> x.getDirection() == Direction.RISE).findFirst().orElseThrow();
        assertThat(e.getCategory()).isEqualTo(Category.STRESS);
        assertThat(e.getPhase()).isEqualTo(Phase.PROVISIONAL);
        assertThat(e.getResolvesAt()).isAfter(e.getOnsetAt());
        assertThat(e.getFeatures().expectedDrop60()).isGreaterThanOrEqualTo(1.5);
    }

    @Test
    @DisplayName("detection writes one DETECTED history row with the rule that fired")
    void historyOnDetection() {
        UUID u = userId();
        seedStepRise(u);
        GlycemicEvent e = scanService.scanUser(u).orElseThrow();
        List<GlycemicEventHistory> h = historyRepository.findByEventIdOrderByAtAsc(e.getId());
        assertThat(h).singleElement().satisfies(r -> {
            assertThat(r.getKind()).isEqualTo(GlycemicEventHistory.Kind.DETECTED);
            assertThat(r.getReason()).isEqualTo("rule:R5");
        });
    }

    @Test
    @DisplayName("logging carbs afterwards reclassifies the same event and records UPDATED")
    void laterLogReclassifies() {
        UUID u = userId();
        long riseAt = seedStepRise(u);
        GlycemicEvent first = scanService.scanUser(u).orElseThrow();
        note(u, toLdt(riseAt), 20.0, 0, Note.TYPE_NORMAL);

        GlycemicEvent again = scanService.scanUser(u).orElseThrow();
        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(again.getCategory()).isEqualTo(Category.UNDER_ESTIMATED_FOOD);
        assertThat(historyRepository.findByEventIdOrderByAtAsc(first.getId()))
                .extracting(GlycemicEventHistory::getKind)
                .containsExactly(GlycemicEventHistory.Kind.DETECTED, GlycemicEventHistory.Kind.UPDATED);
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `./gradlew test --tests '*GlycemicEventPipelineE2ETest'`
Expected: FAIL to compile (`scanUserEvents` is missing).

- [ ] **Step 4: Create the detector and its Spring wiring**

`src/main/java/che/glucosemonitorbe/glycemic/GlycemicEventDetector.java`:

```java
package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.config.GlycemicEventProperties;
import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.domain.RescueCarbProfile;
import che.glucosemonitorbe.domain.UserZones;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.GlycemicEvent.Direction;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.hovorka.ActivityProvider;
import che.glucosemonitorbe.hovorka.HovorkaGlucosePredictionService;
import che.glucosemonitorbe.hovorka.HovorkaParameters;
import che.glucosemonitorbe.hovorka.NotesActivityProvider;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * In-memory detection pipeline (spec section 2): raw Hovorka forecast anchored at the window start ->
 * aligned residuals -> every sustained run -> onset -> feature vector. No persistence and no Spring, so
 * the offline validation harness can drive it with dataset data.
 */
public final class GlycemicEventDetector {

    public record CgmPoint(long epochMs, double mmol) {}

    /** Everything one user's scan needs. {@code end} is the user's local "now". */
    public record Input(UUID userId, ZoneId zone, LocalDateTime end, List<CgmPoint> cgm, List<Note> notes,
                        HovorkaParameters params, RapidInsulinIobParameters iob, UserSettingsDTO settings,
                        ToDoubleFunction<LocalDateTime> cobAt, boolean activityAware) {}

    public record Detection(ResidualRun run, int onsetIndex, long onsetMs, EventFeatures features,
                            ResidualSeries series) {
        public Direction direction() { return run.direction(); }
    }

    private final HovorkaGlucosePredictionService rawPredictor;
    private final GlycemicEventProperties props;

    public GlycemicEventDetector(HovorkaGlucosePredictionService rawPredictor, GlycemicEventProperties props) {
        this.rawPredictor = rawPredictor;
        this.props = props;
    }

    public List<Detection> detect(Input in) {
        long endMs = UserZones.toEpochMs(in.end(), in.zone());
        long startMs = endMs - props.getWindowMinutes() * 60_000L;
        List<CgmPoint> window = in.cgm().stream()
                .filter(p -> p.epochMs() >= startMs && p.epochMs() <= endMs)
                .sorted(Comparator.comparingLong(CgmPoint::epochMs))
                .toList();
        if (window.size() < props.getMinReadings()) return List.of();

        CgmPoint first = window.get(0);
        LocalDateTime anchor = UserZones.toWallTime(first.epochMs(), in.zone());
        List<PredictionPointDTO> predicted = predict(in, anchor, first.mmol(), props.getWindowMinutes(), n -> false);
        List<ResidualPoint> aligned = align(predicted, window, in.zone(), props.getAlignToleranceMs());
        if (aligned.size() < props.getMinReadings()) return List.of();

        ResidualSeries series = ResidualScanner.series(aligned);
        ResidualScanner scanner = new ResidualScanner(props.getSigmaMultiple(), props.getPersistenceMinutes(),
                props.getMaxGapMinutes(), props.getMinReadings());
        List<Detection> out = new ArrayList<>();
        for (ResidualRun run : scanner.runs(series)) {
            int onsetIdx = OnsetLocator.locate(series, run, props.getOnsetMaxBacktrackMinutes());
            long onsetMs = series.epochMs(onsetIdx);
            LocalDateTime onset = UserZones.toWallTime(onsetMs, in.zone());
            EventFeatures f = FeatureExtractor.extract(new FeatureExtractor.Input(series, run, onsetIdx, in.zone(),
                    in.notes(), in.iob(), in.params().isf(), in.cobAt().applyAsDouble(onset),
                    props.getMatchLeadMinutes()));
            out.add(new Detection(run, onsetIdx, onsetMs, f, series));
        }
        return out;
    }

    /**
     * Raw forecast from {@code anchor}. Boluses matching {@code dropBolus} are left out - the tail
     * resolver's no-insulin counterfactual.
     */
    public List<PredictionPointDTO> predict(Input in, LocalDateTime anchor, double g0, int minutes,
                                            Predicate<Note> dropBolus) {
        List<CarbsEntry> carbs = new ArrayList<>();
        List<InsulinDose> insulin = new ArrayList<>();
        List<Note> longActing = new ArrayList<>();
        for (Note n : in.notes()) {
            if (n.isLongActing()) { longActing.add(n); continue; }
            if (n.getCarbs() != null && n.getCarbs() > 0) carbs.add(toCarbsEntry(n));
            if (n.getInsulin() != null && n.getInsulin() > 0 && !dropBolus.test(n)) {
                insulin.add(InsulinDose.builder().timestamp(n.getTimestamp())
                        .units(n.getInsulin()).type(InsulinDose.InsulinType.BOLUS).build());
            }
        }
        // Activity-aware so a drop from a logged activity is explained, not flagged. Inert when logging is off.
        ActivityProvider activity = in.activityAware() ? NotesActivityProvider.fromNotes(in.notes()) : ActivityProvider.NONE;
        return rawPredictor.buildPredictionPath(in.params(), in.iob(), in.settings(), g0, anchor,
                carbs, insulin, longActing, in.userId(), minutes, activity);
    }

    /** Pairs each CGM point with the nearest prediction within {@code tolMs}; unmatched points are dropped. */
    public static List<ResidualPoint> align(List<PredictionPointDTO> predicted, List<CgmPoint> cgm,
                                            ZoneId zone, long tolMs) {
        TreeMap<Long, Double> byMs = new TreeMap<>();
        for (PredictionPointDTO p : predicted) {
            if (p.getPredictedGlucose() != null) byMs.put(UserZones.toEpochMs(p.getTimestamp(), zone), p.getPredictedGlucose());
        }
        List<ResidualPoint> out = new ArrayList<>();
        for (CgmPoint c : cgm) {
            Double p = nearest(byMs, c.epochMs(), tolMs);
            if (p != null) out.add(new ResidualPoint(c.epochMs(), c.mmol(), p));
        }
        return out;
    }

    /**
     * Note -> carb input for the forecast. A {@code hypo_treatment} note carries the rescue marker via
     * {@link RescueCarbProfile#mark}; left unmarked, a 15 g dextrose tablet would absorb on the 240-minute
     * mixed-meal curve, under-predict the recovery, and flag a logged hypo recovery as unlogged food.
     */
    public static CarbsEntry toCarbsEntry(Note n) {
        CarbsEntry entry = CarbsEntry.builder().timestamp(n.getTimestamp()).carbs(n.getCarbs()).build();
        return n.isHypoTreatment() ? RescueCarbProfile.mark(entry) : entry;
    }

    private static Double nearest(TreeMap<Long, Double> map, long target, long tolMs) {
        Map.Entry<Long, Double> floor = map.floorEntry(target), ceil = map.ceilingEntry(target);
        Map.Entry<Long, Double> best = null;
        long bestDiff = Long.MAX_VALUE;
        if (floor != null) { best = floor; bestDiff = target - floor.getKey(); }
        if (ceil != null && ceil.getKey() - target < bestDiff) { best = ceil; bestDiff = ceil.getKey() - target; }
        return best != null && bestDiff <= tolMs ? best.getValue() : null;
    }
}
```

`src/main/java/che/glucosemonitorbe/config/GlycemicEngineConfig.java`:

```java
package che.glucosemonitorbe.config;

import che.glucosemonitorbe.glycemic.GlycemicEventDetector;
import che.glucosemonitorbe.hovorka.*;
import che.glucosemonitorbe.hovorka.learning.PredictionResidualProvider;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import che.glucosemonitorbe.service.UserSettingsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the pure detector with a raw predictor: no twin overlay, no residual correction. */
@Configuration
public class GlycemicEngineConfig {

    @Bean
    public GlycemicEventDetector glycemicEventDetector(HovorkaParameterService paramService, HovorkaOdeSolver odeSolver,
                                                       BasalInsulinResolver basalResolver,
                                                       UserInsulinPreferencesService insulinPrefsService,
                                                       DallaManGutModel gutModel, UserSettingsService userSettingsService,
                                                       GlycemicEventProperties props) {
        HovorkaGlucosePredictionService raw = new HovorkaGlucosePredictionService(paramService, odeSolver,
                basalResolver, insulinPrefsService, gutModel, userSettingsService, PredictionResidualProvider.NONE);
        return new GlycemicEventDetector(raw, props);
    }
}
```

In `RescueMarkerPropagationTest`, replace `GlycemicEventScanService.toCarbsEntry` with `GlycemicEventDetector.toCarbsEntry` and add `import che.glucosemonitorbe.glycemic.GlycemicEventDetector;`.

- [ ] **Step 5: Rewrite the scan service as the thin shell**

Replace `service/GlycemicEventScanService.java` entirely:

```java
package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.FeatureToggleConfig;
import che.glucosemonitorbe.config.GlycemicEventProperties;
import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.domain.GlucoseConversion;
import che.glucosemonitorbe.domain.User;
import che.glucosemonitorbe.domain.UserZones;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.Category;
import che.glucosemonitorbe.entity.GlycemicEvent.Phase;
import che.glucosemonitorbe.entity.GlycemicEvent.State;
import che.glucosemonitorbe.entity.GlycemicEventHistory.Actor;
import che.glucosemonitorbe.entity.GlycemicEventHistory.Kind;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.glycemic.EventClassifier;
import che.glucosemonitorbe.glycemic.GlycemicEventDetector;
import che.glucosemonitorbe.glycemic.GlycemicEventDetector.Detection;
import che.glucosemonitorbe.hovorka.HovorkaParameterService;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.repository.GlycemicEventRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Periodic glycemic-event scan (spec section 2): loads one user's recent CGM + notes, runs the pure
 * {@link GlycemicEventDetector}, classifies each detection and persists it (merging into an overlapping
 * OPEN event of the same direction). User operations live in {@link GlycemicEventService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlycemicEventScanService {

    private static final double MGDL_PER_MMOL = GlucoseConversion.MGDL_PER_MMOL;
    private static final String SEED_EMAIL_PATTERN = "azt1d-subject-%@dataset.local";
    private static final int WARMUP_LOOKBACK_HOURS = 8;

    private final CgmReadingRepository cgmReadingRepository;
    private final NoteRepository noteRepository;
    private final UserRepository userRepository;
    private final GlycemicEventRepository eventRepository;
    private final HovorkaParameterService paramService;
    private final UserInsulinPreferencesService insulinPrefsService;
    private final UserSettingsService userSettingsService;
    private final CarbsOnBoardService carbsOnBoardService;
    private final FeatureToggleConfig featureToggleConfig;
    private final GlycemicEventProperties props;
    private final GlycemicEventDetector detector;
    private final GlycemicEventHistoryRecorder historyRecorder;

    /** Aggregate outcome of a scan pass. */
    public record ScanSummary(int scanned, int flagged, int skipped, int failed) {}

    /** Scan every real (non-seed) user. No-op unless the feature is enabled. */
    public ScanSummary scanAllRealUsers() {
        if (!featureToggleConfig.isGlycemicEventDetectionEnabled()) return new ScanSummary(0, 0, 0, 0);
        int scanned = 0, flagged = 0, skipped = 0, failed = 0;
        for (User user : userRepository.findByEmailNotLike(SEED_EMAIL_PATTERN)) {
            try {
                List<GlycemicEvent> touched = scanUserEvents(user.getId());
                scanned++;
                if (touched.isEmpty()) skipped++; else flagged++;
            } catch (Exception e) {
                failed++;
                log.warn("Glycemic-event scan failed for user {}: {}", user.getId(), e.getMessage());
            }
        }
        ScanSummary summary = new ScanSummary(scanned, flagged, skipped, failed);
        log.info("Glycemic-event scan: scanned={}, flagged={}, skipped={}, failed={}", scanned, flagged, skipped, failed);
        return summary;
    }

    /** The strongest event touched by one scan (largest |mean residual|), for callers that want one. */
    @Transactional
    public Optional<GlycemicEvent> scanUser(UUID userId) {
        return scanUserEvents(userId).stream()
                .max(Comparator.comparingDouble(e -> Math.abs(e.getMeanResidualMmol())));
    }

    /** Scan one user's recent window; returns every event created or updated by this pass. */
    @Transactional
    public List<GlycemicEvent> scanUserEvents(UUID userId) {
        if (!featureToggleConfig.isGlycemicEventDetectionEnabled()) return List.of();

        // Run on the user's own clock: notes hold local wall time, CGM rows hold UTC epochs (audit F26).
        UserSettingsDTO settings = userSettingsService.getUserSettings(userId);
        ZoneId zone = resolveUserZone(settings);
        LocalDateTime end = LocalDateTime.now(zone);
        GlycemicEventDetector.Input in = loadInput(userId, settings, zone, end);

        List<Detection> detections = new ArrayList<>(detector.detect(in));
        detections.sort(Comparator.comparingLong(Detection::onsetMs));

        LocalDateTime windowStart = end.minusMinutes(props.getWindowMinutes());
        List<GlycemicEvent> open = new ArrayList<>(eventRepository.findByUserIdAndStateIn(userId, List.of(State.OPEN)));
        List<GlycemicEvent> answered = eventRepository.findByUserIdAndStateInAndWindowEndAfter(
                userId, List.of(State.CONFIRMED, State.DISMISSED), windowStart.minusHours(1));
        EventClassifier classifier = new EventClassifier(classifierConfig(in.iob()));

        List<GlycemicEvent> touched = new ArrayList<>();
        for (Detection d : detections) {
            LocalDateTime onset = toLdt(d.onsetMs(), zone), wEnd = toLdt(d.run().endMs(), zone);
            // The user already answered this move - never re-open it as a fresh marker.
            if (answered.stream().anyMatch(a -> a.getDirection() == d.direction() && overlaps(a, onset, wEnd))) continue;
            EventClassifier.Classification c = classifier.classify(d.direction(), d.features());
            double confidence = EventClassifier.confidence(c.baseConfidence(), d.features().sigmaMultiple());
            touched.add(persist(userId, d, c, confidence, false, c.reason(), open, zone));
        }
        return touched;
    }

    GlycemicEventDetector.Input loadInput(UUID userId, UserSettingsDTO settings, ZoneId zone, LocalDateTime end) {
        LocalDateTime start = end.minusMinutes(props.getWindowMinutes());
        List<CgmReading> readings = cgmReadingRepository.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(
                userId, UserZones.toEpochMs(start, zone), UserZones.toEpochMs(end, zone));
        List<GlycemicEventDetector.CgmPoint> cgm = readings.stream()
                .filter(r -> r.getSgv() != null && r.getDateTimestamp() != null)
                .map(r -> new GlycemicEventDetector.CgmPoint(r.getDateTimestamp(), r.getSgv() / MGDL_PER_MMOL))
                .toList();
        List<Note> notes = noteRepository.findByUserIdAndTimestampBetween(userId, start.minusHours(WARMUP_LOOKBACK_HOURS), end);
        List<CarbsEntry> carbEntries = notes.stream()
                .filter(n -> !n.isLongActing() && n.getCarbs() != null && n.getCarbs() > 0)
                .map(GlycemicEventDetector::toCarbsEntry).toList();
        return new GlycemicEventDetector.Input(userId, zone, end, cgm, notes, paramService.buildRawForUser(userId),
                insulinPrefsService.getRapidIobParameters(userId), settings,
                t -> carbsOnBoardService.calculateTotalCarbsOnBoard(carbEntries, t, settings),
                featureToggleConfig.isActivityLoggingEnabled());
    }

    EventClassifier.Config classifierConfig(RapidInsulinIobParameters iob) {
        return new EventClassifier.Config(props.getSignificantIobDropMmol(), props.getCobZeroGrams(),
                props.getDawnStartHour(), props.getDawnEndHour(), props.getDawnMaxSlope(),
                (int) Math.round(iob.diaHours() * 60), props.getDropResolveMinutes());
    }

    /**
     * Create an event, or update the overlapping OPEN event of the same direction. An existing event keeps
     * its category and phase unless the new classification comes from something the user logged
     * (rules R1/R2/F1/F3) - a later log is the one thing that should change the engine's mind.
     */
    GlycemicEvent persist(UUID userId, Detection d, EventClassifier.Classification c, double confidence,
                          boolean suppressed, String reason, List<GlycemicEvent> open, ZoneId zone) {
        LocalDateTime onset = toLdt(d.onsetMs(), zone);
        LocalDateTime wStart = toLdt(d.run().startMs(), zone);
        LocalDateTime wEnd = toLdt(d.run().endMs(), zone);
        Optional<GlycemicEvent> existing = open.stream()
                .filter(e -> e.getDirection() == d.direction() && e.getLinkedEventId() == null && overlaps(e, onset, wEnd))
                .findFirst();
        boolean isNew = existing.isEmpty();
        GlycemicEvent e = existing.orElseGet(() -> GlycemicEvent.builder().userId(userId).state(State.OPEN).build());
        Category before = e.getCategory();
        Phase phaseBefore = isNew ? null : e.getPhase();

        if (isNew || EventClassifier.isLoggedContextRule(c.reason())) {
            e.setCategory(c.category());
            e.setDetectorCategory(c.category());
            e.setPhase(c.phase());
            e.setResolvesAt(c.resolveAfterMinutes() == null ? null : onset.plusMinutes(c.resolveAfterMinutes()));
        }
        e.setDirection(d.direction());
        e.setOnsetAt(isNew || onset.isBefore(e.getOnsetAt()) ? onset : e.getOnsetAt());
        e.setWindowStart(isNew || wStart.isBefore(e.getWindowStart()) ? wStart : e.getWindowStart());
        e.setWindowEnd(isNew || wEnd.isAfter(e.getWindowEnd()) ? wEnd : e.getWindowEnd());
        e.setMeanResidualMmol(round2(d.run().mean()));
        e.setSigmaMultiple(round2(d.features().sigmaMultiple()));
        if (e.getFeatures() == null || e.getFeatures().insulinEffectRatio() == null) e.setFeatures(d.features());
        e.setConfidence(round2(confidence));
        e.setSuppressed(suppressed);
        e.setUpdatedAt(LocalDateTime.now());

        GlycemicEvent saved = eventRepository.save(e);
        if (isNew) {
            open.add(saved);
            historyRecorder.record(saved, Kind.DETECTED, null, Actor.SYSTEM, reason);
        } else if (before != saved.getCategory() || phaseBefore != saved.getPhase()) {
            historyRecorder.record(saved, Kind.UPDATED, before, Actor.SYSTEM, reason);
        }
        return saved;
    }

    static boolean overlaps(GlycemicEvent e, LocalDateTime start, LocalDateTime end) {
        LocalDateTime from = e.getOnsetAt() != null && e.getOnsetAt().isBefore(e.getWindowStart())
                ? e.getOnsetAt() : e.getWindowStart();
        return !from.isAfter(end) && !start.isAfter(e.getWindowEnd());
    }

    private static ZoneId resolveUserZone(UserSettingsDTO settings) {
        if (settings == null) return ZoneOffset.UTC;
        return UserZones.resolve(settings.getTimezone(), settings.getUtcOffsetMinutes());
    }

    private static LocalDateTime toLdt(long epochMs, ZoneId zone) { return UserZones.toWallTime(epochMs, zone); }

    static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
```

- [ ] **Step 6: Run the pipeline tests, then every glycemic test**

Run: `./gradlew test --tests '*GlycemicEventPipelineE2ETest' --tests '*GlycemicEvent*' --tests '*RescueMarkerPropagationTest' --tests 'che.glucosemonitorbe.glycemic.*'`
Expected: PASS. The 17 legacy cases still pass because every legacy fixture is a single step with no insulin (T7's fall becomes a provisional `UNLOGGED_INSULIN`, same category) and `scanUser` still returns the strongest event.

If `onsetAtRampStart` misses by more than 15 minutes, print the event's features and the model prediction before changing anything. A consistent model drift from the 110 mg/dL baseline means the fixture needs a flatter baseline, not a looser assertion.

- [ ] **Step 7: Commit**

```bash
git add -A src/
git commit -m "feat(glycemic): multi-event scan pipeline with onset, features and rule classification

GlycemicEventDetector is the pure in-memory pipeline; the scan service loads,
classifies and persists, skipping windows the user already answered.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: `TailResolver` (pure)

**Files:**
- Create: `glycemic/TailResolver.java`
- Create test: `src/test/java/che/glucosemonitorbe/glycemic/TailResolverTest.java`

**Interfaces:**
- Consumes: `Category`.
- Produces:
  - `record TailResolver.Config(double peakMinutes, double noEffectRatio, int aerobicMaxMinutes, int anaerobicMaxRiseMinutes, int anaerobicMaxGapMinutes, double effectDenominatorFloor)`.
  - `boolean isSlingshot(long parentOnsetMs, long parentResolvesMs, long fallOnsetMs, List<Long> bolusEpochMs)`.
  - `static Double effectRatio(double[] gNoInsulin, double[] gActual, double[] gPredicted, double floor)`.
  - `Category resolveResistance(Double effectRatio)`, `static double lipodystrophyConfidence(long recentNoEffect)` and `Category resolveDrop(long onsetMs, long windowEndMs, long nowMs)`.
  - `boolean isAnaerobicPair(double riseDurationMin, long riseWindowEndMs, long fallOnsetMs, List<Long> bolusEpochMs)`.

- [ ] **Step 1: Write the failing test**

```java
package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.entity.GlycemicEvent.Category;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TailResolverTest {

    private static final long MIN = 60_000L;
    private final TailResolver tail = new TailResolver(new TailResolver.Config(75, 0.25, 90, 75, 30, 0.5));

    @Test void slingshotAfterPeakAndBeforeResolutionIsClaimed() {
        assertThat(tail.isSlingshot(0, 360 * MIN, 180 * MIN, List.of())).isTrue();
    }
    @Test void dropBeforeTheInsulinPeakIsNotASlingshot() {
        assertThat(tail.isSlingshot(0, 360 * MIN, 60 * MIN, List.of())).isFalse();
    }
    @Test void dropAfterResolutionIsNotASlingshot() {
        assertThat(tail.isSlingshot(0, 360 * MIN, 361 * MIN, List.of())).isFalse();
    }
    @Test void aNewBolusBetweenOnsetAndDropBlocksTheLink() {
        assertThat(tail.isSlingshot(0, 360 * MIN, 180 * MIN, List.of(120 * MIN))).isFalse();
        assertThat(tail.isSlingshot(0, 360 * MIN, 180 * MIN, List.of(-15 * MIN))).isTrue();   // the original bolus
    }

    @Test void effectRatioNearZeroWhenGlucoseTrackedTheNoInsulinCurve() {
        double[] no = {10, 10, 10}, act = {10, 9.9, 9.8}, pred = {10, 7, 4};
        assertThat(TailResolver.effectRatio(no, act, pred, 0.5)).isCloseTo(0.1 / 3.0, within(1e-9));
    }
    @Test void effectRatioNullWhenTheModelExpectedNoInsulinEffect() {
        assertThat(TailResolver.effectRatio(new double[]{10, 10}, new double[]{10, 10}, new double[]{10, 9.8}, 0.5)).isNull();
    }
    @Test void effectRatioNullOnEmptyTail() {
        assertThat(TailResolver.effectRatio(new double[0], new double[0], new double[0], 0.5)).isNull();
    }

    @Test void resistanceResolution() {
        assertThat(tail.resolveResistance(0.0)).isEqualTo(Category.LIPODYSTROPHY);
        assertThat(tail.resolveResistance(0.249)).isEqualTo(Category.LIPODYSTROPHY);
        assertThat(tail.resolveResistance(0.25)).isEqualTo(Category.STRESS);
        assertThat(tail.resolveResistance(-0.3)).isEqualTo(Category.STRESS);   // rose beyond no-insulin
        assertThat(tail.resolveResistance(null)).isEqualTo(Category.STRESS);   // insufficient data
    }

    @Test void lipodystrophyConfidenceGrowsWithRecurrenceAndCaps() {
        assertThat(TailResolver.lipodystrophyConfidence(0)).isCloseTo(0.4, within(1e-9));
        assertThat(TailResolver.lipodystrophyConfidence(3)).isCloseTo(0.7, within(1e-9));
        assertThat(TailResolver.lipodystrophyConfidence(20)).isCloseTo(0.9, within(1e-9));
    }

    @Test void dropThatStoppedWithin90MinutesIsCardio() {
        assertThat(tail.resolveDrop(0, 50 * MIN, 120 * MIN)).isEqualTo(Category.AEROBIC_ACTIVITY);
    }
    @Test void dropStillRunningIsUnloggedInsulin() {
        assertThat(tail.resolveDrop(0, 115 * MIN, 120 * MIN)).isEqualTo(Category.UNLOGGED_INSULIN);
    }
    @Test void longDropIsUnloggedInsulinEvenIfItStopped() {
        assertThat(tail.resolveDrop(0, 100 * MIN, 130 * MIN)).isEqualTo(Category.UNLOGGED_INSULIN);
    }

    @Test void shortSpikeThenPromptDropIsAnaerobic() {
        assertThat(tail.isAnaerobicPair(50, 100 * MIN, 105 * MIN, List.of())).isTrue();
    }
    @Test void anaerobicRejectsLongRiseLateDropOrInsulinBetween() {
        assertThat(tail.isAnaerobicPair(80, 100 * MIN, 105 * MIN, List.of())).isFalse();
        assertThat(tail.isAnaerobicPair(50, 100 * MIN, 140 * MIN, List.of())).isFalse();
        assertThat(tail.isAnaerobicPair(50, 100 * MIN, 120 * MIN, List.of(110 * MIN))).isFalse();
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests '*TailResolverTest'`
Expected: FAIL to compile.

- [ ] **Step 3: Implement**

```java
package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.entity.GlycemicEvent.Category;

import java.util.List;

/**
 * Decisions that need evidence arriving after onset (spec section 4). Pure: callers supply times,
 * bolus times and forecast arrays.
 */
public final class TailResolver {

    public record Config(double peakMinutes, double noEffectRatio, int aerobicMaxMinutes,
                         int anaerobicMaxRiseMinutes, int anaerobicMaxGapMinutes, double effectDenominatorFloor) {}

    /** A drop whose window has not grown for this long is considered to have stopped. */
    private static final long STOPPED_GRACE_MS = 15 * 60_000L;

    private final Config c;

    public TailResolver(Config config) { this.c = config; }

    /** A fall is the delayed slingshot of a resistance parent (4.1 step 1). */
    public boolean isSlingshot(long parentOnsetMs, long parentResolvesMs, long fallOnsetMs, List<Long> bolusEpochMs) {
        if (fallOnsetMs < parentOnsetMs + (long) (c.peakMinutes() * 60_000L) || fallOnsetMs > parentResolvesMs) return false;
        return bolusEpochMs.stream().noneMatch(b -> b > parentOnsetMs && b <= fallOnsetMs);
    }

    /**
     * Insulin effect ratio E = mean(G_noIns - G_actual) / mean(G_noIns - G_pred). Null when the tail is
     * empty or the model expected less than {@code floor} mmol/L of insulin effect (E would be noise).
     */
    public static Double effectRatio(double[] gNoInsulin, double[] gActual, double[] gPredicted, double floor) {
        int n = gActual.length;
        if (n == 0) return null;
        double num = 0.0, den = 0.0;
        for (int i = 0; i < n; i++) {
            num += gNoInsulin[i] - gActual[i];
            den += gNoInsulin[i] - gPredicted[i];
        }
        num /= n;
        den /= n;
        return den < floor ? null : num / den;
    }

    /** No slingshot by resolves_at: insulin had ~no effect -> LIPODYSTROPHY, otherwise STRESS. */
    public Category resolveResistance(Double effectRatio) {
        return effectRatio != null && effectRatio >= 0 && effectRatio < c.noEffectRatio()
                ? Category.LIPODYSTROPHY : Category.STRESS;
    }

    /** A single no-effect case is weak evidence; a bad site repeats every time it is used. */
    public static double lipodystrophyConfidence(long recentNoEffect) {
        return Math.min(0.9, 0.4 + 0.1 * recentNoEffect);
    }

    /** An unexplained drop that stopped within the cardio window is exercise; one that kept going is insulin. */
    public Category resolveDrop(long onsetMs, long windowEndMs, long nowMs) {
        boolean stopped = nowMs - windowEndMs >= STOPPED_GRACE_MS;
        boolean shortEnough = windowEndMs - onsetMs <= c.aerobicMaxMinutes() * 60_000L;
        return stopped && shortEnough ? Category.AEROBIC_ACTIVITY : Category.UNLOGGED_INSULIN;
    }

    /** A short unexplained rise followed promptly by a drop, with no insulin in between (4.3). */
    public boolean isAnaerobicPair(double riseDurationMin, long riseWindowEndMs, long fallOnsetMs, List<Long> bolusEpochMs) {
        if (riseDurationMin > c.anaerobicMaxRiseMinutes()) return false;
        if (fallOnsetMs - riseWindowEndMs > c.anaerobicMaxGapMinutes() * 60_000L) return false;
        long from = Math.min(riseWindowEndMs, fallOnsetMs), to = Math.max(riseWindowEndMs, fallOnsetMs);
        return bolusEpochMs.stream().noneMatch(b -> b >= from && b <= to);
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew test --tests '*TailResolverTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A src/
git commit -m "feat(glycemic): tail resolver for slingshot, no-effect, drop and anaerobic decisions

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Tail resolution in the scan (claims, provisional resolution, counterfactual)

**Files:**
- Create: `service/GlycemicTailService.java`
- Modify: `service/GlycemicEventScanService.java` (claim falls before classifying; resolve due events after the loop)
- Create test: `src/test/java/che/glucosemonitorbe/integration/GlycemicEventTailE2ETest.java`

**Interfaces:**
- Consumes: `TailResolver` (Task 9), `GlycemicEventDetector` (`predict`, `align`, `Input`, `Detection`, `CgmPoint`), `GlycemicEventHistoryRecorder`, `GlycemicEventRepository.countByUserIdAndDetectorCategoryAndOnsetAtAfter`, `CgmReadingRepository.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc`.
- Produces: `GlycemicTailService` with `Optional<GlycemicEvent> claimFall(Detection d, GlycemicEventDetector.Input in, List<GlycemicEvent> open)` and `List<GlycemicEvent> resolveDue(GlycemicEventDetector.Input in, List<GlycemicEvent> open)`. History reasons: `tail:slingshot`, `tail:anaerobic`, `tail:child`, `tail:no-effect`, `tail:stress`, `tail:insufficient-data`, `tail:drop-stopped`, `tail:drop-continued`.

- [ ] **Step 1: Write the failing E2E tests**

```java
package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.*;
import che.glucosemonitorbe.entity.GlycemicEventHistory;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.glycemic.EventFeaturesBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GlycemicEventTailE2ETest extends AbstractGlycemicEventE2ETest {

    private static final long H = 3_600_000L, MIN = 60_000L;

    private GlycemicEvent provisional(UUID u, Direction dir, Category cat, LocalDateTime onset,
                                      LocalDateTime windowEnd, LocalDateTime resolvesAt) {
        return eventRepository.save(GlycemicEvent.builder().userId(u).category(cat).detectorCategory(cat)
                .direction(dir).phase(Phase.PROVISIONAL).onsetAt(onset).windowStart(onset).windowEnd(windowEnd)
                .resolvesAt(resolvesAt).meanResidualMmol(dir == Direction.RISE ? 3.0 : -3.0).sigmaMultiple(4.0)
                .confidence(0.5).features(new EventFeaturesBuilder().build()).state(State.OPEN).build());
    }

    private String lastReason(UUID eventId) {
        List<GlycemicEventHistory> h = historyRepository.findByEventIdOrderByAtAsc(eventId);
        return h.get(h.size() - 1).getReason();
    }

    @Test
    @DisplayName("a delayed drop after resistance resolves ABSORPTION_DELAY and links the drop, with no UNLOGGED_INSULIN")
    void slingshot() {
        UUID u = userId();
        long now = nowMs();
        seed(u, now - 47 * STEP, concat(repeat(36, 200), repeat(12, 90)));
        GlycemicEvent parent = provisional(u, Direction.RISE, Category.STRESS,
                toLdt(now - 3 * H), toLdt(now - 2 * H), toLdt(now + 3 * H));

        scanService.scanUserEvents(u);

        GlycemicEvent p = eventRepository.findById(parent.getId()).orElseThrow();
        assertThat(p.getCategory()).isEqualTo(Category.ABSORPTION_DELAY);
        assertThat(p.getPhase()).isEqualTo(Phase.FINAL);
        assertThat(p.getResolvesAt()).isNull();
        assertThat(eventRepository.findByLinkedEventId(p.getId()))
                .singleElement().satisfies(c -> assertThat(c.getDirection()).isEqualTo(Direction.FALL));
        assertThat(eventRepository.findAll()).noneMatch(e -> e.getCategory() == Category.UNLOGGED_INSULIN);
        assertThat(lastReason(p.getId())).isEqualTo("tail:slingshot");
    }

    @Test
    @DisplayName("insulin with no measurable effect over its full action resolves LIPODYSTROPHY")
    void lipodystrophy() {
        UUID u = userId();
        long now = nowMs();
        long onset = now - 390 * MIN;
        note(u, toLdt(onset - 15 * MIN), 0, 5.0, Note.TYPE_NORMAL);
        int n = 79;
        int[] sgv = new int[n];
        for (int i = 0; i < n; i++) sgv[i] = 180 - (int) Math.round(9.0 * i / (n - 1));   // 10.0 -> 9.5 mmol/L
        seed(u, onset, sgv);
        GlycemicEvent parent = provisional(u, Direction.RISE, Category.STRESS,
                toLdt(onset), toLdt(onset + H), toLdt(now - 10 * MIN));

        scanService.scanUserEvents(u);

        GlycemicEvent p = eventRepository.findById(parent.getId()).orElseThrow();
        assertThat(p.getCategory()).isEqualTo(Category.LIPODYSTROPHY);
        assertThat(p.getFeatures().insulinEffectRatio()).isBetween(0.0, 0.25);
        assertThat(p.getConfidence()).isEqualTo(0.4);
        assertThat(lastReason(p.getId())).isEqualTo("tail:no-effect");
    }

    @Test
    @DisplayName("glucose rising beyond the no-insulin curve resolves STRESS")
    void stress() {
        UUID u = userId();
        long now = nowMs();
        long onset = now - 390 * MIN;
        note(u, toLdt(onset - 15 * MIN), 0, 5.0, Note.TYPE_NORMAL);
        int n = 79;
        int[] sgv = new int[n];
        for (int i = 0; i < n; i++) sgv[i] = 180 + (int) Math.round(90.0 * i / (n - 1));  // 10 -> 15 mmol/L
        seed(u, onset, sgv);
        GlycemicEvent parent = provisional(u, Direction.RISE, Category.STRESS,
                toLdt(onset), toLdt(onset + H), toLdt(now - 10 * MIN));

        scanService.scanUserEvents(u);

        GlycemicEvent p = eventRepository.findById(parent.getId()).orElseThrow();
        assertThat(p.getCategory()).isEqualTo(Category.STRESS);
        assertThat(p.getPhase()).isEqualTo(Phase.FINAL);
        assertThat(lastReason(p.getId())).isEqualTo("tail:stress");
    }

    @Test
    @DisplayName("a sensor gap over the tail never resolves LIPODYSTROPHY - STRESS with insufficient-data")
    void insufficientTailData() {
        UUID u = userId();
        long now = nowMs();
        GlycemicEvent parent = provisional(u, Direction.RISE, Category.STRESS,
                toLdt(now - 390 * MIN), toLdt(now - 330 * MIN), toLdt(now - 10 * MIN));

        scanService.scanUserEvents(u);

        GlycemicEvent p = eventRepository.findById(parent.getId()).orElseThrow();
        assertThat(p.getCategory()).isEqualTo(Category.STRESS);
        assertThat(lastReason(p.getId())).isEqualTo("tail:insufficient-data");
    }

    @Test
    @DisplayName("an unexplained drop that stopped within 90 min resolves AEROBIC_ACTIVITY")
    void dropStopped() {
        UUID u = userId();
        long now = nowMs();
        GlycemicEvent e = provisional(u, Direction.FALL, Category.UNLOGGED_INSULIN,
                toLdt(now - 130 * MIN), toLdt(now - 80 * MIN), toLdt(now - 10 * MIN));
        scanService.scanUserEvents(u);
        assertThat(eventRepository.findById(e.getId()).orElseThrow().getCategory()).isEqualTo(Category.AEROBIC_ACTIVITY);
    }

    @Test
    @DisplayName("an unexplained drop still running at resolution resolves UNLOGGED_INSULIN (final)")
    void dropContinued() {
        UUID u = userId();
        long now = nowMs();
        GlycemicEvent e = provisional(u, Direction.FALL, Category.UNLOGGED_INSULIN,
                toLdt(now - 130 * MIN), toLdt(now - 5 * MIN), toLdt(now - 10 * MIN));
        scanService.scanUserEvents(u);
        GlycemicEvent back = eventRepository.findById(e.getId()).orElseThrow();
        assertThat(back.getCategory()).isEqualTo(Category.UNLOGGED_INSULIN);
        assertThat(back.getPhase()).isEqualTo(Phase.FINAL);
    }

    @Test
    @DisplayName("a short spike followed by a prompt drop is ANAEROBIC_ACTIVITY with the drop linked")
    void anaerobic() {
        UUID u = userId();
        seed(u, nowMs() - 47 * STEP, concat(repeat(14, 110), repeat(11, 200), repeat(12, 60), repeat(11, 110)));

        scanService.scanUserEvents(u);

        GlycemicEvent rise = eventRepository.findAll().stream()
                .filter(e -> e.getDirection() == Direction.RISE && e.getLinkedEventId() == null).findFirst().orElseThrow();
        assertThat(rise.getCategory()).isEqualTo(Category.ANAEROBIC_ACTIVITY);
        assertThat(eventRepository.findByLinkedEventId(rise.getId())).hasSize(1);
        assertThat(eventRepository.findAll()).noneMatch(e -> e.getCategory() == Category.UNLOGGED_INSULIN);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests '*GlycemicEventTailE2ETest'`
Expected: FAIL. Nothing resolves provisional events or claims falls yet, so `slingshot` sees `STRESS` and an extra `UNLOGGED_INSULIN`.

- [ ] **Step 3: Implement `GlycemicTailService`**

```java
package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.GlycemicEventProperties;
import che.glucosemonitorbe.domain.GlucoseConversion;
import che.glucosemonitorbe.domain.UserZones;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.*;
import che.glucosemonitorbe.entity.GlycemicEventHistory.Actor;
import che.glucosemonitorbe.entity.GlycemicEventHistory.Kind;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.glycemic.GlycemicEventDetector;
import che.glucosemonitorbe.glycemic.GlycemicEventDetector.CgmPoint;
import che.glucosemonitorbe.glycemic.GlycemicEventDetector.Detection;
import che.glucosemonitorbe.glycemic.ResidualPoint;
import che.glucosemonitorbe.glycemic.TailResolver;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.repository.GlycemicEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Predicate;

/**
 * Tail handling for the scan (spec section 4): claims a fall as the linked child of an earlier event
 * (slingshot, anaerobic drop, or an already-linked child), and resolves PROVISIONAL events whose tail
 * window has closed. Runs inside the scan's transaction.
 */
@Service
@RequiredArgsConstructor
public class GlycemicTailService {

    private final GlycemicEventRepository eventRepository;
    private final CgmReadingRepository cgmReadingRepository;
    private final GlycemicEventDetector detector;
    private final GlycemicEventProperties props;
    private final GlycemicEventHistoryRecorder historyRecorder;

    public Optional<GlycemicEvent> claimFall(Detection d, GlycemicEventDetector.Input in, List<GlycemicEvent> open) {
        ZoneId zone = in.zone();
        LocalDateTime onset = UserZones.toWallTime(d.onsetMs(), zone);
        LocalDateTime wEnd = UserZones.toWallTime(d.run().endMs(), zone);

        Optional<GlycemicEvent> child = open.stream()
                .filter(e -> e.getLinkedEventId() != null && e.getDirection() == Direction.FALL
                        && GlycemicEventScanService.overlaps(e, onset, wEnd))
                .findFirst();
        if (child.isPresent()) {
            GlycemicEvent c = child.get();
            if (wEnd.isAfter(c.getWindowEnd())) c.setWindowEnd(wEnd);
            c.setMeanResidualMmol(GlycemicEventScanService.round2(d.run().mean()));
            c.setUpdatedAt(LocalDateTime.now());
            return Optional.of(eventRepository.save(c));
        }

        TailResolver tail = resolver(in.iob());
        List<Long> boluses = bolusEpochs(in);
        for (GlycemicEvent p : List.copyOf(open)) {
            if (p.getPhase() == Phase.PROVISIONAL && p.getDirection() == Direction.RISE
                    && p.getLinkedEventId() == null && p.getResolvesAt() != null
                    && tail.isSlingshot(ms(p.getOnsetAt(), zone), ms(p.getResolvesAt(), zone), d.onsetMs(), boluses)) {
                Category before = p.getCategory();
                p.setCategory(Category.ABSORPTION_DELAY);
                p.setDetectorCategory(Category.ABSORPTION_DELAY);
                p.setPhase(Phase.FINAL);
                p.setResolvesAt(null);
                if (p.getFeatures() != null) {
                    p.setFeatures(p.getFeatures().withTail(null, d.run().peak(),
                            (d.onsetMs() - ms(p.getOnsetAt(), zone)) / 60_000.0, null));
                }
                p.setUpdatedAt(LocalDateTime.now());
                GlycemicEvent saved = eventRepository.save(p);
                historyRecorder.record(saved, Kind.RESOLVED, before, Actor.SYSTEM, "tail:slingshot");
                return Optional.of(saveChild(saved, d, zone, open));
            }
        }
        for (GlycemicEvent r : List.copyOf(open)) {
            if (r.getPhase() == Phase.FINAL && r.getDirection() == Direction.RISE
                    && r.getCategory() == Category.UNLOGGED_FOOD && r.getLinkedEventId() == null
                    && open.stream().noneMatch(x -> r.getId().equals(x.getLinkedEventId()))
                    && tail.isAnaerobicPair(Duration.between(r.getWindowStart(), r.getWindowEnd()).toMinutes(),
                            ms(r.getWindowEnd(), zone), d.onsetMs(), boluses)) {
                Category before = r.getCategory();
                r.setCategory(Category.ANAEROBIC_ACTIVITY);
                r.setDetectorCategory(Category.ANAEROBIC_ACTIVITY);
                r.setUpdatedAt(LocalDateTime.now());
                GlycemicEvent saved = eventRepository.save(r);
                historyRecorder.record(saved, Kind.RESOLVED, before, Actor.SYSTEM, "tail:anaerobic");
                return Optional.of(saveChild(saved, d, zone, open));
            }
        }
        return Optional.empty();
    }

    public List<GlycemicEvent> resolveDue(GlycemicEventDetector.Input in, List<GlycemicEvent> open) {
        ZoneId zone = in.zone();
        LocalDateTime now = in.end();
        TailResolver tail = resolver(in.iob());
        List<GlycemicEvent> resolved = new ArrayList<>();
        for (GlycemicEvent e : List.copyOf(open)) {
            if (e.getPhase() != Phase.PROVISIONAL || e.getResolvesAt() == null
                    || e.getResolvesAt().isAfter(now) || e.getLinkedEventId() != null) continue;
            Category before = e.getCategory();
            String reason;
            if (e.getDirection() == Direction.RISE) {
                Double ratio = effectRatio(in, e);
                Category cat = tail.resolveResistance(ratio);
                long recent = eventRepository.countByUserIdAndDetectorCategoryAndOnsetAtAfter(
                        e.getUserId(), Category.LIPODYSTROPHY, now.minusDays(14));
                if (cat == Category.LIPODYSTROPHY) {
                    e.setConfidence(GlycemicEventScanService.round2(TailResolver.lipodystrophyConfidence(recent)));
                }
                if (e.getFeatures() != null) e.setFeatures(e.getFeatures().withTail(ratio, null, null, (int) recent));
                reason = ratio == null ? "tail:insufficient-data"
                        : cat == Category.LIPODYSTROPHY ? "tail:no-effect" : "tail:stress";
                e.setCategory(cat);
                e.setDetectorCategory(cat);
            } else {
                Category cat = tail.resolveDrop(ms(e.getOnsetAt(), zone), ms(e.getWindowEnd(), zone), ms(now, zone));
                reason = cat == Category.AEROBIC_ACTIVITY ? "tail:drop-stopped" : "tail:drop-continued";
                e.setCategory(cat);
                e.setDetectorCategory(cat);
            }
            e.setPhase(Phase.FINAL);
            e.setResolvesAt(null);
            e.setUpdatedAt(LocalDateTime.now());
            GlycemicEvent saved = eventRepository.save(e);
            historyRecorder.record(saved, Kind.RESOLVED, before, Actor.SYSTEM, reason);
            resolved.add(saved);
        }
        return resolved;
    }

    /**
     * E over [onset, resolves_at]: actual CGM vs. the raw forecast with and without the boluses active at
     * onset. Null when fewer than half the expected CGM points exist (sensor gap) or the model expected
     * too little insulin effect.
     */
    Double effectRatio(GlycemicEventDetector.Input in, GlycemicEvent e) {
        ZoneId zone = in.zone();
        long onsetMs = ms(e.getOnsetAt(), zone), endMs = ms(e.getResolvesAt(), zone);
        int minutes = (int) ((endMs - onsetMs) / 60_000L);
        List<CgmPoint> tailCgm = cgmReadingRepository
                .findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(e.getUserId(), onsetMs, endMs).stream()
                .filter(r -> r.getSgv() != null && r.getDateTimestamp() != null)
                .map(r -> new CgmPoint(r.getDateTimestamp(), r.getSgv() / GlucoseConversion.MGDL_PER_MMOL))
                .toList();
        if (minutes <= 0 || tailCgm.size() < 0.5 * minutes / 5.0) return null;

        double g0 = tailCgm.get(0).mmol();
        LocalDateTime from = UserZones.toWallTime(onsetMs - (long) (in.iob().diaHours() * 3_600_000L), zone);
        LocalDateTime to = e.getResolvesAt();
        Predicate<Note> activeAtOnset = n -> n.getTimestamp() != null
                && !n.getTimestamp().isBefore(from) && !n.getTimestamp().isAfter(to);
        List<ResidualPoint> withIns = GlycemicEventDetector.align(
                detector.predict(in, e.getOnsetAt(), g0, minutes, n -> false), tailCgm, zone, props.getAlignToleranceMs());
        Map<Long, Double> noInsByMs = new HashMap<>();
        for (ResidualPoint p : GlycemicEventDetector.align(
                detector.predict(in, e.getOnsetAt(), g0, minutes, activeAtOnset), tailCgm, zone, props.getAlignToleranceMs())) {
            noInsByMs.put(p.epochMs(), p.predictedMmol());
        }
        List<ResidualPoint> paired = withIns.stream().filter(p -> noInsByMs.containsKey(p.epochMs())).toList();
        double[] gNo = new double[paired.size()], gAct = new double[paired.size()], gPred = new double[paired.size()];
        for (int i = 0; i < paired.size(); i++) {
            gNo[i] = noInsByMs.get(paired.get(i).epochMs());
            gAct[i] = paired.get(i).actualMmol();
            gPred[i] = paired.get(i).predictedMmol();
        }
        return TailResolver.effectRatio(gNo, gAct, gPred, props.getEffectDenominatorFloor());
    }

    private GlycemicEvent saveChild(GlycemicEvent parent, Detection d, ZoneId zone, List<GlycemicEvent> open) {
        GlycemicEvent child = eventRepository.save(GlycemicEvent.builder()
                .userId(parent.getUserId()).linkedEventId(parent.getId())
                .category(parent.getCategory()).detectorCategory(parent.getCategory())
                .direction(Direction.FALL).phase(Phase.FINAL)
                .onsetAt(UserZones.toWallTime(d.onsetMs(), zone))
                .windowStart(UserZones.toWallTime(d.run().startMs(), zone))
                .windowEnd(UserZones.toWallTime(d.run().endMs(), zone))
                .meanResidualMmol(GlycemicEventScanService.round2(d.run().mean()))
                .sigmaMultiple(GlycemicEventScanService.round2(d.features().sigmaMultiple()))
                .confidence(parent.getConfidence()).suppressed(parent.isSuppressed())
                .features(d.features()).state(parent.getState())
                .updatedAt(LocalDateTime.now()).build());
        open.add(child);
        historyRecorder.record(child, Kind.DETECTED, null, Actor.SYSTEM, "tail:child");
        return child;
    }

    private TailResolver resolver(RapidInsulinIobParameters iob) {
        return new TailResolver(new TailResolver.Config(iob.peakMinutes(), props.getNoEffectRatio(),
                props.getAerobicMaxMinutes(), props.getAnaerobicMaxRiseMinutes(), props.getAnaerobicMaxGapMinutes(),
                props.getEffectDenominatorFloor()));
    }

    private static List<Long> bolusEpochs(GlycemicEventDetector.Input in) {
        return in.notes().stream()
                .filter(n -> !n.isLongActing() && n.getInsulin() != null && n.getInsulin() > 0 && n.getTimestamp() != null)
                .map(n -> UserZones.toEpochMs(n.getTimestamp(), in.zone()))
                .toList();
    }

    private static long ms(LocalDateTime t, ZoneId zone) { return UserZones.toEpochMs(t, zone); }
}
```

- [ ] **Step 4: Hook it into the scan**

In `GlycemicEventScanService`, inject `private final GlycemicTailService tailService;`. In `scanUserEvents`, add this inside the loop right after the "already answered" `continue` and before `classifier.classify`:

```java
            if (d.direction() == Direction.FALL) {
                Optional<GlycemicEvent> claimed = tailService.claimFall(d, in, open);
                if (claimed.isPresent()) { touched.add(claimed.get()); continue; }
            }
```

Add this after the loop and before `return touched;`:

```java
        touched.addAll(tailService.resolveDue(in, open));
```

Add `import che.glucosemonitorbe.entity.GlycemicEvent.Direction;`. Resolution runs even when the detector found nothing: a sensor gap must still let provisional events close.

- [ ] **Step 5: Run the tail tests, then every glycemic test**

Run: `./gradlew test --tests '*GlycemicEventTailE2ETest'`
Expected: PASS.
Run: `./gradlew test --tests '*GlycemicEvent*' --tests 'che.glucosemonitorbe.glycemic.*'`
Expected: PASS.

If `lipodystrophy` resolves `STRESS` with a ratio above 0.25, log `gNo`, `gAct` and `gPred` for the fixture before touching thresholds. The fixture assumes the raw model holds roughly steady with no boluses (the legacy flat-110 fixtures rely on the same thing). A steady upward drift of the no-insulin curve would be a model finding to report to the user, not something to tune away.

- [ ] **Step 6: Commit**

```bash
git add -A src/
git commit -m "feat(glycemic): resolve provisional events from tail evidence

Slingshot drops link to their resistance parent (fixing the false
UNLOGGED_INSULIN), no-effect insulin resolves LIPODYSTROPHY via a
no-insulin counterfactual, unexplained drops resolve cardio vs insulin,
and a short spike-then-drop becomes anaerobic activity.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: `EventLabelLearner` (pure)

**Files:**
- Create: `glycemic/EventLabelLearner.java`
- Create test: `src/test/java/che/glucosemonitorbe/glycemic/EventLabelLearnerTest.java`

**Interfaces:**
- Consumes: `EventFeatures`, `Category`, `Direction`; fixture `EventFeaturesBuilder`.
- Produces: `EventLabelLearner(int k, double priorWeight, double overrideThreshold)`; `record LabelledExample(Category label, EventFeatures features)`, where `label == null` means the user dismissed it (the NOISE class); `record Decision(Category category, double confidence, boolean suppressed, boolean overridden)`; `Decision decide(Direction d, Category ruleCategory, double ruleConfidence, boolean provisional, EventFeatures f, List<LabelledExample> training)`.

- [ ] **Step 1: Write the failing test**

```java
package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.entity.GlycemicEvent.Category;
import che.glucosemonitorbe.entity.GlycemicEvent.Direction;
import che.glucosemonitorbe.glycemic.EventLabelLearner.Decision;
import che.glucosemonitorbe.glycemic.EventLabelLearner.LabelledExample;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EventLabelLearnerTest {

    private final EventLabelLearner learner = new EventLabelLearner(5, 10.0, 0.6);
    private final EventFeatures query = new EventFeaturesBuilder().onsetHour(9).expectedDrop60(2.0).build();

    private static List<LabelledExample> copies(int n, Category label, EventFeatures f) {
        return new ArrayList<>(Collections.nCopies(n, new LabelledExample(label, f)));
    }

    @Test void withNoLabelsTheRuleOutputIsReturnedUnchanged() {
        Decision d = learner.decide(Direction.RISE, Category.UNLOGGED_FOOD, 0.6, false, query, List.of());
        assertThat(d).isEqualTo(new Decision(Category.UNLOGGED_FOOD, 0.6, false, false));
    }

    @Test void tenAgreeingLabelsBlendButDoNotYetOverride() {
        // p(STRESS) = (10 * 0.4/7 + 10 * 1) / 20 = 0.529 < 0.6
        Decision d = learner.decide(Direction.RISE, Category.UNLOGGED_FOOD, 0.6, false, query,
                copies(10, Category.STRESS, query));
        assertThat(d.category()).isEqualTo(Category.UNLOGGED_FOOD);
        assertThat(d.overridden()).isFalse();
        assertThat(d.confidence()).isCloseTo(0.3, within(1e-9));   // (10 * 0.6 + 10 * 0) / 20
    }

    @Test void twentyAgreeingLabelsOverride() {
        // p(STRESS) = (10 * 0.4/7 + 20) / 30 = 0.686
        Decision d = learner.decide(Direction.RISE, Category.UNLOGGED_FOOD, 0.6, false, query,
                copies(20, Category.STRESS, query));
        assertThat(d.category()).isEqualTo(Category.STRESS);
        assertThat(d.overridden()).isTrue();
        assertThat(d.confidence()).isCloseTo((10 * 0.4 / 7 + 20) / 30, within(1e-9));
    }

    @Test void neverCrossesDirection() {
        Decision d = learner.decide(Direction.RISE, Category.UNLOGGED_FOOD, 0.6, false, query,
                copies(30, Category.UNLOGGED_INSULIN, query));
        assertThat(d).isEqualTo(new Decision(Category.UNLOGGED_FOOD, 0.6, false, false));
    }

    @Test void provisionalEventsAreNeverRelabelled() {
        Decision d = learner.decide(Direction.RISE, Category.STRESS, 0.5, true, query,
                copies(30, Category.UNLOGGED_FOOD, query));
        assertThat(d.category()).isEqualTo(Category.STRESS);
        assertThat(d.overridden()).isFalse();
    }

    @Test void aClusterOfDismissalsSuppressesThePrompt() {
        Decision d = learner.decide(Direction.RISE, Category.UNLOGGED_FOOD, 0.6, false, query,
                copies(20, null, query));
        assertThat(d.suppressed()).isTrue();
        assertThat(d.category()).isEqualTo(Category.UNLOGGED_FOOD);
    }

    @Test void nearestNeighboursDecideNotTheMajority() {
        EventFeatures morning = new EventFeaturesBuilder().onsetHour(9).expectedDrop60(2.0).build();
        EventFeatures evening = new EventFeaturesBuilder().onsetHour(21).expectedDrop60(0.0).cobAtOnset(0).build();
        List<LabelledExample> training = copies(10, Category.STRESS, morning);
        training.addAll(copies(10, Category.UNLOGGED_FOOD, evening));

        assertThat(learner.decide(Direction.RISE, Category.UNLOGGED_FOOD, 0.6, false, morning, training).category())
                .isEqualTo(Category.STRESS);
        assertThat(learner.decide(Direction.RISE, Category.UNLOGGED_FOOD, 0.6, false, evening, training).category())
                .isEqualTo(Category.UNLOGGED_FOOD);
    }

    @Test void examplesWithoutUsableFeaturesAreSkipped() {
        EventFeatures legacy = new EventFeatures(0, 9, false, 60, 3, 4, 0, 0, 0, 7, 0, 0, 0,
                null, null, false, false, false, 4, null, null, null, null);
        List<LabelledExample> training = copies(15, Category.STRESS, null);
        training.addAll(copies(15, Category.STRESS, legacy));
        assertThat(learner.decide(Direction.RISE, Category.UNLOGGED_FOOD, 0.6, false, query, training))
                .isEqualTo(new Decision(Category.UNLOGGED_FOOD, 0.6, false, false));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests '*EventLabelLearnerTest'`
Expected: FAIL to compile.

- [ ] **Step 3: Implement**

```java
package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.entity.GlycemicEvent.Category;
import che.glucosemonitorbe.entity.GlycemicEvent.Direction;

import java.util.*;

/**
 * Per-user refinement of the rule output (spec section 3.2): distance-weighted k-NN over the user's
 * answered events, blended with the rule prior by label count -
 * p = (priorWeight * p_rule + n * p_knn) / (priorWeight + n). With no labels it returns the rule output.
 * Never crosses direction, never relabels a PROVISIONAL event (the tail decides), and can suppress a
 * prompt when the user keeps dismissing look-alike events.
 */
public final class EventLabelLearner {

    /** A past event the user answered. {@code label == null} means dismissed (the NOISE class). */
    public record LabelledExample(Category label, EventFeatures features) {}

    public record Decision(Category category, double confidence, boolean suppressed, boolean overridden) {}

    private static final String NOISE = "NOISE";
    private static final double MISSING_MINUTES = 480.0;

    private final int k;
    private final double priorWeight;
    private final double overrideThreshold;

    public EventLabelLearner(int k, double priorWeight, double overrideThreshold) {
        this.k = k;
        this.priorWeight = priorWeight;
        this.overrideThreshold = overrideThreshold;
    }

    public Decision decide(Direction d, Category ruleCategory, double ruleConfidence, boolean provisional,
                           EventFeatures f, List<LabelledExample> training) {
        List<LabelledExample> usable = training.stream()
                .filter(ex -> ex.features() != null && ex.features().v() == EventFeatures.VERSION)
                .filter(ex -> ex.label() == null || ex.label().direction() == d)
                .toList();

        List<String> classes = new ArrayList<>();
        Category.forDirection(d).forEach(c -> classes.add(c.name()));
        classes.add(NOISE);
        Map<String, Double> p = new HashMap<>();
        double rest = (1.0 - ruleConfidence) / (classes.size() - 1);
        for (String c : classes) p.put(c, c.equals(ruleCategory.name()) ? ruleConfidence : rest);

        int n = usable.size();
        if (n > 0) {
            Map<String, Double> knn = knn(f, usable, classes);
            for (String c : classes) p.put(c, (priorWeight * p.get(c) + n * knn.get(c)) / (priorWeight + n));
        }

        String best = classes.stream().max(Comparator.comparingDouble(p::get)).orElseThrow();
        double ruleP = p.get(ruleCategory.name());
        if (best.equals(NOISE) && p.get(NOISE) >= overrideThreshold) {
            return new Decision(ruleCategory, ruleP, true, false);
        }
        if (!provisional && !best.equals(NOISE) && !best.equals(ruleCategory.name()) && p.get(best) >= overrideThreshold) {
            return new Decision(Category.valueOf(best), p.get(best), false, true);
        }
        return new Decision(ruleCategory, ruleP, false, false);
    }

    private Map<String, Double> knn(EventFeatures q, List<LabelledExample> examples, List<String> classes) {
        double[][] x = examples.stream().map(e -> vector(e.features())).toArray(double[][]::new);
        double[] query = vector(q);
        int dims = query.length;
        double[] med = new double[dims], scale = new double[dims];
        for (int j = 0; j < dims; j++) {
            double[] col = new double[x.length];
            for (int i = 0; i < x.length; i++) col[i] = x[i][j];
            med[j] = median(col.clone());
            double[] dev = new double[col.length];
            for (int i = 0; i < col.length; i++) dev[i] = Math.abs(col[i] - med[j]);
            double s = 1.4826 * median(dev);
            scale[j] = s < 1e-9 ? 1.0 : s;
        }
        record Neighbour(double dist, String label) {}
        List<Neighbour> all = new ArrayList<>();
        for (int i = 0; i < x.length; i++) {
            double sum = 0.0;
            for (int j = 0; j < dims; j++) {
                double z = (x[i][j] - query[j]) / scale[j];
                sum += z * z;
            }
            Category label = examples.get(i).label();
            all.add(new Neighbour(Math.sqrt(sum), label == null ? NOISE : label.name()));
        }
        all.sort(Comparator.comparingDouble(Neighbour::dist));
        Map<String, Double> w = new HashMap<>();
        classes.forEach(c -> w.put(c, 0.0));
        double total = 0.0;
        for (Neighbour nb : all.subList(0, Math.min(k, all.size()))) {
            double wt = 1.0 / (nb.dist() + 1e-6);
            w.merge(nb.label(), wt, Double::sum);
            total += wt;
        }
        for (String c : classes) w.put(c, w.get(c) / total);
        return w;
    }

    static double[] vector(EventFeatures f) {
        double angle = 2 * Math.PI * f.onsetHour() / 24.0;
        return new double[]{
                Math.sin(angle), Math.cos(angle), f.weekend() ? 1 : 0,
                f.residualSlope(), f.peakResidual(), f.durationMin(),
                f.expectedDrop60(), f.cobAtOnset(),
                f.minSinceCarbs() == null ? MISSING_MINUTES : f.minSinceCarbs(),
                f.minSinceBolus() == null ? MISSING_MINUTES : f.minSinceBolus(),
                f.glucoseAtOnset()};
    }

    private static double median(double[] a) {
        Arrays.sort(a);
        int n = a.length;
        if (n == 0) return 0.0;
        return n % 2 == 1 ? a[n / 2] : 0.5 * (a[n / 2 - 1] + a[n / 2]);
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew test --tests '*EventLabelLearnerTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A src/
git commit -m "feat(glycemic): per-user k-NN learner blended with the rule prior

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: Learner in the scan (training cache, override, suppression)

**Files:**
- Create: `service/GlycemicLearnerTrainingCache.java`
- Modify: `service/GlycemicEventScanService.java` (apply the learner after classification)
- Modify: `service/GlycemicEventService.java` (invalidate the cache on confirm/dismiss)
- Create tests: `src/test/java/che/glucosemonitorbe/service/GlycemicLearnerTrainingCacheTest.java`, `src/test/java/che/glucosemonitorbe/integration/GlycemicEventLearnerE2ETest.java`

**Interfaces:**
- Consumes: `EventLabelLearner` (Task 11), `GlycemicEventRepository.findByUserIdAndStateIn`.
- Produces: `GlycemicLearnerTrainingCache` with `List<EventLabelLearner.LabelledExample> examples(UUID userId, Direction d)` (60 s TTL per user) and `void invalidate(UUID userId)`. Scan history `reason = "learner"` when the learner overrides.

- [ ] **Step 1: Write the failing cache unit test (mock-first)**

```java
package che.glucosemonitorbe.service;

import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.*;
import che.glucosemonitorbe.glycemic.EventFeaturesBuilder;
import che.glucosemonitorbe.glycemic.EventLabelLearner.LabelledExample;
import che.glucosemonitorbe.repository.GlycemicEventRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GlycemicLearnerTrainingCacheTest {

    private final GlycemicEventRepository repo = mock(GlycemicEventRepository.class);
    private final GlycemicLearnerTrainingCache cache = new GlycemicLearnerTrainingCache(repo);
    private final UUID user = UUID.randomUUID();

    private GlycemicEvent ev(Category detector, Category userLabel, State state, boolean withFeatures, UUID parent) {
        return GlycemicEvent.builder().id(UUID.randomUUID()).userId(user).category(userLabel != null ? userLabel : detector)
                .detectorCategory(detector).userLabel(userLabel).direction(detector.direction()).state(state)
                .linkedEventId(parent).features(withFeatures ? new EventFeaturesBuilder().build() : null).build();
    }

    @Test
    void mapsAnswersToLabelsAndSkipsUnusableRows() {
        when(repo.findByUserIdAndStateIn(eq(user), any())).thenReturn(List.of(
                ev(Category.UNLOGGED_FOOD, null, State.CONFIRMED, true, null),          // label UNLOGGED_FOOD
                ev(Category.UNLOGGED_FOOD, Category.STRESS, State.CONFIRMED, true, null),// relabel -> STRESS
                ev(Category.UNLOGGED_FOOD, null, State.DISMISSED, true, null),          // NOISE (null)
                ev(Category.UNLOGGED_FOOD, null, State.CONFIRMED, false, null),         // legacy: no features
                ev(Category.ABSORPTION_DELAY, null, State.CONFIRMED, true, UUID.randomUUID()), // child
                ev(Category.UNLOGGED_INSULIN, null, State.CONFIRMED, true, null)));     // other direction

        List<LabelledExample> rise = cache.examples(user, Direction.RISE);

        assertThat(rise).extracting(LabelledExample::label)
                .containsExactly(Category.UNLOGGED_FOOD, Category.STRESS, null);
    }

    @Test
    void cachesUntilInvalidated() {
        when(repo.findByUserIdAndStateIn(eq(user), any())).thenReturn(List.of());
        cache.examples(user, Direction.RISE);
        cache.examples(user, Direction.FALL);
        verify(repo, times(1)).findByUserIdAndStateIn(eq(user), any());
        cache.invalidate(user);
        cache.examples(user, Direction.RISE);
        verify(repo, times(2)).findByUserIdAndStateIn(eq(user), any());
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests '*GlycemicLearnerTrainingCacheTest'`
Expected: FAIL to compile.

- [ ] **Step 3: Implement the cache**

```java
package che.glucosemonitorbe.service;

import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.Direction;
import che.glucosemonitorbe.entity.GlycemicEvent.State;
import che.glucosemonitorbe.glycemic.EventLabelLearner.LabelledExample;
import che.glucosemonitorbe.repository.GlycemicEventRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The learner's per-user training set: answered, top-level events with a feature vector. Cached 60 s
 * (same pattern as the digital-twin cache) and invalidated whenever the user answers an event.
 */
@Component
public class GlycemicLearnerTrainingCache {

    private static final Duration TTL = Duration.ofSeconds(60);

    private record Entry(List<GlycemicEvent> events, Instant loadedAt) {}

    private final GlycemicEventRepository eventRepository;
    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();

    public GlycemicLearnerTrainingCache(GlycemicEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    public List<LabelledExample> examples(UUID userId, Direction direction) {
        Entry entry = cache.compute(userId, (id, old) -> old != null && old.loadedAt().plus(TTL).isAfter(Instant.now())
                ? old
                : new Entry(eventRepository.findByUserIdAndStateIn(id, List.of(State.CONFIRMED, State.DISMISSED)), Instant.now()));
        return entry.events().stream()
                .filter(e -> e.getFeatures() != null && e.getLinkedEventId() == null && e.getDirection() == direction)
                .map(e -> new LabelledExample(e.getState() == State.DISMISSED ? null
                        : e.getUserLabel() != null ? e.getUserLabel() : e.getDetectorCategory(), e.getFeatures()))
                .toList();
    }

    public void invalidate(UUID userId) { cache.remove(userId); }
}
```

- [ ] **Step 4: Run the cache test**

Run: `./gradlew test --tests '*GlycemicLearnerTrainingCacheTest'`
Expected: PASS.

- [ ] **Step 5: Write the failing learner E2E test**

```java
package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.State;
import che.glucosemonitorbe.service.GlycemicLearnerTrainingCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GlycemicEventLearnerE2ETest extends AbstractGlycemicEventE2ETest {

    @Autowired GlycemicLearnerTrainingCache trainingCache;

    @Test
    @DisplayName("a pattern the user keeps dismissing is stored but suppressed")
    void dismissalsSuppress() {
        UUID u = userId();
        long start = nowMs() - 35 * STEP;
        seed(u, start, concat(repeat(24, 110), repeat(12, 200)));
        GlycemicEvent first = scanService.scanUser(u).orElseThrow();
        assertThat(first.isSuppressed()).isFalse();

        for (int i = 0; i < 20; i++) {   // 20 dismissed look-alikes, two days back
            eventRepository.save(GlycemicEvent.builder().userId(u).category(first.getCategory())
                    .detectorCategory(first.getDetectorCategory()).direction(first.getDirection())
                    .onsetAt(first.getOnsetAt().minusDays(2).plusMinutes(i)).windowStart(first.getWindowStart().minusDays(2))
                    .windowEnd(first.getWindowEnd().minusDays(2)).meanResidualMmol(first.getMeanResidualMmol())
                    .features(first.getFeatures()).state(State.DISMISSED).build());
        }
        eventRepository.delete(first);
        trainingCache.invalidate(u);

        GlycemicEvent again = scanService.scanUser(u).orElseThrow();
        assertThat(again.isSuppressed()).isTrue();
        assertThat(again.getState()).isEqualTo(State.OPEN);
    }
}
```

- [ ] **Step 6: Run to verify failure**

Run: `./gradlew test --tests '*GlycemicEventLearnerE2ETest'`
Expected: FAIL: `again.isSuppressed()` is false.

- [ ] **Step 7: Apply the learner in the scan and invalidate on answers**

In `GlycemicEventScanService`, inject `private final GlycemicLearnerTrainingCache trainingCache;`. In `scanUserEvents`, before the loop:

```java
        EventLabelLearner learner = new EventLabelLearner(props.getLearnerK(), props.getLearnerPriorWeight(),
                props.getLearnerOverride());
```

Replace the two lines that compute `confidence` and call `persist` with:

```java
            double ruleConfidence = EventClassifier.confidence(c.baseConfidence(), d.features().sigmaMultiple());
            EventLabelLearner.Decision ld = learner.decide(d.direction(), c.category(), ruleConfidence,
                    c.phase() == Phase.PROVISIONAL, d.features(), trainingCache.examples(userId, d.direction()));
            EventClassifier.Classification effective = ld.overridden()
                    ? new EventClassifier.Classification(ld.category(), c.phase(), c.resolveAfterMinutes(),
                            c.baseConfidence(), "learner")
                    : c;
            touched.add(persist(userId, d, effective, ld.confidence(), ld.suppressed(), effective.reason(), open, zone));
```

(Add `import che.glucosemonitorbe.glycemic.EventLabelLearner;`.)

In `GlycemicEventService`, add `private final GlycemicLearnerTrainingCache trainingCache;` as the **last** field, call `trainingCache.invalidate(userId)` at the end of `confirm` and `dismiss`, and update `GlycemicEventServiceTest.setUp` to `new GlycemicEventService(events, notes, mock(GlycemicEventHistoryRecorder.class), new GlycemicEventProperties(), mock(GlycemicLearnerTrainingCache.class))`. (Task 14 replaces this service and its test with the final field order.)

- [ ] **Step 8: Run the tests**

Run: `./gradlew test --tests '*GlycemicEventLearnerE2ETest' --tests '*GlycemicEvent*' --tests 'che.glucosemonitorbe.glycemic.*' --tests '*GlycemicLearner*'`
Expected: PASS. Legacy users have no answered events, so their output equals the rules.

- [ ] **Step 9: Commit**

```bash
git add -A src/
git commit -m "feat(glycemic): learn from the user's answers during the scan

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: Calibration keeps learning dawn

**Files:**
- Modify: `service/DigitalTwinCalibrationService.java` (`excludeFlaggedWindows`)
- Create test: `src/test/java/che/glucosemonitorbe/integration/GlycemicEventCalibrationE2ETest.java`

**Interfaces:**
- Consumes: `DigitalTwinCalibrationService.calibrateUser(UUID)` returning `DigitalTwinCalibrator.Result` with `trainSamples()`.

- [ ] **Step 1: Write the failing test**

```java
package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.*;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.hovorka.learning.DigitalTwinCalibrator;
import che.glucosemonitorbe.service.DigitalTwinCalibrationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GlycemicEventCalibrationE2ETest extends AbstractGlycemicEventE2ETest {

    @Autowired DigitalTwinCalibrationService calibrationService;

    private void seedCalibrationData(UUID u) {
        long now = nowMs();
        int count = 260;
        int[] sgv = new int[count];
        for (int i = 0; i < count; i++) sgv[i] = 110 + ((i % 12) < 6 ? 0 : 20);
        seed(u, now - (count - 1) * STEP, sgv);
        note(u, toLdt(now - 12 * 3_600_000L), 40.0, 3.0, Note.TYPE_NORMAL);
    }

    @Test
    @DisplayName("a confirmed DAWN_PHENOMENON window stays in the fit; other categories are excluded")
    void dawnIsNotDownWeighted() {
        UUID u = userId();
        seedCalibrationData(u);
        DigitalTwinCalibrator.Result r0 = calibrationService.calibrateUser(u);
        assertThat(r0).isNotNull();
        int base = r0.trainSamples();

        LocalDateTime wEnd = toLdt(nowMs() - 6 * 3_600_000L);
        GlycemicEvent dawn = eventRepository.save(GlycemicEvent.builder().userId(u)
                .category(Category.DAWN_PHENOMENON).direction(Direction.RISE)
                .windowStart(wEnd.minusHours(4)).windowEnd(wEnd).meanResidualMmol(2.0)
                .state(State.CONFIRMED).build());
        assertThat(calibrationService.calibrateUser(u).trainSamples()).isEqualTo(base);

        dawn.setCategory(Category.UNLOGGED_FOOD);
        eventRepository.save(dawn);
        assertThat(calibrationService.calibrateUser(u).trainSamples()).isLessThan(base);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests '*GlycemicEventCalibrationE2ETest'`
Expected: FAIL: the dawn window is excluded, so `trainSamples < base`.

- [ ] **Step 3: Implement**

In `DigitalTwinCalibrationService.excludeFlaggedWindows`, directly after fetching the `OPEN`/`CONFIRMED` events, add:

```java
        // Dawn is a systematic, repeatable pattern: ResidualBiasModel should learn it, not ignore it.
        flags = flags.stream().filter(f -> f.getCategory() != GlycemicEvent.Category.DAWN_PHENOMENON).toList();
```

(If `flags` is declared `final` or is effectively final, declare a new local `List<GlycemicEvent> excluded` instead and use it in the rest of the method.) Update the method's javadoc to say DAWN_PHENOMENON windows are kept.

- [ ] **Step 4: Run the calibration tests**

Run: `./gradlew test --tests '*GlycemicEventCalibrationE2ETest' --tests '*GlycemicEventE2ETest'`
Expected: PASS (legacy T12 still sees its `UNLOGGED_FOOD` window excluded).

- [ ] **Step 5: Commit**

```bash
git add -A src/
git commit -m "feat(glycemic): keep dawn-phenomenon windows in the digital-twin fit

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 14: The user API (range, prompt, confirm/relabel, dismiss, history)

**Files:**
- Create: `glycemic/EventPresentation.java`, `dto/GlycemicEventHistoryDTO.java`
- Replace: `dto/GlycemicEventDTO.java`, `dto/ConfirmGlycemicEventRequest.java`, `service/GlycemicEventService.java`, `controller/GlycemicEventController.java`
- Modify test: `integration/GlycemicEventE2ETest.java` (T5, T9b request constructor; T10 range)
- Replace test: `service/GlycemicEventServiceTest.java`
- Create tests: `src/test/java/che/glucosemonitorbe/glycemic/EventPresentationTest.java`, `src/test/java/che/glucosemonitorbe/integration/GlycemicEventApiE2ETest.java`

**Interfaces:**
- Consumes: repositories and recorder (Task 3), `GlycemicLearnerTrainingCache` (Task 12), `GlycemicEventProperties` (Task 4), `UserSettingsService.getUserSettings(UUID)`.
- Produces:
  - REST contract used by iOS (Tasks 17–21):
    - `GET /api/glycemic-events?from=&to=&state=` returns `GlycemicEventDTO[]`
    - `GET /api/glycemic-events/prompt` returns `GlycemicEventDTO` or 204
    - `POST /{id}/confirm` with body `{category?, carbs?, insulin?}` returns `GlycemicEventDTO`
    - `POST /{id}/dismiss` returns `GlycemicEventDTO`
    - `GET /{id}/history` returns `GlycemicEventHistoryDTO[]`
  - DTO JSON fields, exactly: `id, category, detectorCategory, userLabel, displayLabel, direction, phase, state, onsetAt, windowStart, windowEnd, resolvesAt, detectedAt, resolvedAt, confidence, suppressed, linkedEventId, meanResidualMmol, sigmaMultiple, promptOptions`. Times use the `yyyy-MM-dd'T'HH:mm:ss` format.
  - `EventPresentation.displayLabel(GlycemicEvent)`, `EventPresentation.label(Category)` and `EventPresentation.promptOptions(GlycemicEvent)`.

- [ ] **Step 1: Write the failing presentation test**

```java
package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventPresentationTest {

    private GlycemicEvent ev(Category c, Direction d, Phase p) {
        return GlycemicEvent.builder().category(c).direction(d).phase(p).build();
    }

    @Test void provisionalLabelsAreQuestions() {
        assertThat(EventPresentation.displayLabel(ev(Category.STRESS, Direction.RISE, Phase.PROVISIONAL))).isEqualTo("Resistance?");
        assertThat(EventPresentation.displayLabel(ev(Category.UNLOGGED_INSULIN, Direction.FALL, Phase.PROVISIONAL))).isEqualTo("Unexplained drop");
    }
    @Test void finalLabels() {
        assertThat(EventPresentation.displayLabel(ev(Category.UNLOGGED_FOOD, Direction.RISE, Phase.FINAL))).isEqualTo("Unaccounted carbs");
        assertThat(EventPresentation.displayLabel(ev(Category.LIPODYSTROPHY, Direction.RISE, Phase.FINAL)))
                .isEqualTo("Possible lipodystrophy / injection site issue");
    }
    @Test void everyCategoryHasALabel() {
        for (Category c : Category.values()) assertThat(EventPresentation.label(c)).isNotBlank();
    }
    @Test void promptOptions() {
        assertThat(EventPresentation.promptOptions(ev(Category.STRESS, Direction.RISE, Phase.PROVISIONAL)))
                .containsExactly(Category.STRESS, Category.ABSORPTION_DELAY, Category.LIPODYSTROPHY, Category.UNLOGGED_FOOD);
        assertThat(EventPresentation.promptOptions(ev(Category.UNLOGGED_INSULIN, Direction.FALL, Phase.PROVISIONAL)))
                .containsExactly(Category.UNLOGGED_INSULIN, Category.AEROBIC_ACTIVITY);
        assertThat(EventPresentation.promptOptions(ev(Category.UNLOGGED_FOOD, Direction.RISE, Phase.FINAL)))
                .containsExactly(Category.UNLOGGED_FOOD, Category.STRESS, Category.ANAEROBIC_ACTIVITY);
        assertThat(EventPresentation.promptOptions(ev(Category.AEROBIC_ACTIVITY, Direction.FALL, Phase.FINAL)))
                .startsWith(Category.AEROBIC_ACTIVITY).containsOnly(Category.forDirection(Direction.FALL).toArray(Category[]::new));
    }
    @Test void noLabelGivesDosingAdvice() {
        for (Category c : Category.values()) {
            assertThat(EventPresentation.label(c).toLowerCase()).doesNotContain("dose").doesNotContain("units");
        }
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests '*EventPresentationTest'`
Expected: FAIL to compile.

- [ ] **Step 3: Implement presentation and the DTOs**

`glycemic/EventPresentation.java`:

```java
package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-owned wording and one-tap choices (spec 5.2), so the app never needs a release to change them.
 * Wording never gives dosing advice.
 */
public final class EventPresentation {

    private EventPresentation() {}

    public static String displayLabel(GlycemicEvent e) {
        if (e.getPhase() == Phase.PROVISIONAL) {
            return e.getDirection() == Direction.RISE ? "Resistance?" : "Unexplained drop";
        }
        return label(e.getCategory());
    }

    public static String label(Category c) {
        return switch (c) {
            case UNLOGGED_FOOD -> "Unaccounted carbs";
            case UNDER_ESTIMATED_FOOD -> "More carbs than logged";
            case STRESS -> "Possible stress";
            case ABSORPTION_DELAY -> "Delayed insulin absorption";
            case LIPODYSTROPHY -> "Possible lipodystrophy / injection site issue";
            case DAWN_PHENOMENON -> "Dawn phenomenon";
            case ANAEROBIC_ACTIVITY -> "Strength exercise";
            case UNLOGGED_INSULIN -> "Unlogged insulin";
            case UNDER_ESTIMATED_INSULIN -> "More insulin than logged";
            case AEROBIC_ACTIVITY -> "Cardio exercise";
        };
    }

    public static List<Category> promptOptions(GlycemicEvent e) {
        if (e.getPhase() == Phase.PROVISIONAL && e.getDirection() == Direction.RISE) {
            return List.of(Category.STRESS, Category.ABSORPTION_DELAY, Category.LIPODYSTROPHY, Category.UNLOGGED_FOOD);
        }
        if (e.getPhase() == Phase.PROVISIONAL) {
            return List.of(Category.UNLOGGED_INSULIN, Category.AEROBIC_ACTIVITY);
        }
        if (e.getCategory() == Category.UNLOGGED_FOOD) {
            return List.of(Category.UNLOGGED_FOOD, Category.STRESS, Category.ANAEROBIC_ACTIVITY);
        }
        if (e.getCategory() == Category.LIPODYSTROPHY) {
            return List.of(Category.LIPODYSTROPHY, Category.ABSORPTION_DELAY, Category.STRESS);
        }
        List<Category> out = new ArrayList<>();
        out.add(e.getCategory());
        Category.forDirection(e.getCategory().direction()).stream().filter(c -> c != e.getCategory()).forEach(out::add);
        return out;
    }
}
```

Replace `dto/GlycemicEventDTO.java`:

```java
package che.glucosemonitorbe.dto;

import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.glycemic.EventPresentation;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** API view of a {@link GlycemicEvent}. Times are the user's local wall time; glucose is mmol/L. */
public record GlycemicEventDTO(
        UUID id,
        String category,
        String detectorCategory,
        String userLabel,
        String displayLabel,
        String direction,
        String phase,
        String state,
        @JsonFormat(pattern = TIME) LocalDateTime onsetAt,
        @JsonFormat(pattern = TIME) LocalDateTime windowStart,
        @JsonFormat(pattern = TIME) LocalDateTime windowEnd,
        @JsonFormat(pattern = TIME) LocalDateTime resolvesAt,
        @JsonFormat(pattern = TIME) LocalDateTime detectedAt,
        @JsonFormat(pattern = TIME) LocalDateTime resolvedAt,
        Double confidence,
        boolean suppressed,
        UUID linkedEventId,
        Double meanResidualMmol,
        Double sigmaMultiple,
        List<String> promptOptions) {

    /** The note-DTO convention the iOS decoder parses; LocalDateTime.now() would otherwise emit nanoseconds. */
    public static final String TIME = "yyyy-MM-dd'T'HH:mm:ss";

    public static GlycemicEventDTO from(GlycemicEvent e) {
        return new GlycemicEventDTO(e.getId(), e.getCategory().name(),
                e.getDetectorCategory() == null ? null : e.getDetectorCategory().name(),
                e.getUserLabel() == null ? null : e.getUserLabel().name(),
                EventPresentation.displayLabel(e), e.getDirection().name(), e.getPhase().name(), e.getState().name(),
                e.getOnsetAt(), e.getWindowStart(), e.getWindowEnd(), e.getResolvesAt(), e.getDetectedAt(),
                e.getResolvedAt(), e.getConfidence(), e.isSuppressed(), e.getLinkedEventId(),
                e.getMeanResidualMmol(), e.getSigmaMultiple(),
                EventPresentation.promptOptions(e).stream().map(Enum::name).toList());
    }
}
```

`dto/GlycemicEventHistoryDTO.java`:

```java
package che.glucosemonitorbe.dto;

import che.glucosemonitorbe.entity.GlycemicEventHistory;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/** One row of an event's timeline: detected -> resolved -> the user's answer. */
public record GlycemicEventHistoryDTO(
        String kind,
        @JsonFormat(pattern = GlycemicEventDTO.TIME) LocalDateTime at,
        String categoryBefore,
        String categoryAfter,
        Double confidence,
        String actor,
        String reason) {

    public static GlycemicEventHistoryDTO from(GlycemicEventHistory h) {
        return new GlycemicEventHistoryDTO(h.getKind().name(), h.getAt(),
                h.getCategoryBefore() == null ? null : h.getCategoryBefore().name(),
                h.getCategoryAfter() == null ? null : h.getCategoryAfter().name(),
                h.getConfidence(), h.getActor().name(), h.getReason());
    }
}
```

Replace `dto/ConfirmGlycemicEventRequest.java`:

```java
package che.glucosemonitorbe.dto;

/**
 * Body for confirming a glycemic event. {@code category} confirms or relabels (required for a PROVISIONAL
 * event; must match the event's direction). {@code carbs} may be backfilled only for a food category and
 * {@code insulin} only for an insulin category; either becomes a real note at the event's onset.
 */
public record ConfirmGlycemicEventRequest(String category, Double carbs, Double insulin) {}
```

Run: `./gradlew test --tests '*EventPresentationTest'`
Expected: PASS.

- [ ] **Step 4: Write the failing service unit tests (mock-first) and API E2E tests**

Replace `src/test/java/che/glucosemonitorbe/service/GlycemicEventServiceTest.java`:

```java
package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.GlycemicEventProperties;
import che.glucosemonitorbe.dto.ConfirmGlycemicEventRequest;
import che.glucosemonitorbe.dto.GlycemicEventDTO;
import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.*;
import che.glucosemonitorbe.entity.GlycemicEventHistory.Kind;
import che.glucosemonitorbe.repository.GlycemicEventHistoryRepository;
import che.glucosemonitorbe.repository.GlycemicEventRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GlycemicEventServiceTest {

    private final GlycemicEventRepository events = mock(GlycemicEventRepository.class);
    private final GlycemicEventHistoryRepository history = mock(GlycemicEventHistoryRepository.class);
    private final NoteRepository notes = mock(NoteRepository.class);
    private final UserSettingsService settings = mock(UserSettingsService.class);
    private final GlycemicEventHistoryRecorder recorder = mock(GlycemicEventHistoryRecorder.class);
    private final GlycemicLearnerTrainingCache cache = mock(GlycemicLearnerTrainingCache.class);
    private GlycemicEventService service;
    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new GlycemicEventService(events, history, notes, settings, new GlycemicEventProperties(), recorder, cache);
        when(events.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(events.findByLinkedEventId(any())).thenReturn(List.of());
    }

    private GlycemicEvent stored(UUID owner, Category c, Phase p, State s) {
        GlycemicEvent e = GlycemicEvent.builder().id(UUID.randomUUID()).userId(owner).category(c).detectorCategory(c)
                .direction(c.direction()).phase(p).onsetAt(LocalDateTime.now().minusHours(1))
                .windowStart(LocalDateTime.now().minusHours(1)).windowEnd(LocalDateTime.now())
                .meanResidualMmol(3.0).state(s).build();
        when(events.findById(e.getId())).thenReturn(Optional.of(e));
        return e;
    }

    private HttpStatus status(Runnable r) {
        return (HttpStatus) assertThrows(ResponseStatusException.class, r::run).getStatusCode();
    }

    @Test void foreignEventIs404() {
        GlycemicEvent e = stored(UUID.randomUUID(), Category.UNLOGGED_FOOD, Phase.FINAL, State.OPEN);
        assertThat(status(() -> service.confirm(user, e.getId(), null))).isEqualTo(HttpStatus.NOT_FOUND);
    }
    @Test void resolvedEventIs409BeforeAnyValidation() {
        GlycemicEvent e = stored(user, Category.UNLOGGED_FOOD, Phase.FINAL, State.DISMISSED);
        assertThat(status(() -> service.confirm(user, e.getId(), new ConfirmGlycemicEventRequest("NOPE", 999.0, null))))
                .isEqualTo(HttpStatus.CONFLICT);
    }
    @Test void unknownCategoryIs422() {
        GlycemicEvent e = stored(user, Category.UNLOGGED_FOOD, Phase.FINAL, State.OPEN);
        assertThat(status(() -> service.confirm(user, e.getId(), new ConfirmGlycemicEventRequest("NOPE", null, null))))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }
    @Test void wrongDirectionIs422() {
        GlycemicEvent e = stored(user, Category.UNLOGGED_FOOD, Phase.FINAL, State.OPEN);
        assertThat(status(() -> service.confirm(user, e.getId(), new ConfirmGlycemicEventRequest("UNLOGGED_INSULIN", null, null))))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }
    @Test void provisionalWithoutCategoryIs422() {
        GlycemicEvent e = stored(user, Category.STRESS, Phase.PROVISIONAL, State.OPEN);
        assertThat(status(() -> service.confirm(user, e.getId(), null))).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }
    @Test void backfillMustMatchCategory() {
        GlycemicEvent stress = stored(user, Category.STRESS, Phase.FINAL, State.OPEN);
        assertThat(status(() -> service.confirm(user, stress.getId(), new ConfirmGlycemicEventRequest(null, 20.0, null))))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        GlycemicEvent food = stored(user, Category.UNLOGGED_FOOD, Phase.FINAL, State.OPEN);
        assertThat(status(() -> service.confirm(user, food.getId(), new ConfirmGlycemicEventRequest(null, null, 2.0))))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        verify(notes, never()).save(any());
    }
    @Test void relabelSetsUserLabelKeepsDetectorCategoryAndFinalises() {
        GlycemicEvent e = stored(user, Category.STRESS, Phase.PROVISIONAL, State.OPEN);
        GlycemicEventDTO dto = service.confirm(user, e.getId(), new ConfirmGlycemicEventRequest("LIPODYSTROPHY", null, null));
        assertThat(dto.category()).isEqualTo("LIPODYSTROPHY");
        assertThat(dto.userLabel()).isEqualTo("LIPODYSTROPHY");
        assertThat(dto.detectorCategory()).isEqualTo("STRESS");
        assertThat(dto.phase()).isEqualTo("FINAL");
        assertThat(dto.state()).isEqualTo("CONFIRMED");
        verify(recorder).record(any(), eq(Kind.RELABELLED), eq(Category.STRESS), any(), isNull());
        verify(cache).invalidate(user);
    }
    @Test void dismissingAParentDismissesItsChildren() {
        GlycemicEvent parent = stored(user, Category.ABSORPTION_DELAY, Phase.FINAL, State.OPEN);
        GlycemicEvent child = GlycemicEvent.builder().id(UUID.randomUUID()).userId(user)
                .category(Category.ABSORPTION_DELAY).direction(Direction.FALL).linkedEventId(parent.getId())
                .state(State.OPEN).build();
        when(events.findByLinkedEventId(parent.getId())).thenReturn(List.of(child));
        service.dismiss(user, parent.getId());
        assertThat(child.getState()).isEqualTo(State.DISMISSED);
    }
}
```

`src/test/java/che/glucosemonitorbe/integration/GlycemicEventApiE2ETest.java`:

```java
package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.dto.ConfirmGlycemicEventRequest;
import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.*;
import che.glucosemonitorbe.entity.UserSettings;
import che.glucosemonitorbe.repository.UserSettingsRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GlycemicEventApiE2ETest extends AbstractGlycemicEventE2ETest {

    @Autowired UserSettingsRepository userSettingsRepository;

    private GlycemicEvent event(UUID u, Category c, Phase p, LocalDateTime onset, double confidence) {
        return eventRepository.save(GlycemicEvent.builder().userId(u).category(c).detectorCategory(c)
                .direction(c.direction()).phase(p).onsetAt(onset).windowStart(onset).windowEnd(onset.plusMinutes(50))
                .resolvesAt(p == Phase.PROVISIONAL ? onset.plusHours(6) : null)
                .meanResidualMmol(c.direction() == Direction.RISE ? 3.0 : -3.0).confidence(confidence)
                .state(State.OPEN).build());
    }

    private ResponseEntity<String> get(String path) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders), String.class);
    }

    private ResponseEntity<String> post(String path, Object body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, authHeaders), String.class);
    }

    private static LocalDateTime utcNow() { return LocalDateTime.now(ZoneOffset.UTC).withNano(0); }

    @Test
    @DisplayName("range list returns intersecting events; ranges over 31 days are 422")
    void rangeList() throws Exception {
        UUID u = userId();
        GlycemicEvent e = event(u, Category.UNLOGGED_FOOD, Phase.FINAL, utcNow().minusHours(2), 0.6);
        ResponseEntity<String> ok = get("/api/glycemic-events?from=" + utcNow().minusHours(3) + "&to=" + utcNow());
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode arr = mapper.readTree(ok.getBody());
        assertThat(arr).hasSize(1);
        assertThat(arr.get(0).path("id").asText()).isEqualTo(e.getId().toString());
        assertThat(arr.get(0).path("onsetAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
        assertThat(arr.get(0).path("displayLabel").asText()).isEqualTo("Unaccounted carbs");

        assertThat(get("/api/glycemic-events?from=" + utcNow().minusDays(40) + "&to=" + utcNow()).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("prompt picks the most confident fresh top-level event; 204 when none qualifies")
    void prompt() throws Exception {
        UUID u = userId();
        assertThat(get("/api/glycemic-events/prompt").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        event(u, Category.UNLOGGED_FOOD, Phase.FINAL, utcNow().minusHours(1), 0.4);               // too unsure
        GlycemicEvent suppressed = event(u, Category.STRESS, Phase.FINAL, utcNow().minusHours(1), 0.9);
        suppressed.setSuppressed(true);
        eventRepository.save(suppressed);
        event(u, Category.UNLOGGED_FOOD, Phase.FINAL, utcNow().minusHours(8), 0.9);               // stale
        GlycemicEvent best = event(u, Category.STRESS, Phase.PROVISIONAL, utcNow().minusHours(2), 0.7);
        eventRepository.save(GlycemicEvent.builder().userId(u).category(Category.STRESS).direction(Direction.FALL)
                .linkedEventId(best.getId()).windowStart(utcNow()).windowEnd(utcNow()).meanResidualMmol(-2.0)
                .confidence(0.95).state(State.OPEN).build());                                   // child

        JsonNode body = mapper.readTree(get("/api/glycemic-events/prompt").getBody());
        assertThat(body.path("id").asText()).isEqualTo(best.getId().toString());
        assertThat(body.path("displayLabel").asText()).isEqualTo("Resistance?");
        assertThat(body.path("promptOptions")).hasSize(4);
    }

    @Test
    @DisplayName("prompt age uses the user's clock (UTC+4)")
    void promptUsesUserClock() {
        UUID u = userId();
        ZoneId tbilisi = ZoneId.of("Asia/Tbilisi");
        UserSettings s = userSettingsRepository.findByUserId(u).orElseGet(() -> {
            UserSettings fresh = new UserSettings();
            fresh.setUserId(u);
            return fresh;
        });
        s.setTimezone(tbilisi.getId());
        s.setUtcOffsetMinutes(240);
        userSettingsRepository.save(s);
        LocalDateTime localNow = LocalDateTime.now(tbilisi).withNano(0);

        GlycemicEvent old = event(u, Category.UNLOGGED_FOOD, Phase.FINAL, localNow.minusHours(7), 0.9);
        assertThat(get("/api/glycemic-events/prompt").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        eventRepository.delete(old);
        event(u, Category.UNLOGGED_FOOD, Phase.FINAL, localNow.minusHours(5), 0.9);
        assertThat(get("/api/glycemic-events/prompt").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("relabel via API records RELABELLED and a history timeline")
    void relabelAndHistory() throws Exception {
        UUID u = userId();
        GlycemicEvent e = event(u, Category.UNLOGGED_FOOD, Phase.FINAL, utcNow().minusHours(1), 0.6);
        ResponseEntity<String> resp = post("/api/glycemic-events/" + e.getId() + "/confirm",
                new ConfirmGlycemicEventRequest("STRESS", null, null));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(resp.getBody()).path("userLabel").asText()).isEqualTo("STRESS");

        JsonNode h = mapper.readTree(get("/api/glycemic-events/" + e.getId() + "/history").getBody());
        assertThat(h.get(h.size() - 1).path("kind").asText()).isEqualTo("RELABELLED");
        assertThat(h.get(h.size() - 1).path("actor").asText()).isEqualTo("USER");
    }

    @Test
    @DisplayName("confirm validation over HTTP: 422 wrong direction, 422 provisional without category, 404 foreign history")
    void validation() {
        UUID u = userId();
        GlycemicEvent rise = event(u, Category.UNLOGGED_FOOD, Phase.FINAL, utcNow().minusHours(1), 0.6);
        assertThat(post("/api/glycemic-events/" + rise.getId() + "/confirm",
                new ConfirmGlycemicEventRequest("AEROBIC_ACTIVITY", null, null)).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        GlycemicEvent prov = event(u, Category.STRESS, Phase.PROVISIONAL, utcNow().minusHours(1), 0.6);
        assertThat(post("/api/glycemic-events/" + prov.getId() + "/confirm",
                new ConfirmGlycemicEventRequest(null, null, null)).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        HttpHeaders other = registerAndLogin();
        assertThat(rest.exchange("/api/glycemic-events/" + rise.getId() + "/history", HttpMethod.GET,
                new HttpEntity<>(other), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
```

- [ ] **Step 5: Run to verify failure**

Run: `./gradlew test --tests '*GlycemicEventServiceTest' --tests '*GlycemicEventApiE2ETest'`
Expected: FAIL to compile (new constructor, `confirm(UUID, UUID, ConfirmGlycemicEventRequest)`).

- [ ] **Step 6: Replace `GlycemicEventService`**

```java
package che.glucosemonitorbe.service;

import che.glucosemonitorbe.config.GlycemicEventProperties;
import che.glucosemonitorbe.domain.UserZones;
import che.glucosemonitorbe.dto.ConfirmGlycemicEventRequest;
import che.glucosemonitorbe.dto.GlycemicEventDTO;
import che.glucosemonitorbe.dto.GlycemicEventHistoryDTO;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.*;
import che.glucosemonitorbe.entity.GlycemicEventHistory.Actor;
import che.glucosemonitorbe.entity.GlycemicEventHistory.Kind;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.GlycemicEventHistoryRepository;
import che.glucosemonitorbe.repository.GlycemicEventRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;

/**
 * User-facing operations on glycemic events (spec section 5): range listing for the chart, the single
 * prompt, confirm/relabel with optional backfill, dismiss, and the per-event history timeline. Every
 * user answer writes a history row, mirrors onto linked children, and refreshes the learner.
 */
@Service
@RequiredArgsConstructor
public class GlycemicEventService {

    private static final Set<Category> FOOD = EnumSet.of(Category.UNLOGGED_FOOD, Category.UNDER_ESTIMATED_FOOD);
    private static final Set<Category> INSULIN = EnumSet.of(Category.UNLOGGED_INSULIN, Category.UNDER_ESTIMATED_INSULIN);

    private final GlycemicEventRepository eventRepository;
    private final GlycemicEventHistoryRepository historyRepository;
    private final NoteRepository noteRepository;
    private final UserSettingsService userSettingsService;
    private final GlycemicEventProperties props;
    private final GlycemicEventHistoryRecorder historyRecorder;
    private final GlycemicLearnerTrainingCache trainingCache;

    @Transactional(readOnly = true)
    public List<GlycemicEventDTO> listRange(UUID userId, LocalDateTime from, LocalDateTime to, State state) {
        if (from == null || to == null || !from.isBefore(to) || to.isAfter(from.plusDays(31))) {
            throw unprocessable("from must be before to, and the range at most 31 days");
        }
        return eventRepository
                .findByUserIdAndOnsetAtLessThanEqualAndWindowEndGreaterThanEqualOrderByOnsetAtAsc(userId, to, from)
                .stream().filter(e -> state == null || e.getState() == state)
                .map(GlycemicEventDTO::from).toList();
    }

    @Transactional(readOnly = true)
    public Optional<GlycemicEventDTO> prompt(UUID userId) {
        LocalDateTime oldest = LocalDateTime.now(userZone(userId)).minusHours(props.getPromptMaxAgeHours());
        return eventRepository.findByUserIdAndStateIn(userId, List.of(State.OPEN)).stream()
                .filter(e -> !e.isSuppressed() && e.getLinkedEventId() == null)
                .filter(e -> e.getConfidence() != null && e.getConfidence() >= props.getPromptMinConfidence())
                .filter(e -> !e.getOnsetAt().isBefore(oldest))
                .max(Comparator.comparing(GlycemicEvent::getConfidence).thenComparing(GlycemicEvent::getOnsetAt))
                .map(GlycemicEventDTO::from);
    }

    @Transactional
    public GlycemicEventDTO confirm(UUID userId, UUID eventId, ConfirmGlycemicEventRequest req) {
        GlycemicEvent event = requireOpen(userId, eventId);
        Category requested = parseCategory(req == null ? null : req.category());
        if (requested != null && requested.direction() != event.getDirection()) {
            throw unprocessable("Category " + requested + " does not fit a " + event.getDirection() + " event");
        }
        if (event.getPhase() == Phase.PROVISIONAL && requested == null) {
            throw unprocessable("Choose a category for a provisional event");
        }
        Category effective = requested != null ? requested : event.getCategory();
        double carbs = validateAmount(req == null ? null : req.carbs(), props.getBackfillMaxCarbs(), "carbs");
        double insulin = validateAmount(req == null ? null : req.insulin(), props.getBackfillMaxInsulin(), "insulin");
        if (carbs > 0 && !FOOD.contains(effective)) throw unprocessable("Carbs can only be added to a food event");
        if (insulin > 0 && !INSULIN.contains(effective)) throw unprocessable("Insulin can only be added to an insulin event");
        if (carbs > 0 || insulin > 0) backfill(userId, event.getOnsetAt(), carbs, insulin);

        Category before = event.getCategory();
        boolean relabel = requested != null && requested != before;
        if (relabel) {
            event.setUserLabel(requested);
            event.setCategory(requested);
        }
        resolve(event, State.CONFIRMED);
        GlycemicEvent saved = eventRepository.save(event);
        historyRecorder.record(saved, relabel ? Kind.RELABELLED : Kind.CONFIRMED, before, Actor.USER, null);
        mirrorChildren(saved);
        trainingCache.invalidate(userId);
        return GlycemicEventDTO.from(saved);
    }

    @Transactional
    public GlycemicEventDTO dismiss(UUID userId, UUID eventId) {
        GlycemicEvent event = requireOpen(userId, eventId);
        Category before = event.getCategory();
        resolve(event, State.DISMISSED);
        GlycemicEvent saved = eventRepository.save(event);
        historyRecorder.record(saved, Kind.DISMISSED, before, Actor.USER, null);
        mirrorChildren(saved);
        trainingCache.invalidate(userId);
        return GlycemicEventDTO.from(saved);
    }

    @Transactional(readOnly = true)
    public List<GlycemicEventHistoryDTO> history(UUID userId, UUID eventId) {
        eventRepository.findById(eventId).filter(e -> e.getUserId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
        return historyRepository.findByEventIdOrderByAtAsc(eventId).stream().map(GlycemicEventHistoryDTO::from).toList();
    }

    GlycemicEvent requireOpen(UUID userId, UUID eventId) {
        GlycemicEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
        if (!event.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"); // don't leak existence
        }
        if (event.getState() != State.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Event already resolved");
        }
        return event;
    }

    private void resolve(GlycemicEvent e, State state) {
        LocalDateTime now = LocalDateTime.now();
        e.setState(state);
        e.setPhase(Phase.FINAL);
        e.setResolvesAt(null);
        e.setResolvedAt(now);
        e.setUpdatedAt(now);
    }

    private void mirrorChildren(GlycemicEvent parent) {
        for (GlycemicEvent child : eventRepository.findByLinkedEventId(parent.getId())) {
            child.setState(parent.getState());
            child.setCategory(parent.getCategory());
            child.setResolvedAt(parent.getResolvedAt());
            child.setUpdatedAt(LocalDateTime.now());
            eventRepository.save(child);
        }
    }

    private void backfill(UUID userId, LocalDateTime at, double carbs, double insulin) {
        Note note = new Note();
        note.setUserId(userId);
        note.setTimestamp(at);
        note.setCarbs(carbs);
        note.setInsulin(insulin);
        note.setMeal("Unlogged (backfilled)");
        note.setType(Note.TYPE_NORMAL);
        note.setCreatedAt(LocalDateTime.now());
        note.setUpdatedAt(LocalDateTime.now());
        noteRepository.save(note);
    }

    private static Category parseCategory(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Category.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw unprocessable("Unknown category: " + raw);
        }
    }

    private static double validateAmount(Double v, double max, String name) {
        double x = v == null ? 0.0 : v;
        if (x < 0 || x > max) throw unprocessable("Invalid backfill " + name + ": " + v);
        return x;
    }

    private ZoneId userZone(UUID userId) {
        UserSettingsDTO s = userSettingsService.getUserSettings(userId);
        return s == null ? ZoneOffset.UTC : UserZones.resolve(s.getTimezone(), s.getUtcOffsetMinutes());
    }

    private static ResponseStatusException unprocessable(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
```

- [ ] **Step 7: Replace the controller**

```java
package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.ConfirmGlycemicEventRequest;
import che.glucosemonitorbe.dto.GlycemicEventDTO;
import che.glucosemonitorbe.dto.GlycemicEventHistoryDTO;
import che.glucosemonitorbe.entity.GlycemicEvent.State;
import che.glucosemonitorbe.service.FeatureToggleService;
import che.glucosemonitorbe.service.GlycemicEventService;
import che.glucosemonitorbe.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Glycemic events for the chart, the one-tap prompt, and the history view (spec section 5). */
@Tag(name = "Glycemic events", description = "Detected unexplained glucose moves, for user confirmation")
@RestController
@RequestMapping("/api/glycemic-events")
@RequiredArgsConstructor
public class GlycemicEventController {

    private final GlycemicEventService eventService;
    private final FeatureToggleService featureToggleService;
    private final UserService userService;

    @Operation(summary = "Events intersecting [from, to] (local wall time, at most 31 days)")
    @GetMapping
    public ResponseEntity<List<GlycemicEventDTO>> list(Authentication auth,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) State state) {
        requireFeature();
        return ResponseEntity.ok(eventService.listRange(userId(auth), from, to, state));
    }

    @Operation(summary = "The one event to prompt about now, or 204")
    @GetMapping("/prompt")
    public ResponseEntity<GlycemicEventDTO> prompt(Authentication auth) {
        requireFeature();
        return eventService.prompt(userId(auth)).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(summary = "Confirm or relabel; optionally backfill the real carbs/insulin as a note")
    @PostMapping("/{id}/confirm")
    public ResponseEntity<GlycemicEventDTO> confirm(Authentication auth, @PathVariable UUID id,
            @RequestBody(required = false) ConfirmGlycemicEventRequest body) {
        requireFeature();
        return ResponseEntity.ok(eventService.confirm(userId(auth), id, body));
    }

    @Operation(summary = "Dismiss (this wasn't anything)")
    @PostMapping("/{id}/dismiss")
    public ResponseEntity<GlycemicEventDTO> dismiss(Authentication auth, @PathVariable UUID id) {
        requireFeature();
        return ResponseEntity.ok(eventService.dismiss(userId(auth), id));
    }

    @Operation(summary = "The event's timeline, oldest first")
    @GetMapping("/{id}/history")
    public ResponseEntity<List<GlycemicEventHistoryDTO>> history(Authentication auth, @PathVariable UUID id) {
        requireFeature();
        return ResponseEntity.ok(eventService.history(userId(auth), id));
    }

    private void requireFeature() {
        if (!featureToggleService.isEnabled("glycemic-event-detection-enabled")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Glycemic event detection is not enabled");
        }
    }

    private UUID userId(Authentication auth) {
        return userService.getUserByUsername(auth.getName()).getId();
    }
}
```

- [ ] **Step 8: Update the legacy E2E cases to the new contract**

In `integration/GlycemicEventE2ETest.java`:
- T5: `new ConfirmGlycemicEventRequest(45.0, null)` becomes `new ConfirmGlycemicEventRequest(null, 45.0, null)`.
- T9b: `new ConfirmGlycemicEventRequest(30.0, null)` becomes `new ConfirmGlycemicEventRequest(null, 30.0, null)`.
- T10: build `String range = "?from=" + LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(1).withNano(0) + "&to=" + LocalDateTime.now(java.time.ZoneOffset.UTC).plusHours(1).withNano(0);`, then call `"/api/glycemic-events" + range` and `"/api/glycemic-events" + range + "&state=DISMISSED"`.

- [ ] **Step 9: Run the tests**

Run: `./gradlew test --tests '*GlycemicEventServiceTest' --tests '*GlycemicEventApiE2ETest' --tests '*GlycemicEvent*' --tests 'che.glucosemonitorbe.glycemic.*'`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add -A src/
git commit -m "feat(glycemic): range, prompt, relabel and history API with server-owned labels

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 15: Insights endpoint

**Files:**
- Create: `dto/GlycemicInsightsDTO.java`
- Modify: `service/GlycemicEventService.java` (add `insights`), `controller/GlycemicEventController.java` (add `GET /insights`)
- Create test: `src/test/java/che/glucosemonitorbe/integration/GlycemicEventInsightsE2ETest.java`

**Interfaces:**
- Produces: `GET /api/glycemic-events/insights?days=30` (`days ∈ [1, 90]`, else 422) returns `GlycemicInsightsDTO(int days, Map<String,Integer> counts, Map<String,List<Integer>> hourHistogram, int noEffectCount14d, Map<String,Double> agreementRate)`.

- [ ] **Step 1: Write the failing test**

```java
package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.entity.GlycemicEvent;
import che.glucosemonitorbe.entity.GlycemicEvent.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GlycemicEventInsightsE2ETest extends AbstractGlycemicEventE2ETest {

    private void ev(UUID u, Category c, LocalDateTime onset, State s, Category userLabel) {
        eventRepository.save(GlycemicEvent.builder().userId(u).category(userLabel != null ? userLabel : c)
                .detectorCategory(c).userLabel(userLabel).direction(c.direction())
                .onsetAt(onset).windowStart(onset).windowEnd(onset.plusMinutes(50)).meanResidualMmol(2.0)
                .state(s).build());
    }

    @Test
    void countsHoursAgreementAndRecurrence() throws Exception {
        UUID u = userId();
        LocalDateTime today10 = LocalDateTime.now(ZoneOffset.UTC).withHour(10).withMinute(0).withSecond(0).withNano(0).minusDays(1);
        ev(u, Category.STRESS, today10, State.CONFIRMED, null);                          // agreed
        ev(u, Category.STRESS, today10.minusDays(1), State.CONFIRMED, Category.UNLOGGED_FOOD); // relabelled
        ev(u, Category.LIPODYSTROPHY, today10.minusDays(2), State.OPEN, null);
        ev(u, Category.LIPODYSTROPHY, today10.minusDays(3), State.OPEN, null);

        ResponseEntity<String> resp = rest.exchange("/api/glycemic-events/insights?days=30", HttpMethod.GET,
                new HttpEntity<>(authHeaders), String.class);
        JsonNode body = mapper.readTree(resp.getBody());

        assertThat(body.path("counts").path("LIPODYSTROPHY").asInt()).isEqualTo(2);
        assertThat(body.path("counts").path("STRESS").asInt()).isEqualTo(1);
        assertThat(body.path("counts").path("UNLOGGED_FOOD").asInt()).isEqualTo(1);
        assertThat(body.path("hourHistogram").path("STRESS").get(10).asInt()).isEqualTo(1);
        assertThat(body.path("noEffectCount14d").asInt()).isEqualTo(2);
        assertThat(body.path("agreementRate").path("STRESS").asDouble()).isEqualTo(0.5);

        assertThat(rest.exchange("/api/glycemic-events/insights?days=91", HttpMethod.GET,
                new HttpEntity<>(authHeaders), String.class).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests '*GlycemicEventInsightsE2ETest'`
Expected: FAIL (404, because there's no endpoint yet).

- [ ] **Step 3: Implement**

`dto/GlycemicInsightsDTO.java`:

```java
package che.glucosemonitorbe.dto;

import java.util.List;
import java.util.Map;

/**
 * History-view summary. Counts and hour histograms are over top-level, non-dismissed events by effective
 * category; agreementRate is per detector category: confirmed-without-relabel / answered.
 */
public record GlycemicInsightsDTO(int days, Map<String, Integer> counts, Map<String, List<Integer>> hourHistogram,
                                  int noEffectCount14d, Map<String, Double> agreementRate) {}
```

Add to `GlycemicEventService` (imports `GlycemicInsightsDTO`, `java.util.stream.Collectors`):

```java
    @Transactional(readOnly = true)
    public GlycemicInsightsDTO insights(UUID userId, int days) {
        if (days < 1 || days > 90) throw unprocessable("days must be between 1 and 90");
        LocalDateTime now = LocalDateTime.now(userZone(userId));
        List<GlycemicEvent> all = eventRepository
                .findByUserIdAndOnsetAtLessThanEqualAndWindowEndGreaterThanEqualOrderByOnsetAtAsc(userId, now, now.minusDays(days))
                .stream().filter(e -> e.getLinkedEventId() == null).toList();

        Map<String, Integer> counts = new TreeMap<>();
        Map<String, List<Integer>> hours = new TreeMap<>();
        for (GlycemicEvent e : all) {
            if (e.getState() == State.DISMISSED) continue;
            String key = e.getCategory().name();
            counts.merge(key, 1, Integer::sum);
            List<Integer> h = hours.computeIfAbsent(key, k -> new ArrayList<>(Collections.nCopies(24, 0)));
            h.set(e.getOnsetAt().getHour(), h.get(e.getOnsetAt().getHour()) + 1);
        }
        int noEffect = (int) all.stream()
                .filter(e -> e.getCategory() == Category.LIPODYSTROPHY && e.getState() != State.DISMISSED)
                .filter(e -> e.getOnsetAt().isAfter(now.minusDays(14))).count();

        Map<String, Double> agreement = new TreeMap<>();
        Map<Category, List<GlycemicEvent>> answeredByDetector = all.stream()
                .filter(e -> e.getState() != State.OPEN && e.getDetectorCategory() != null)
                .collect(Collectors.groupingBy(GlycemicEvent::getDetectorCategory));
        answeredByDetector.forEach((cat, list) -> {
            long agreed = list.stream().filter(e -> e.getState() == State.CONFIRMED && e.getUserLabel() == null).count();
            agreement.put(cat.name(), Math.round(1000.0 * agreed / list.size()) / 1000.0);
        });
        return new GlycemicInsightsDTO(days, counts, hours, noEffect, agreement);
    }
```

Add to the controller:

```java
    @Operation(summary = "Per-category counts, hour histograms, lipodystrophy recurrence and agreement rate")
    @GetMapping("/insights")
    public ResponseEntity<GlycemicInsightsDTO> insights(Authentication auth, @RequestParam(defaultValue = "30") int days) {
        requireFeature();
        return ResponseEntity.ok(eventService.insights(userId(auth), days));
    }
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew test --tests '*GlycemicEventInsightsE2ETest' --tests '*GlycemicEvent*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A src/
git commit -m "feat(glycemic): insights endpoint for the history view

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 16: Offline validation on AZT1D and HUPA-UCM (data-gated, report-only)

**Files:**
- Create test support: `src/test/java/che/glucosemonitorbe/glycemic/GlycemicEventReplay.java`, `src/test/java/che/glucosemonitorbe/glycemic/GlycemicEventScorecard.java`
- Create tests: `src/test/java/che/glucosemonitorbe/hupa/HupaGlycemicEventValidationTest.java`, `src/test/java/che/glucosemonitorbe/azt1d/Azt1dGlycemicEventValidationTest.java`
- Modify: `hupa/HupaUcmCalibrationValidationTest.java` and `azt1d/Azt1dCalibrationValidationTest.java`: drop `private` from `rawPredictor()` and `personalParams(...)` so the same-package validation tests reuse them.

**Interfaces:**
- Consumes: `GlycemicEventDetector`, `EventClassifier`, `GlycemicEventProperties` defaults; `HupaUcmDataset.loadAll(Path)` / `Azt1dDataset.loadAll(Path)` returning subjects with `cgm()` (`PredictionReplayEngine.Reading(epochMs, mmol)`), `events()` (`PredictionReplayEngine.Event(epochMs, carbs, insulin, longActing, protein, fat, fiber, rescue)`) and `profile()`; HUPA subjects also have `activity()` (`ActivitySample(epochMs, hr, steps)`).
- Produces: `GlycemicEventReplay` with `List<Found> scan(List<CgmPoint>, List<Note>, long fromMs, long toMs, long strideMs)` and `static List<Note> toNotes(List<PredictionReplayEngine.Event>)`; `GlycemicEventScorecard` with `Row evaluate(String id, GlycemicEventReplay, List<CgmPoint>, List<Note>, long seed, List<long[]> stepWindows)`, `static String render(List<Row>)` and `static double meanPromptsPerDay(List<Row>)`.

Scope notes, which the rendered report must repeat:
- The replay runs detection and rule classification only. Tail resolution and learning need persisted history, so they aren't replayed, and provisional categories count as their provisional label.
- COB uses a linear 240-minute decay, not the per-user nutrition-aware model.
- Stress, absorption delay, lipodystrophy and dawn have no ground truth in either dataset.

- [ ] **Step 1: Write the replay and scorecard support**

`GlycemicEventReplay.java`:

```java
package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.GlycemicEvent.Category;
import che.glucosemonitorbe.entity.GlycemicEvent.Direction;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.glycemic.GlycemicEventDetector.CgmPoint;
import che.glucosemonitorbe.hovorka.HovorkaParameters;
import che.glucosemonitorbe.hovorka.learning.PredictionReplayEngine;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Drives the detector + rule classifier over a dataset record by sliding the scan end time. No DB. */
public final class GlycemicEventReplay {

    public record Found(Direction direction, Category category, long onsetMs, double confidence) {}

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final long DEDUPE_MS = 30 * 60_000L;

    private final GlycemicEventDetector detector;
    private final EventClassifier classifier;
    private final HovorkaParameters params;
    private final RapidInsulinIobParameters iob;
    private final int windowMinutes;

    public GlycemicEventReplay(GlycemicEventDetector detector, EventClassifier classifier, HovorkaParameters params,
                               RapidInsulinIobParameters iob, int windowMinutes) {
        this.detector = detector;
        this.classifier = classifier;
        this.params = params;
        this.iob = iob;
        this.windowMinutes = windowMinutes;
    }

    public List<Found> scan(List<CgmPoint> cgm, List<Note> notes, long fromMs, long toMs, long strideMs) {
        List<Found> found = new ArrayList<>();
        UserSettingsDTO settings = null;   // same as the calibration harnesses: population defaults
        long windowMs = windowMinutes * 60_000L, warmupMs = 8 * 3_600_000L;
        for (long end = fromMs; end <= toMs; end += strideMs) {
            long lo = end - windowMs, noteLo = lo - warmupMs, hi = end;
            List<CgmPoint> windowCgm = cgm.stream().filter(p -> p.epochMs() >= lo && p.epochMs() <= hi).toList();
            List<Note> windowNotes = notes.stream()
                    .filter(n -> { long t = ms(n.getTimestamp()); return t >= noteLo && t <= hi; }).toList();
            GlycemicEventDetector.Input in = new GlycemicEventDetector.Input(USER, ZoneOffset.UTC, toLdt(end),
                    windowCgm, windowNotes, params, iob, settings, t -> linearCob(windowNotes, t), false);
            for (GlycemicEventDetector.Detection d : detector.detect(in)) {
                boolean seen = found.stream().anyMatch(f -> f.direction() == d.direction()
                        && Math.abs(f.onsetMs() - d.onsetMs()) <= DEDUPE_MS);
                if (seen) continue;
                EventClassifier.Classification c = classifier.classify(d.direction(), d.features());
                found.add(new Found(d.direction(), c.category(), d.onsetMs(),
                        EventClassifier.confidence(c.baseConfidence(), d.features().sigmaMultiple())));
            }
        }
        return found;
    }

    public static List<Note> toNotes(List<PredictionReplayEngine.Event> events) {
        List<Note> out = new ArrayList<>();
        for (PredictionReplayEngine.Event e : events) {
            Note n = new Note();
            n.setTimestamp(toLdt(e.epochMs()));
            n.setCarbs(e.carbs());
            n.setInsulin(e.insulin());
            n.setType(e.longActing() ? Note.TYPE_LONG_ACTING : e.rescue() ? Note.TYPE_HYPO_TREATMENT : Note.TYPE_NORMAL);
            out.add(n);
        }
        return out;
    }

    /** Linear 240-minute carb decay - a deliberate simplification for replay (stated in the report). */
    static double linearCob(List<Note> notes, LocalDateTime t) {
        double cob = 0.0;
        for (Note n : notes) {
            if (n.getCarbs() == null || n.getCarbs() <= 0 || n.getTimestamp() == null) continue;
            double minsAgo = Duration.between(n.getTimestamp(), t).toMinutes();
            if (minsAgo >= 0 && minsAgo < 240) cob += n.getCarbs() * (1 - minsAgo / 240.0);
        }
        return cob;
    }

    static LocalDateTime toLdt(long ms) { return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC); }

    static long ms(LocalDateTime t) { return t.toInstant(ZoneOffset.UTC).toEpochMilli(); }
}
```

`GlycemicEventScorecard.java`:

```java
package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.entity.GlycemicEvent.Category;
import che.glucosemonitorbe.entity.GlycemicEvent.Direction;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.glycemic.GlycemicEventDetector.CgmPoint;
import che.glucosemonitorbe.glycemic.GlycemicEventReplay.Found;

import java.util.*;

/**
 * Hide-a-logged-event scoring: remove one real meal (or correction bolus) and check whether the engine
 * tags it; plus prompts per day on untouched data. Report-only apart from the prompt-rate gate.
 */
public final class GlycemicEventScorecard {

    public record Row(String subject, int meals, int mealsFound, int boluses, int bolusesFound,
                      double medianOnsetErrorMin, double promptsPerDay, int falls, int fallsWithSteps) {}

    private static final long MIN = 60_000L, H = 3_600_000L;
    private static final int MAX_HIDDEN = 20;

    private GlycemicEventScorecard() {}

    public static Row evaluate(String id, GlycemicEventReplay replay, List<CgmPoint> cgm, List<Note> notes,
                               long seed, List<long[]> stepWindows) {
        Random rnd = new Random(seed);
        List<Double> errors = new ArrayList<>();

        List<Note> meals = new ArrayList<>(notes.stream()
                .filter(n -> !n.isLongActing() && n.getCarbs() != null && n.getCarbs() >= 20).toList());
        Collections.shuffle(meals, rnd);
        meals = meals.subList(0, Math.min(MAX_HIDDEN, meals.size()));
        int mealsFound = 0;
        for (Note meal : meals) {
            long t = GlycemicEventReplay.ms(meal.getTimestamp());
            List<Note> without = notes.stream().filter(n -> n != meal).toList();
            Optional<Found> hit = replay.scan(cgm, without, t + 2 * H, t + 4 * H, H).stream()
                    .filter(f -> f.direction() == Direction.RISE)
                    .filter(f -> f.category() == Category.UNLOGGED_FOOD || f.category() == Category.UNDER_ESTIMATED_FOOD)
                    .filter(f -> f.onsetMs() >= t - 30 * MIN && f.onsetMs() <= t + 90 * MIN)
                    .findFirst();
            if (hit.isPresent()) { mealsFound++; errors.add((hit.get().onsetMs() - t) / (double) MIN); }
        }

        List<Note> corrections = new ArrayList<>(notes.stream()
                .filter(n -> !n.isLongActing() && n.getInsulin() != null && n.getInsulin() >= 2.0)
                .filter(b -> notes.stream().noneMatch(o -> o.getCarbs() != null && o.getCarbs() > 0
                        && Math.abs(GlycemicEventReplay.ms(o.getTimestamp()) - GlycemicEventReplay.ms(b.getTimestamp())) <= 30 * MIN))
                .toList());
        Collections.shuffle(corrections, rnd);
        corrections = corrections.subList(0, Math.min(MAX_HIDDEN, corrections.size()));
        int bolusesFound = 0;
        for (Note bolus : corrections) {
            long t = GlycemicEventReplay.ms(bolus.getTimestamp());
            List<Note> without = notes.stream().filter(n -> n != bolus).toList();
            boolean hit = replay.scan(cgm, without, t + 2 * H, t + 4 * H, H).stream()
                    .anyMatch(f -> f.direction() == Direction.FALL
                            && (f.category() == Category.UNLOGGED_INSULIN || f.category() == Category.UNDER_ESTIMATED_INSULIN)
                            && f.onsetMs() >= t && f.onsetMs() <= t + 120 * MIN);
            if (hit) bolusesFound++;
        }

        long first = cgm.get(0).epochMs(), last = cgm.get(cgm.size() - 1).epochMs();
        List<Found> untouched = replay.scan(cgm, notes, first + 4 * H, last, H);
        double days = Math.max(1.0, (last - first) / (24.0 * H));
        long prompts = untouched.stream().filter(f -> f.confidence() >= 0.5).count();
        List<Found> falls = untouched.stream().filter(f -> f.direction() == Direction.FALL).toList();
        int fallsWithSteps = (int) falls.stream().filter(f -> stepWindows.stream()
                .anyMatch(w -> f.onsetMs() >= w[0] - 30 * MIN && f.onsetMs() <= w[1] + 30 * MIN)).count();

        errors.sort(Double::compare);
        double median = errors.isEmpty() ? Double.NaN : errors.get(errors.size() / 2);
        return new Row(id, meals.size(), mealsFound, corrections.size(), bolusesFound, median,
                prompts / days, falls.size(), fallsWithSteps);
    }

    public static String render(List<Row> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("| subject | meals found | corrections found | median onset err (min) | prompts/day | falls w/ steps |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            sb.append(String.format(Locale.ROOT, "| %s | %d/%d | %d/%d | %.0f | %.2f | %d/%d |%n", r.subject(),
                    r.mealsFound(), r.meals(), r.bolusesFound(), r.boluses(), r.medianOnsetErrorMin(),
                    r.promptsPerDay(), r.fallsWithSteps(), r.falls()));
        }
        double meanPrompts = rows.stream().mapToDouble(Row::promptsPerDay).average().orElse(0);
        sb.append(String.format(Locale.ROOT, "%nMean prompts/day: %.2f%n", meanPrompts));
        sb.append("Scope: detection + rule classification only (no tail resolution / learning); linear COB; "
                + "no ground truth for stress, absorption delay, lipodystrophy or dawn.\n");
        return sb.toString();
    }

    public static double meanPromptsPerDay(List<Row> rows) {
        return rows.stream().mapToDouble(Row::promptsPerDay).average().orElse(0);
    }
}
```

- [ ] **Step 2: Write the two data-gated tests**

`src/test/java/che/glucosemonitorbe/hupa/HupaGlycemicEventValidationTest.java`:

```java
package che.glucosemonitorbe.hupa;

import che.glucosemonitorbe.config.GlycemicEventProperties;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.glycemic.*;
import che.glucosemonitorbe.glycemic.GlycemicEventDetector.CgmPoint;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Report-only validation of the glycemic event engine on HUPA-UCM. Skips unless -Dhupa.dir is set.
 * Hard gate: mean prompts/day <= 1.0 on untouched data (a noisy prompt gets ignored).
 * Run: ./gradlew test --tests '*HupaGlycemicEventValidationTest' -Dhupa.dir="/Users/vlad/Downloads/HUPA-UCM Diabetes Dataset"
 */
class HupaGlycemicEventValidationTest {

    @Test
    void scorecard() throws Exception {
        Path root = Path.of(System.getProperty("hupa.dir", ""));
        Assumptions.assumeTrue(!root.toString().isBlank() && Files.isDirectory(root.resolve("Preprocessed")),
                "HUPA-UCM dataset not present - set -Dhupa.dir to run");
        GlycemicEventProperties props = new GlycemicEventProperties();
        RapidInsulinIobParameters iob = new RapidInsulinIobParameters(4.5, 55.0);
        GlycemicEventDetector detector = new GlycemicEventDetector(HupaUcmCalibrationValidationTest.rawPredictor(), props);
        EventClassifier classifier = new EventClassifier(new EventClassifier.Config(props.getSignificantIobDropMmol(),
                props.getCobZeroGrams(), props.getDawnStartHour(), props.getDawnEndHour(), props.getDawnMaxSlope(),
                (int) (iob.diaHours() * 60), props.getDropResolveMinutes()));

        List<GlycemicEventScorecard.Row> rows = new ArrayList<>();
        for (HupaUcmDataset.Subject s : HupaUcmDataset.loadAll(root)) {
            if (s.cgm().size() < 500) continue;
            GlycemicEventReplay replay = new GlycemicEventReplay(detector, classifier,
                    HupaUcmCalibrationValidationTest.personalParams(s.profile()), iob, props.getWindowMinutes());
            List<CgmPoint> cgm = s.cgm().stream().map(r -> new CgmPoint(r.epochMs(), r.mmol())).toList();
            List<long[]> stepWindows = s.activity().stream().filter(a -> a.steps() >= 1000)
                    .map(a -> new long[]{a.epochMs(), a.epochMs() + 5 * 60_000L}).toList();
            rows.add(GlycemicEventScorecard.evaluate(s.id(), replay, cgm,
                    GlycemicEventReplay.toNotes(s.events()), 42L, stepWindows));
        }
        System.out.println(GlycemicEventScorecard.render(rows));
        assertThat(GlycemicEventScorecard.meanPromptsPerDay(rows)).isLessThanOrEqualTo(1.0);
    }
}
```

`src/test/java/che/glucosemonitorbe/azt1d/Azt1dGlycemicEventValidationTest.java`:

```java
package che.glucosemonitorbe.azt1d;

import che.glucosemonitorbe.config.GlycemicEventProperties;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.glycemic.*;
import che.glucosemonitorbe.glycemic.GlycemicEventDetector.CgmPoint;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Report-only validation of the glycemic event engine on AZT1D. Skips unless -Dazt1d.dir is set.
 * AZT1D has no step data, so the "falls with steps" column is always 0/n.
 * Hard gate: mean prompts/day <= 1.0 on untouched data.
 * Run: ./gradlew test --tests '*Azt1dGlycemicEventValidationTest' -Dazt1d.dir="/Users/vlad/Downloads/AZT1D  A Real-World Dataset for Type 1 Diabetes/AZT1D 2025"
 */
class Azt1dGlycemicEventValidationTest {

    @Test
    void scorecard() throws Exception {
        Path root = Path.of(System.getProperty("azt1d.dir", ""));
        Assumptions.assumeTrue(!root.toString().isBlank() && Files.isDirectory(root),
                "AZT1D dataset not present - set -Dazt1d.dir to run");
        GlycemicEventProperties props = new GlycemicEventProperties();
        RapidInsulinIobParameters iob = new RapidInsulinIobParameters(4.5, 55.0);
        GlycemicEventDetector detector = new GlycemicEventDetector(Azt1dCalibrationValidationTest.rawPredictor(), props);
        EventClassifier classifier = new EventClassifier(new EventClassifier.Config(props.getSignificantIobDropMmol(),
                props.getCobZeroGrams(), props.getDawnStartHour(), props.getDawnEndHour(), props.getDawnMaxSlope(),
                (int) (iob.diaHours() * 60), props.getDropResolveMinutes()));

        List<GlycemicEventScorecard.Row> rows = new ArrayList<>();
        for (Azt1dDataset.Subject s : Azt1dDataset.loadAll(root)) {
            if (s.cgm().size() < 500) continue;
            GlycemicEventReplay replay = new GlycemicEventReplay(detector, classifier,
                    Azt1dCalibrationValidationTest.personalParams(s.profile()), iob, props.getWindowMinutes());
            List<CgmPoint> cgm = s.cgm().stream().map(r -> new CgmPoint(r.epochMs(), r.mmol())).toList();
            rows.add(GlycemicEventScorecard.evaluate(s.id(), replay, cgm,
                    GlycemicEventReplay.toNotes(s.events()), 42L, List.of()));
        }
        System.out.println(GlycemicEventScorecard.render(rows));
        assertThat(GlycemicEventScorecard.meanPromptsPerDay(rows)).isLessThanOrEqualTo(1.0);
    }
}
```

- [ ] **Step 3: Make the harness helpers reusable**

In `HupaUcmCalibrationValidationTest` and `Azt1dCalibrationValidationTest`, change `private static HovorkaGlucosePredictionService rawPredictor()` and `private static HovorkaParameters personalParams(...)` to package-private `static`.

- [ ] **Step 4: Check that they skip cleanly without data, then run with data**

Run: `./gradlew test --tests '*GlycemicEventValidationTest'`
Expected: both SKIPPED (assumption not met).
Run: `./gradlew test --tests '*HupaGlycemicEventValidationTest' -Dhupa.dir="/Users/vlad/Downloads/HUPA-UCM Diabetes Dataset" --info | grep -A40 "| subject"`
Run: `./gradlew test --tests '*Azt1dGlycemicEventValidationTest' -Dazt1d.dir="/Users/vlad/Downloads/AZT1D  A Real-World Dataset for Type 1 Diabetes/AZT1D 2025" --info | grep -A40 "| subject"`
Expected: two scorecard tables. **If the prompts/day gate fails, stop.** Don't tune thresholds. Report both tables to the user: threshold tuning is their decision (spec §7.3). If the dataset paths don't exist on this machine, report that and move on.

- [ ] **Step 5: Commit**

```bash
git add -A src/test/
git commit -m "test(glycemic): data-gated AZT1D/HUPA scorecard with a prompts-per-day gate

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 6: Full backend suite**

Run: `./gradlew test > /tmp/gm-final.log 2>&1; grep -E "FAILED" /tmp/gm-final.log`
Expected: only the Task 1 Step 1 baseline failures.

---

## Phase 3 — iOS (`glucose-monitor-iphone/`)

Common to every iOS task:

- **Build (simulator, no signing).** Run:
  `xcodebuild -scheme GlucoseMonitor -project GlucoseMonitor.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 16,OS=18.3.1' -derivedDataPath /tmp/gm-derived CODE_SIGNING_ALLOWED=NO build > /tmp/gm-ios-build.log 2>&1; echo exit=$?; grep -c "BUILD SUCCEEDED" /tmp/gm-ios-build.log`
  Pass means `exit=0` and a count of 1. Never pipe `xcodebuild` into `tail`/`head` and read `$?`.
- **Tests.** The same command with `test -only-testing:GlucoseMonitorTests/<Class>` in place of `build`. The log goes to `/tmp/gm-ios-test.log`; grep it for `** TEST SUCCEEDED **`.
- **SourceKit warnings.** SourceKit "No such module 'UIKit'" diagnostics are spurious here. Trust `xcodebuild`.
- **Adding a Swift file to the project.** Every new file is registered in `project.pbxproj` with the helper below. Create it once, in `/tmp`, not in the repo:

```bash
cat > /tmp/gm-add-swift.py <<'EOF'
#!/usr/bin/env python3
"""Register a new Swift file next to an existing one of the same target: file ref, group child,
build file, sources phase. Usage: gm-add-swift.py <anchor-file> <new-file> <hex-suffix, e.g. A01>"""
import re, sys
proj = 'GlucoseMonitor.xcodeproj/project.pbxproj'
anchor, new, suffix = sys.argv[1:4]
s = open(proj).read()
ref_id, bld_id = '6' + suffix.rjust(23, '0'), '7' + suffix.rjust(23, '0')
assert ref_id not in s and bld_id not in s, 'id already used - pick another suffix'
m_ref = re.search(r'(\t\t(\w{24}) /\* ' + re.escape(anchor) + r' \*/ = \{isa = PBXFileReference;[^\n]*\n)', s)
m_bld = re.search(r'(\t\t(\w{24}) /\* ' + re.escape(anchor) + r' in Sources \*/ = \{isa = PBXBuildFile;[^\n]*\n)', s)
assert m_ref and m_bld, 'anchor not found'
a_ref, a_bld = m_ref.group(2), m_bld.group(2)
s = s.replace(m_bld.group(1), m_bld.group(1) + f'\t\t{bld_id} /* {new} in Sources */ = {{isa = PBXBuildFile; fileRef = {ref_id} /* {new} */; }};\n', 1)
s = s.replace(m_ref.group(1), m_ref.group(1) + f'\t\t{ref_id} /* {new} */ = {{isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = {new}; sourceTree = "<group>"; }};\n', 1)
g = f'\t\t\t\t{a_ref} /* {anchor} */,\n'
p = f'\t\t\t\t{a_bld} /* {anchor} in Sources */,\n'
assert s.count(g) == 1 and s.count(p) == 1, 'anchor group/sources entry not unique'
s = s.replace(g, g + f'\t\t\t\t{ref_id} /* {new} */,\n').replace(p, p + f'\t\t\t\t{bld_id} /* {new} in Sources */,\n')
open(proj, 'w').write(s)
print('added', new, ref_id, bld_id)
EOF
```

  App files use the anchor `HypoPromptView.swift`, and test files use `HypoPromptTests.swift`. Suffixes are pre-allocated per file, in each task's **Files** block.

### Task 17: API models and calls

**Files:**
- Create: `GlucoseMonitor/BackendAPI+Glycemic.swift` (suffix `A01`)
- Modify: `GlucoseMonitor/BackendAPI.swift`: make `authorizedRequest(path:method:)`, `performWithRefresh(_:)` and `checkStatus(_:data:)` `static` (internal) instead of `private static`, so the extension file can use them
- Create test: `GlucoseMonitorTests/GlycemicEventDecodingTests.swift` (suffix `A06`)

**Interfaces:**
- Consumes: the Task 14/15 REST contract.
- Produces:
  - `BackendAPI.GlycemicEvent` (`Decodable, Identifiable, Equatable`), with fields `id, category, detectorCategory, userLabel, displayLabel, direction, phase, state, onsetAt: Date, windowStart: Date, windowEnd: Date, resolvesAt: Date?, confidence: Double?, suppressed: Bool, linkedEventId: String?, meanResidualMmol: Double, promptOptions: [String]` and computed properties `isProvisional`, `isRise`, `isChild`.
  - `BackendAPI.GlycemicEventHistoryEntry` and `BackendAPI.GlycemicInsights`.
  - `enum GlycemicCategory` with `buttonTitle(_:)`, `takesCarbs(_:)`, `takesInsulin(_:)`.
  - Calls:
    - `fetchGlycemicEvents(from:to:) -> [GlycemicEvent]`
    - `fetchGlycemicPrompt() -> GlycemicEvent?` (nil on 204)
    - `confirmGlycemicEvent(id:category:carbs:insulin:) -> GlycemicEvent`
    - `dismissGlycemicEvent(id:) -> GlycemicEvent`
    - `fetchGlycemicEventHistory(id:) -> [GlycemicEventHistoryEntry]`
    - `fetchGlycemicInsights(days:) -> GlycemicInsights`

- [ ] **Step 1: Write the failing test**

```swift
import XCTest
@testable import GlucoseMonitor

/// Pins the JSON shapes the backend sends for glycemic events, decoded with the exact decoder the
/// network path uses. Unknown categories must decode (the server may add categories without a release).
final class GlycemicEventDecodingTests: XCTestCase {

    private let provisional = """
    {"id":"e1","category":"STRESS","detectorCategory":"STRESS","userLabel":null,"displayLabel":"Resistance?",
     "direction":"RISE","phase":"PROVISIONAL","state":"OPEN","onsetAt":"2026-09-30T11:40:00",
     "windowStart":"2026-09-30T11:55:00","windowEnd":"2026-09-30T12:45:00","resolvesAt":"2026-09-30T17:40:00",
     "detectedAt":"2026-09-30T12:46:00","resolvedAt":null,"confidence":0.5,"suppressed":false,
     "linkedEventId":null,"meanResidualMmol":3.1,"sigmaMultiple":4.2,
     "promptOptions":["STRESS","ABSORPTION_DELAY","LIPODYSTROPHY","UNLOGGED_FOOD"]}
    """

    func testDecodesProvisionalEvent() throws {
        let e = try GlucoseMonitorAPI.jsonDecoder().decode(BackendAPI.GlycemicEvent.self, from: Data(provisional.utf8))
        XCTAssertEqual(e.displayLabel, "Resistance?")
        XCTAssertTrue(e.isProvisional)
        XCTAssertTrue(e.isRise)
        XCTAssertFalse(e.isChild)
        XCTAssertEqual(e.promptOptions.count, 4)
        XCTAssertNotNil(e.resolvesAt)
        XCTAssertLessThan(e.onsetAt, e.windowStart)
    }

    func testUnknownCategoryStillDecodes() throws {
        let json = provisional.replacingOccurrences(of: "\"category\":\"STRESS\"", with: "\"category\":\"FUTURE_THING\"")
        let e = try GlucoseMonitorAPI.jsonDecoder().decode(BackendAPI.GlycemicEvent.self, from: Data(json.utf8))
        XCTAssertEqual(e.category, "FUTURE_THING")
        XCTAssertEqual(GlycemicCategory.buttonTitle("FUTURE_THING"), "Future Thing")
    }

    func testDecodesChildWithNulls() throws {
        let json = provisional
            .replacingOccurrences(of: "\"linkedEventId\":null", with: "\"linkedEventId\":\"p1\"")
            .replacingOccurrences(of: "\"resolvesAt\":\"2026-09-30T17:40:00\"", with: "\"resolvesAt\":null")
            .replacingOccurrences(of: "\"confidence\":0.5", with: "\"confidence\":null")
        let e = try GlucoseMonitorAPI.jsonDecoder().decode(BackendAPI.GlycemicEvent.self, from: Data(json.utf8))
        XCTAssertTrue(e.isChild)
        XCTAssertNil(e.resolvesAt)
        XCTAssertNil(e.confidence)
    }

    func testDecodesInsightsAndHistory() throws {
        let insights = """
        {"days":30,"counts":{"STRESS":2},"hourHistogram":{"STRESS":[0,0,0,0,0,0,0,0,0,0,2,0,0,0,0,0,0,0,0,0,0,0,0,0]},
         "noEffectCount14d":1,"agreementRate":{"STRESS":0.5}}
        """
        let i = try GlucoseMonitorAPI.jsonDecoder().decode(BackendAPI.GlycemicInsights.self, from: Data(insights.utf8))
        XCTAssertEqual(i.hourHistogram["STRESS"]?[10], 2)
        let history = """
        [{"kind":"DETECTED","at":"2026-09-30T12:46:00","categoryBefore":null,"categoryAfter":"STRESS",
          "confidence":0.5,"actor":"SYSTEM","reason":"rule:R4"}]
        """
        let h = try GlucoseMonitorAPI.jsonDecoder().decode([BackendAPI.GlycemicEventHistoryEntry].self, from: Data(history.utf8))
        XCTAssertEqual(h.first?.reason, "rule:R4")
    }

    func testButtonTitlesAndAmountKinds() {
        XCTAssertEqual(GlycemicCategory.buttonTitle("UNLOGGED_FOOD"), "I ate")
        XCTAssertEqual(GlycemicCategory.buttonTitle("LIPODYSTROPHY"), "Injection site")
        XCTAssertTrue(GlycemicCategory.takesCarbs("UNLOGGED_FOOD"))
        XCTAssertFalse(GlycemicCategory.takesCarbs("STRESS"))
        XCTAssertTrue(GlycemicCategory.takesInsulin("UNLOGGED_INSULIN"))
    }

    func testPrompt204DecodesAsNil() async throws {
        GlucoseMonitorAPI.storeSessionTokens(accessToken: "test-token", refreshToken: nil)
        StubURLProtocol.reset()
        URLProtocol.registerClass(StubURLProtocol.self)
        defer {
            URLProtocol.unregisterClass(StubURLProtocol.self)
            StubURLProtocol.reset()
            GlucoseMonitorAPI.clearSession()
        }
        StubURLProtocol.enqueue(path: "/api/glycemic-events/prompt", .init(status: 204))
        let prompt = try await BackendAPI.fetchGlycemicPrompt()
        XCTAssertNil(prompt)
    }
}
```

- [ ] **Step 2: Register the test file and verify failure**

Run: `python3 /tmp/gm-add-swift.py HypoPromptTests.swift GlycemicEventDecodingTests.swift A06`, then the test command with `-only-testing:GlucoseMonitorTests/GlycemicEventDecodingTests`.
Expected: the build fails (`BackendAPI.GlycemicEvent` doesn't exist).

- [ ] **Step 3: Implement**

In `BackendAPI.swift`, change `private static func authorizedRequest`, `private static func performWithRefresh` and `private static func checkStatus` to `static func …`.

`GlucoseMonitor/BackendAPI+Glycemic.swift`:

```swift
import Foundation

// MARK: - Glycemic events (backend /api/glycemic-events)

extension BackendAPI {

    /// A detected unexplained glucose move. Times are the user's local wall time (naive
    /// `yyyy-MM-dd'T'HH:mm:ss`), which `GlucoseMonitorAPI.jsonDecoder()` reads in `TimeZone.current` -
    /// the same convention as notes, so markers line up with note markers. `category` and
    /// `promptOptions` are raw strings so categories the app doesn't know yet still decode.
    struct GlycemicEvent: Decodable, Identifiable, Equatable {
        let id: String
        let category: String
        let detectorCategory: String?
        let userLabel: String?
        let displayLabel: String
        let direction: String
        let phase: String
        let state: String
        let onsetAt: Date
        let windowStart: Date
        let windowEnd: Date
        let resolvesAt: Date?
        let confidence: Double?
        let suppressed: Bool
        let linkedEventId: String?
        let meanResidualMmol: Double
        let promptOptions: [String]

        var isProvisional: Bool { phase == "PROVISIONAL" }
        var isRise: Bool { direction == "RISE" }
        var isChild: Bool { linkedEventId != nil }
    }

    struct GlycemicEventHistoryEntry: Decodable, Equatable {
        let kind: String
        let at: Date
        let categoryBefore: String?
        let categoryAfter: String?
        let confidence: Double?
        let actor: String
        let reason: String?
    }

    struct GlycemicInsights: Decodable, Equatable {
        let days: Int
        let counts: [String: Int]
        let hourHistogram: [String: [Int]]
        let noEffectCount14d: Int
        let agreementRate: [String: Double]
    }

    private struct ConfirmGlycemicEventBody: Encodable {
        let category: String?
        let carbs: Double?
        let insulin: Double?
    }

    static func fetchGlycemicEvents(from: Date, to: Date) async throws -> [GlycemicEvent] {
        try await performWithRefresh {
            let f = formatNoteTimestampForRequest(from), t = formatNoteTimestampForRequest(to)
            let req = try authorizedRequest(path: "/api/glycemic-events?from=\(f)&to=\(t)")
            let (data, resp) = try await URLSession.shared.data(for: req)
            try checkStatus(resp, data: data)
            return try GlucoseMonitorAPI.jsonDecoder().decode([GlycemicEvent].self, from: data)
        }
    }

    /// The one event to prompt about now; nil when the server answers 204.
    static func fetchGlycemicPrompt() async throws -> GlycemicEvent? {
        try await performWithRefresh {
            let req = try authorizedRequest(path: "/api/glycemic-events/prompt")
            let (data, resp) = try await URLSession.shared.data(for: req)
            if (resp as? HTTPURLResponse)?.statusCode == 204 { return nil }
            try checkStatus(resp, data: data)
            return try GlucoseMonitorAPI.jsonDecoder().decode(GlycemicEvent.self, from: data)
        }
    }

    static func confirmGlycemicEvent(id: String, category: String?, carbs: Double?, insulin: Double?) async throws -> GlycemicEvent {
        try await performWithRefresh {
            var req = try authorizedRequest(path: "/api/glycemic-events/\(id)/confirm", method: "POST")
            req.httpBody = try JSONEncoder().encode(ConfirmGlycemicEventBody(category: category, carbs: carbs, insulin: insulin))
            let (data, resp) = try await URLSession.shared.data(for: req)
            try checkStatus(resp, data: data)
            return try GlucoseMonitorAPI.jsonDecoder().decode(GlycemicEvent.self, from: data)
        }
    }

    static func dismissGlycemicEvent(id: String) async throws -> GlycemicEvent {
        try await performWithRefresh {
            let req = try authorizedRequest(path: "/api/glycemic-events/\(id)/dismiss", method: "POST")
            let (data, resp) = try await URLSession.shared.data(for: req)
            try checkStatus(resp, data: data)
            return try GlucoseMonitorAPI.jsonDecoder().decode(GlycemicEvent.self, from: data)
        }
    }

    static func fetchGlycemicEventHistory(id: String) async throws -> [GlycemicEventHistoryEntry] {
        try await performWithRefresh {
            let req = try authorizedRequest(path: "/api/glycemic-events/\(id)/history")
            let (data, resp) = try await URLSession.shared.data(for: req)
            try checkStatus(resp, data: data)
            return try GlucoseMonitorAPI.jsonDecoder().decode([GlycemicEventHistoryEntry].self, from: data)
        }
    }

    static func fetchGlycemicInsights(days: Int = 30) async throws -> GlycemicInsights {
        try await performWithRefresh {
            let req = try authorizedRequest(path: "/api/glycemic-events/insights?days=\(days)")
            let (data, resp) = try await URLSession.shared.data(for: req)
            try checkStatus(resp, data: data)
            return try GlucoseMonitorAPI.jsonDecoder().decode(GlycemicInsights.self, from: data)
        }
    }
}

/// Short one-tap button titles for server categories. Unknown categories fall back to a title-cased name.
enum GlycemicCategory {
    private static let titles: [String: String] = [
        "UNLOGGED_FOOD": "I ate",
        "UNDER_ESTIMATED_FOOD": "Ate more",
        "STRESS": "Stress",
        "ABSORPTION_DELAY": "Late insulin",
        "LIPODYSTROPHY": "Injection site",
        "DAWN_PHENOMENON": "Dawn",
        "ANAEROBIC_ACTIVITY": "Strength workout",
        "UNLOGGED_INSULIN": "Took insulin",
        "UNDER_ESTIMATED_INSULIN": "More insulin",
        "AEROBIC_ACTIVITY": "Cardio",
    ]

    static func buttonTitle(_ raw: String) -> String {
        titles[raw] ?? raw.replacingOccurrences(of: "_", with: " ").capitalized
    }

    static func takesCarbs(_ raw: String) -> Bool { raw == "UNLOGGED_FOOD" || raw == "UNDER_ESTIMATED_FOOD" }

    static func takesInsulin(_ raw: String) -> Bool { raw == "UNLOGGED_INSULIN" || raw == "UNDER_ESTIMATED_INSULIN" }
}
```

Register it: `python3 /tmp/gm-add-swift.py HypoPromptView.swift BackendAPI+Glycemic.swift A01`.

- [ ] **Step 4: Run to verify pass**

Run the test command with `-only-testing:GlucoseMonitorTests/GlycemicEventDecodingTests`.
Expected: `** TEST SUCCEEDED **`.

- [ ] **Step 5: Commit**

```bash
git add GlucoseMonitor/BackendAPI.swift GlucoseMonitor/BackendAPI+Glycemic.swift GlucoseMonitorTests/GlycemicEventDecodingTests.swift GlucoseMonitor.xcodeproj/project.pbxproj
git commit -m "feat(glycemic): API models and calls for detected glucose events

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 18: `AppState` — fetch, prompt, answer, snooze

**Files:**
- Modify: `GlucoseMonitor/AppState.swift` (stored state; add both fetches to `refreshAll` and the periodic refresh)
- Create: `GlucoseMonitor/AppState+Glycemic.swift` (suffix `A0A`)
- Create test: `GlucoseMonitorTests/GlycemicEventAppStateTests.swift` (suffix `A07`)

**Interfaces:**
- Consumes: Task 17 calls; existing `openHypoEvent`, `errorMessage`.
- Produces:
  - `@Published var glycemicEvents: [BackendAPI.GlycemicEvent]`, `@Published var glycemicPrompt: BackendAPI.GlycemicEvent?` and `@Published var isAnsweringGlycemicPrompt: Bool`.
  - `var visibleGlycemicPrompt: BackendAPI.GlycemicEvent?`, which is nil while a hypo prompt is open or the event is snoozed.
  - Methods:
    - `func refreshGlycemicEvents() async` (the last 24 h through +1 h)
    - `func refreshGlycemicPrompt() async`
    - `func answerGlycemicEvent(_:category:carbs:insulin:) async -> Bool`
    - `func answerGlycemicPrompt(category:carbs:insulin:) async -> Bool`
    - `func dismissGlycemicEvent(_:) async -> Bool`
    - `func dismissGlycemicPrompt() async -> Bool`
    - `func snoozeGlycemicPrompt()`
  - `static let glycemicSnoozeKey = "glycemicSnoozedEventIds"`.

- [ ] **Step 1: Write the failing test**

```swift
import XCTest
@testable import GlucoseMonitor

@MainActor
final class GlycemicEventAppStateTests: XCTestCase {

    private func event(_ id: String) -> BackendAPI.GlycemicEvent {
        BackendAPI.GlycemicEvent(id: id, category: "UNLOGGED_FOOD", detectorCategory: "UNLOGGED_FOOD", userLabel: nil,
                                 displayLabel: "Unaccounted carbs", direction: "RISE", phase: "FINAL", state: "OPEN",
                                 onsetAt: Date().addingTimeInterval(-3600), windowStart: Date().addingTimeInterval(-3300),
                                 windowEnd: Date().addingTimeInterval(-600), resolvesAt: nil, confidence: 0.6,
                                 suppressed: false, linkedEventId: nil, meanResidualMmol: 3.0,
                                 promptOptions: ["UNLOGGED_FOOD", "STRESS", "ANAEROBIC_ACTIVITY"])
    }

    private let confirmedJSON = Data("""
    {"id":"g1","category":"STRESS","detectorCategory":"UNLOGGED_FOOD","userLabel":"STRESS","displayLabel":"Possible stress",
     "direction":"RISE","phase":"FINAL","state":"CONFIRMED","onsetAt":"2026-09-30T11:40:00",
     "windowStart":"2026-09-30T11:55:00","windowEnd":"2026-09-30T12:45:00","resolvesAt":null,
     "confidence":0.6,"suppressed":false,"linkedEventId":null,"meanResidualMmol":3.1,"promptOptions":["STRESS"]}
    """.utf8)

    override func setUp() {
        super.setUp()
        UserDefaults.standard.removeObject(forKey: AppState.glycemicSnoozeKey)
    }

    override func tearDown() {
        UserDefaults.standard.removeObject(forKey: AppState.glycemicSnoozeKey)
        URLProtocol.unregisterClass(StubURLProtocol.self)
        StubURLProtocol.reset()
        GlucoseMonitorAPI.clearSession()
        super.tearDown()
    }

    func testHypoPromptTakesPrecedence() {
        let s = AppState()
        s.glycemicPrompt = event("g1")
        XCTAssertEqual(s.visibleGlycemicPrompt?.id, "g1")
        s.openHypoEvent = BackendAPI.HypoEvent(id: "h1", triggerGlucoseMmol: 3.2, state: "OPEN",
                                               noteId: nil, detectedAt: nil, resolvedAt: nil)
        XCTAssertNil(s.visibleGlycemicPrompt, "a hypo always wins over an event question")
    }

    func testSnoozeHidesOnlyThisEventOnThisDevice() {
        let s = AppState()
        s.glycemicPrompt = event("g1")
        s.snoozeGlycemicPrompt()
        XCTAssertNil(s.visibleGlycemicPrompt)
        s.glycemicPrompt = event("g2")
        XCTAssertEqual(s.visibleGlycemicPrompt?.id, "g2")
    }

    func testFailedRefreshKeepsTheCurrentPrompt() async {
        let s = AppState()
        s.glycemicPrompt = event("g1")
        URLProtocol.registerClass(FailingURLProtocol.self)
        defer { URLProtocol.unregisterClass(FailingURLProtocol.self) }
        await s.refreshGlycemicPrompt()
        XCTAssertEqual(s.glycemicPrompt?.id, "g1")
    }

    func testFailedConfirmReleasesControlsAndKeepsPrompt() async {
        GlucoseMonitorAPI.storeSessionTokens(accessToken: "test-token", refreshToken: nil)
        StubURLProtocol.reset()
        URLProtocol.registerClass(StubURLProtocol.self)
        StubURLProtocol.enqueue(path: "/api/glycemic-events/g1/confirm", .jsonError(status: 500, message: "boom"))
        let s = AppState()
        s.glycemicPrompt = event("g1")
        let ok = await s.answerGlycemicPrompt(category: "STRESS")
        XCTAssertFalse(ok)
        XCTAssertFalse(s.isAnsweringGlycemicPrompt, "controls must come back after a failure")
        XCTAssertEqual(s.glycemicPrompt?.id, "g1")
    }

    func testDoubleTapConfirmsOnce() async {
        GlucoseMonitorAPI.storeSessionTokens(accessToken: "test-token", refreshToken: nil)
        StubURLProtocol.reset()
        URLProtocol.registerClass(StubURLProtocol.self)
        StubURLProtocol.enqueue(path: "/api/glycemic-events/g1/confirm", .init(status: 200, body: confirmedJSON))
        let s = AppState()
        s.glycemicPrompt = event("g1")
        async let a: Bool = s.answerGlycemicPrompt(category: "STRESS")
        async let b: Bool = s.answerGlycemicPrompt(category: "UNLOGGED_FOOD")
        _ = await (a, b)
        XCTAssertEqual(StubURLProtocol.hits(for: "/api/glycemic-events/g1/confirm"), 1)
        XCTAssertNil(s.glycemicPrompt)
    }
}
```

- [ ] **Step 2: Register the test and verify failure**

Run: `python3 /tmp/gm-add-swift.py HypoPromptTests.swift GlycemicEventAppStateTests.swift A07`, then run the tests for `GlycemicEventAppStateTests`.
Expected: the build fails (the new `AppState` members don't exist).

- [ ] **Step 3: Implement**

In `AppState.swift`, next to `@Published var openHypoEvent`, add:

```swift
    /// Glycemic events for the chart's recent range (see `AppState+Glycemic.swift`).
    @Published var glycemicEvents: [BackendAPI.GlycemicEvent] = []
    /// The one event the server wants to ask about now.
    @Published var glycemicPrompt: BackendAPI.GlycemicEvent?
    /// True for the duration of one in-flight answer, and only that (the hypo-prompt lesson).
    @Published var isAnsweringGlycemicPrompt = false
```

In `refreshAll()`, extend the `async let` group:

```swift
            async let glycemicFetch: Void = refreshGlycemicEvents()
            async let glycemicPromptFetch: Void = refreshGlycemicPrompt()
            _ = await (notesFetch, historyFetch, readingFetch, sensorFetch, prefsFetch, hypoFetch,
                       glycemicFetch, glycemicPromptFetch)
```

Add the same two calls to the periodic/lightweight refresh function (the one documented "Periodic / lightweight refresh without reloading the full notes list"), right after its hypo-event refresh. If it has no hypo refresh, append `await refreshGlycemicEvents(); await refreshGlycemicPrompt()` at the end of its task body.

`GlucoseMonitor/AppState+Glycemic.swift`:

```swift
import Foundation

/// Glycemic events: fetch for the chart, the one-tap prompt, answers, and device-local snooze.
/// Mirrors the hypo-prompt rules - a failed fetch never clears what is shown, and an in-flight answer
/// blocks a second tap until it succeeds or fails.
extension AppState {

    static let glycemicSnoozeKey = "glycemicSnoozedEventIds"
    private static let maxSnoozed = 50

    /// The prompt to show: hidden while a hypo prompt is open (a hypo always wins) or when snoozed here.
    var visibleGlycemicPrompt: BackendAPI.GlycemicEvent? {
        guard openHypoEvent == nil, let prompt = glycemicPrompt, !snoozedGlycemicIds.contains(prompt.id) else { return nil }
        return prompt
    }

    private var snoozedGlycemicIds: [String] {
        UserDefaults.standard.stringArray(forKey: Self.glycemicSnoozeKey) ?? []
    }

    func refreshGlycemicEvents() async {
        let now = Date()
        do {
            glycemicEvents = try await BackendAPI.fetchGlycemicEvents(from: now.addingTimeInterval(-24 * 3600),
                                                                      to: now.addingTimeInterval(3600))
        } catch {
            // Keep the markers already on screen.
        }
    }

    func refreshGlycemicPrompt() async {
        do {
            glycemicPrompt = try await BackendAPI.fetchGlycemicPrompt()
        } catch {
            // A failed poll must never yank the card away mid-answer.
        }
    }

    func answerGlycemicPrompt(category: String, carbs: Double? = nil, insulin: Double? = nil) async -> Bool {
        guard let event = glycemicPrompt else { return true }
        return await answerGlycemicEvent(event, category: category, carbs: carbs, insulin: insulin)
    }

    func answerGlycemicEvent(_ event: BackendAPI.GlycemicEvent, category: String?, carbs: Double? = nil,
                             insulin: Double? = nil) async -> Bool {
        guard !isAnsweringGlycemicPrompt else { return true }
        isAnsweringGlycemicPrompt = true
        defer { isAnsweringGlycemicPrompt = false }
        do {
            _ = try await BackendAPI.confirmGlycemicEvent(id: event.id, category: category, carbs: carbs, insulin: insulin)
            if glycemicPrompt?.id == event.id { glycemicPrompt = nil }
            await refreshGlycemicEvents()
            return true
        } catch {
            errorMessage = "Couldn't save your answer. Try again."
            return false
        }
    }

    func dismissGlycemicPrompt() async -> Bool {
        guard let event = glycemicPrompt else { return true }
        return await dismissGlycemicEvent(event)
    }

    func dismissGlycemicEvent(_ event: BackendAPI.GlycemicEvent) async -> Bool {
        guard !isAnsweringGlycemicPrompt else { return true }
        isAnsweringGlycemicPrompt = true
        defer { isAnsweringGlycemicPrompt = false }
        do {
            _ = try await BackendAPI.dismissGlycemicEvent(id: event.id)
            if glycemicPrompt?.id == event.id { glycemicPrompt = nil }
            await refreshGlycemicEvents()
            return true
        } catch {
            errorMessage = "Couldn't save your answer. Try again."
            return false
        }
    }

    /// "Not sure": hide this event's question on this device only. It stays OPEN server-side and can be
    /// answered later from the event list.
    func snoozeGlycemicPrompt() {
        guard let prompt = glycemicPrompt else { return }
        var ids = snoozedGlycemicIds.filter { $0 != prompt.id }
        ids.append(prompt.id)
        UserDefaults.standard.set(Array(ids.suffix(Self.maxSnoozed)), forKey: Self.glycemicSnoozeKey)
        objectWillChange.send()
    }
}
```

Register it: `python3 /tmp/gm-add-swift.py HypoPromptView.swift AppState+Glycemic.swift A0A`.

- [ ] **Step 4: Run to verify pass**

Run the tests for `GlycemicEventAppStateTests`.
Expected: `** TEST SUCCEEDED **`.

- [ ] **Step 5: Commit**

```bash
git add GlucoseMonitor/AppState.swift GlucoseMonitor/AppState+Glycemic.swift GlucoseMonitorTests/GlycemicEventAppStateTests.swift GlucoseMonitor.xcodeproj/project.pbxproj
git commit -m "feat(glycemic): app state for event markers and the one-tap prompt

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 19: Chart markers (bands, onset rules, pills, connectors, tap)

**Files:**
- Create: `GlucoseMonitor/GlycemicEventMarkers.swift` (suffix `A02`)
- Modify: `GlucoseMonitor/DashboardExtras.swift` (`GlucoseHistoryChart`: two new parameters, marks in `chartCore`, overlay modifier)
- Modify: `GlucoseMonitor/ContentView.swift` (pass events and a selection handler; detail sheet comes in Task 20)
- Create test: `GlucoseMonitorTests/GlycemicEventMarkerLayoutTests.swift` (suffix `A08`)

**Interfaces:**
- Consumes: `BackendAPI.GlycemicEvent` (Task 17), `AppState.glycemicEvents` (Task 18).
- Produces:
  - `enum GlycemicEventMarkerLayout` with:
    - `struct Pill: Equatable { let eventIds: [String]; let label: String; let x: CGFloat; let dashed: Bool }`
    - `static let groupingThreshold: CGFloat = 32`
    - `static func clampedSpan(_:domain:) -> ClosedRange<Date>?`
    - `static func visible(_:in:) -> [BackendAPI.GlycemicEvent]`
    - `static func pills(_ items: [(event: BackendAPI.GlycemicEvent, x: CGFloat)]) -> [Pill]`
  - `enum GlycemicEventStyle` with `static func color(for:) -> Color`.
  - `struct GlycemicEventPillsOverlay: ViewModifier`.
  - `GlucoseHistoryChart` gains `var events: [BackendAPI.GlycemicEvent] = []` and `var onSelectEvent: ((BackendAPI.GlycemicEvent) -> Void)? = nil`.

- [ ] **Step 1: Write the failing test**

```swift
import XCTest
@testable import GlucoseMonitor

/// Pure layout for glycemic event markers - no UI.
final class GlycemicEventMarkerLayoutTests: XCTestCase {

    private let t0 = Date(timeIntervalSince1970: 1_790_000_000)

    private func event(_ id: String, onset: TimeInterval, end: TimeInterval, phase: String = "FINAL",
                       state: String = "OPEN", parent: String? = nil, suppressed: Bool = false,
                       label: String = "Unaccounted carbs") -> BackendAPI.GlycemicEvent {
        BackendAPI.GlycemicEvent(id: id, category: "UNLOGGED_FOOD", detectorCategory: nil, userLabel: nil,
                                 displayLabel: label, direction: "RISE", phase: phase, state: state,
                                 onsetAt: t0.addingTimeInterval(onset), windowStart: t0.addingTimeInterval(onset),
                                 windowEnd: t0.addingTimeInterval(end), resolvesAt: nil, confidence: 0.6,
                                 suppressed: suppressed, linkedEventId: parent, meanResidualMmol: 3,
                                 promptOptions: [])
    }

    func testNearbyPillsMergeIntoACount() {
        let pills = GlycemicEventMarkerLayout.pills([
            (event: event("a", onset: 0, end: 600), x: 100),
            (event: event("b", onset: 60, end: 900), x: 120),
            (event: event("c", onset: 3600, end: 4200), x: 300),
        ])
        XCTAssertEqual(pills.count, 2)
        XCTAssertEqual(pills[0].label, "2 events")
        XCTAssertEqual(pills[0].eventIds, ["a", "b"])
        XCTAssertEqual(pills[1].label, "Unaccounted carbs")
    }

    func testProvisionalPillIsDashed() {
        let pills = GlycemicEventMarkerLayout.pills([(event: event("p", onset: 0, end: 600, phase: "PROVISIONAL",
                                                                  label: "Resistance?"), x: 50)])
        XCTAssertTrue(pills[0].dashed)
        XCTAssertEqual(pills[0].label, "Resistance?")
    }

    func testChildrenAndSuppressedGetNoPill() {
        let pills = GlycemicEventMarkerLayout.pills([
            (event: event("child", onset: 0, end: 600, parent: "p"), x: 50),
            (event: event("quiet", onset: 0, end: 600, suppressed: true), x: 200),
        ])
        XCTAssertTrue(pills.isEmpty)
    }

    func testSpanIsClippedToTheDomain() {
        let domain = t0.addingTimeInterval(0)...t0.addingTimeInterval(3600)
        let straddling = event("s", onset: -1800, end: 7200)   // starts before and ends after the chart
        let span = GlycemicEventMarkerLayout.clampedSpan(straddling, domain: domain)
        XCTAssertEqual(span?.lowerBound, domain.lowerBound)
        XCTAssertEqual(span?.upperBound, domain.upperBound)
    }

    func testEventsOutsideTheDomainOrDismissedAreHidden() {
        let domain = t0...t0.addingTimeInterval(3600)
        let outside = event("o", onset: 7200, end: 9000)
        let dismissed = event("d", onset: 600, end: 1200, state: "DISMISSED")
        let inside = event("i", onset: 600, end: 1200)
        XCTAssertNil(GlycemicEventMarkerLayout.clampedSpan(outside, domain: domain))
        XCTAssertEqual(GlycemicEventMarkerLayout.visible([outside, dismissed, inside], in: domain).map(\.id), ["i"])
    }
}
```

- [ ] **Step 2: Register the test and verify failure**

Run: `python3 /tmp/gm-add-swift.py HypoPromptTests.swift GlycemicEventMarkerLayoutTests.swift A08`, then run the tests for `GlycemicEventMarkerLayoutTests`.
Expected: the build fails.

- [ ] **Step 3: Implement the layer**

`GlucoseMonitor/GlycemicEventMarkers.swift`:

```swift
import SwiftUI
import Charts

/// Colour families for glycemic events: warm for rise explanations, cool for falls, purple for the
/// insulin-resistance family (stress, late absorption, injection site).
enum GlycemicEventStyle {
    static func color(for event: BackendAPI.GlycemicEvent) -> Color {
        switch event.category {
        case "STRESS", "ABSORPTION_DELAY", "LIPODYSTROPHY": return .purple
        default: return event.isRise ? .orange : .teal
        }
    }
}

/// Pure placement logic for event markers (unit tested).
enum GlycemicEventMarkerLayout {

    struct Pill: Equatable {
        let eventIds: [String]
        let label: String
        let x: CGFloat
        let dashed: Bool
    }

    /// Max pixel gap between two pills before they are drawn separately (same rule as carb chips).
    static let groupingThreshold: CGFloat = 32

    /// The part of an event inside the chart's x-domain, or nil when it lies entirely outside.
    static func clampedSpan(_ event: BackendAPI.GlycemicEvent, domain: ClosedRange<Date>) -> ClosedRange<Date>? {
        let end = max(event.windowEnd, event.onsetAt)
        let lower = max(event.onsetAt, domain.lowerBound)
        let upper = min(end, domain.upperBound)
        return lower <= upper ? lower...upper : nil
    }

    /// Events to draw: not dismissed, and at least partly inside the domain.
    static func visible(_ events: [BackendAPI.GlycemicEvent], in domain: ClosedRange<Date>) -> [BackendAPI.GlycemicEvent] {
        events.filter { $0.state != "DISMISSED" && clampedSpan($0, domain: domain) != nil }
    }

    /// One pill per top-level, non-suppressed event at its (clipped) onset x; pills closer than the
    /// threshold merge into a count pill.
    static func pills(_ items: [(event: BackendAPI.GlycemicEvent, x: CGFloat)]) -> [Pill] {
        let candidates = items.filter { !$0.event.isChild && !$0.event.suppressed }.sorted { $0.x < $1.x }
        var groups: [[(event: BackendAPI.GlycemicEvent, x: CGFloat)]] = []
        for item in candidates {
            if let last = groups.last?.last, item.x - last.x <= groupingThreshold {
                groups[groups.count - 1].append(item)
            } else {
                groups.append([item])
            }
        }
        return groups.map { group in
            let x = group.map(\.x).reduce(0, +) / CGFloat(group.count)
            if group.count == 1 {
                return Pill(eventIds: [group[0].event.id], label: group[0].event.displayLabel, x: x,
                            dashed: group[0].event.isProvisional)
            }
            return Pill(eventIds: group.map(\.event.id), label: "\(group.count) events", x: x, dashed: false)
        }
    }
}

/// Label pills, parent-child connectors and the tap target, drawn over the chart's plot area
/// (a row below the carb chips so the two never collide). iOS 17+ like the note overlay.
struct GlycemicEventPillsOverlay: ViewModifier {
    let events: [BackendAPI.GlycemicEvent]
    let domain: ClosedRange<Date>?
    let onSelect: (BackendAPI.GlycemicEvent) -> Void

    /// Vertical offset of the pill row from the plot's top (carb chips sit at 18).
    private static let pillRowY: CGFloat = 40

    func body(content: Content) -> some View {
        if #available(iOS 17.0, *), let domain {
            content.chartOverlay { proxy in
                GeometryReader { geometry in
                    let plot = proxy.plotFrame.map { geometry[$0] } ?? .zero
                    let placed: [(event: BackendAPI.GlycemicEvent, x: CGFloat)] = events.compactMap { e in
                        guard let span = GlycemicEventMarkerLayout.clampedSpan(e, domain: domain),
                              let x = proxy.position(forX: span.lowerBound) else { return nil }
                        return (e, plot.minX + x)
                    }

                    // Tap anywhere on a band to open that event.
                    Rectangle().fill(Color.clear).contentShape(Rectangle())
                        .frame(width: plot.width, height: plot.height)
                        .position(x: plot.midX, y: plot.midY)
                        .onTapGesture { location in
                            guard let date: Date = proxy.value(atX: location.x, as: Date.self) else { return }
                            if let hit = events.first(where: { !$0.isChild && $0.onsetAt <= date && date <= $0.windowEnd }) {
                                onSelect(hit)
                            }
                        }

                    // Dotted connector from each child's onset back to its parent's onset.
                    ForEach(placed.filter { $0.event.isChild }, id: \.event.id) { child in
                        if let parent = placed.first(where: { $0.event.id == child.event.linkedEventId }) {
                            Path { p in
                                p.move(to: CGPoint(x: parent.x, y: plot.minY + Self.pillRowY + 12))
                                p.addLine(to: CGPoint(x: child.x, y: plot.minY + Self.pillRowY + 12))
                            }
                            .stroke(GlycemicEventStyle.color(for: parent.event).opacity(0.7),
                                    style: StrokeStyle(lineWidth: 1, dash: [2, 3]))
                        }
                    }

                    ForEach(GlycemicEventMarkerLayout.pills(placed), id: \.eventIds) { pill in
                        let first = events.first { $0.id == pill.eventIds[0] }
                        Button {
                            if let first { onSelect(first) }
                        } label: {
                            Text(pill.label)
                                .font(.system(size: 11, weight: .semibold))
                                .padding(.horizontal, 7).padding(.vertical, 3)
                                .foregroundStyle(first.map { GlycemicEventStyle.color(for: $0) } ?? .secondary)
                                .background(Capsule().fill(Color(.systemBackground).opacity(0.9)))
                                .overlay(Capsule().strokeBorder(
                                    first.map { GlycemicEventStyle.color(for: $0) } ?? .secondary,
                                    style: StrokeStyle(lineWidth: 1, dash: pill.dashed ? [3, 2] : [])))
                        }
                        .buttonStyle(.plain)
                        .position(x: pill.x, y: plot.minY + Self.pillRowY)
                        .accessibilityLabel(Text(pill.label))
                    }
                }
            }
        } else {
            content
        }
    }
}
```

Register it: `python3 /tmp/gm-add-swift.py HypoPromptView.swift GlycemicEventMarkers.swift A02`.

In `DashboardExtras.swift`, in `GlucoseHistoryChart`:
- add these properties after `currentGlucose`:

```swift
    /// Detected glycemic events to mark on the chart (bands + onset rules + pills).
    var events: [BackendAPI.GlycemicEvent] = []
    /// Called when the user taps an event's band or pill.
    var onSelectEvent: ((BackendAPI.GlycemicEvent) -> Void)? = nil

    private var eventsOnChart: [BackendAPI.GlycemicEvent] {
        guard let domain = xDomain else { return [] }
        return GlycemicEventMarkerLayout.visible(events, in: domain)
    }
```

- in `chartCore`, directly after the `ForEach(notesOnChart) { … RuleMark … }` block, add:

```swift
            // Glycemic events: a band over the unexplained move and a rule at its onset (dashed while provisional).
            ForEach(eventsOnChart) { e in
                if let domain = xDomain, let span = GlycemicEventMarkerLayout.clampedSpan(e, domain: domain) {
                    RectangleMark(xStart: .value("eventStart", span.lowerBound), xEnd: .value("eventEnd", span.upperBound))
                        .foregroundStyle(GlycemicEventStyle.color(for: e).opacity(e.suppressed ? 0.05 : 0.14))
                    if !e.isChild {
                        RuleMark(x: .value("eventOnset", span.lowerBound))
                            .foregroundStyle(GlycemicEventStyle.color(for: e).opacity(e.suppressed ? 0.3 : 0.9))
                            .lineStyle(StrokeStyle(lineWidth: 1.5, dash: e.isProvisional ? [4, 3] : []))
                    }
                }
            }
```

- after `.modifier(ChartNoteMarkersOverlay(notes: notesOnChart))`, add:

```swift
                    .modifier(GlycemicEventPillsOverlay(events: eventsOnChart, domain: xDomain,
                                                        onSelect: { onSelectEvent?($0) }))
```

In `ContentView.swift`, add `@State private var selectedGlycemicEvent: BackendAPI.GlycemicEvent?` next to the other `@State` sheet flags, and pass `events: appState.glycemicEvents, onSelectEvent: { selectedGlycemicEvent = $0 }` to `GlucoseHistoryChart(...)` in `extendedChartSection`. The sheet that uses `selectedGlycemicEvent` is added in Task 20.

- [ ] **Step 4: Run the tests and build**

Run the tests for `GlycemicEventMarkerLayoutTests`. Expected: `** TEST SUCCEEDED **`. Then run the build command. Expected: `exit=0`, count 1.

- [ ] **Step 5: Commit**

```bash
git add GlucoseMonitor/GlycemicEventMarkers.swift GlucoseMonitor/DashboardExtras.swift GlucoseMonitor/ContentView.swift GlucoseMonitorTests/GlycemicEventMarkerLayoutTests.swift GlucoseMonitor.xcodeproj/project.pbxproj
git commit -m "feat(glycemic): mark detected events on the forecast chart

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 20: Prompt card and event detail sheet

**Files:**
- Create: `GlucoseMonitor/GlycemicEventPromptCard.swift` (suffix `A03`), `GlucoseMonitor/GlycemicEventDetailSheet.swift` (suffix `A04`)
- Modify: `GlucoseMonitor/ContentView.swift` (card under the chart; detail sheet)
- Create test: `GlucoseMonitorTests/GlycemicEventTextTests.swift` (suffix `A09`)

**Interfaces:**
- Consumes: Task 17 types, Task 18 `AppState` methods, `GlucoseUnit.fromMmol(_:displayUnit:)`, `appState.preferredGlucoseUnit`.
- Produces:
  - `enum GlycemicEventCopy` with `static func question(for:) -> String` and `static func summary(for:displayUnit:) -> String`.
  - `enum GlycemicAmount` with `static func parse(_:) -> Double?`, `static let carbPresets: [Double] = [10, 20, 30]` and `static let insulinPresets: [Double] = [1, 2, 4]`.
  - `struct GlycemicAnswerButtons: View`, `struct GlycemicEventPromptCard: View` and `struct GlycemicEventDetailSheet: View`.

- [ ] **Step 1: Write the failing test**

```swift
import XCTest
@testable import GlucoseMonitor

final class GlycemicEventTextTests: XCTestCase {

    private func event(direction: String, phase: String, mean: Double, minutes: Double) -> BackendAPI.GlycemicEvent {
        let onset = Date(timeIntervalSince1970: 1_790_000_000)
        return BackendAPI.GlycemicEvent(id: "e", category: "STRESS", detectorCategory: nil, userLabel: nil,
                                        displayLabel: "Resistance?", direction: direction, phase: phase, state: "OPEN",
                                        onsetAt: onset, windowStart: onset, windowEnd: onset.addingTimeInterval(minutes * 60),
                                        resolvesAt: nil, confidence: 0.5, suppressed: false, linkedEventId: nil,
                                        meanResidualMmol: mean, promptOptions: [])
    }

    func testQuestions() {
        XCTAssertEqual(GlycemicEventCopy.question(for: event(direction: "RISE", phase: "PROVISIONAL", mean: 3, minutes: 60)),
                       "Your glucose held up although insulin was active. What's going on?")
        XCTAssertEqual(GlycemicEventCopy.question(for: event(direction: "FALL", phase: "PROVISIONAL", mean: -3, minutes: 60)),
                       "Your glucose dropped more than expected. What happened?")
        XCTAssertEqual(GlycemicEventCopy.question(for: event(direction: "RISE", phase: "FINAL", mean: 3, minutes: 60)),
                       "Does this look right?")
    }

    func testSummaryUsesTheDisplayUnit() {
        let e = event(direction: "RISE", phase: "FINAL", mean: 3.0, minutes: 70)
        XCTAssertEqual(GlycemicEventCopy.summary(for: e, displayUnit: "mmol/L"),
                       "3.0 mmol/L above the forecast for 70 min")
        XCTAssertEqual(GlycemicEventCopy.summary(for: e, displayUnit: "mg/dL"),
                       "54 mg/dL above the forecast for 70 min")
    }

    func testAmountParsingAcceptsCommaDecimals() {
        XCTAssertEqual(GlycemicAmount.parse("2,5"), 2.5)
        XCTAssertEqual(GlycemicAmount.parse(" 20 "), 20)
        XCTAssertNil(GlycemicAmount.parse(""))
        XCTAssertNil(GlycemicAmount.parse("-3"))
        XCTAssertNil(GlycemicAmount.parse("abc"))
    }
}
```

- [ ] **Step 2: Register the test and verify failure**

Run: `python3 /tmp/gm-add-swift.py HypoPromptTests.swift GlycemicEventTextTests.swift A09`, then run the tests for `GlycemicEventTextTests`.
Expected: the build fails.

- [ ] **Step 3: Implement the card**

`GlucoseMonitor/GlycemicEventPromptCard.swift`:

```swift
import SwiftUI

/// Wording for the prompt and detail views. Never dosing advice.
enum GlycemicEventCopy {
    static func question(for event: BackendAPI.GlycemicEvent) -> String {
        if event.isProvisional {
            return event.isRise
                ? "Your glucose held up although insulin was active. What's going on?"
                : "Your glucose dropped more than expected. What happened?"
        }
        return "Does this look right?"
    }

    /// e.g. "3.0 mmol/L above the forecast for 70 min".
    static func summary(for event: BackendAPI.GlycemicEvent, displayUnit: String) -> String {
        let magnitude = GlucoseUnit.fromMmol(abs(event.meanResidualMmol), displayUnit: displayUnit)
        let value = displayUnit == "mg/dL" ? String(format: "%.0f", magnitude) : String(format: "%.1f", magnitude)
        let minutes = Int((event.windowEnd.timeIntervalSince(event.onsetAt) / 60).rounded())
        return "\(value) \(displayUnit) \(event.isRise ? "above" : "below") the forecast for \(minutes) min"
    }
}

enum GlycemicAmount {
    static let carbPresets: [Double] = [10, 20, 30]
    static let insulinPresets: [Double] = [1, 2, 4]

    /// A positive amount, accepting a comma decimal separator; nil for empty or invalid input.
    static func parse(_ text: String) -> Double? {
        let cleaned = text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        guard let v = Double(cleaned), v > 0 else { return nil }
        return v
    }
}

/// The one-tap answer row, shared by the prompt card and the detail sheet. Food and insulin answers
/// expand an optional amount (presets or typed); every other answer confirms immediately.
struct GlycemicAnswerButtons: View {
    let options: [String]
    let isBusy: Bool
    let onAnswer: (_ category: String, _ carbs: Double?, _ insulin: Double?) -> Void

    @State private var amountFor: String?
    @State private var amountText = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            FlowRow(spacing: 6) {
                ForEach(options, id: \.self) { option in
                    Button(GlycemicCategory.buttonTitle(option)) {
                        if GlycemicCategory.takesCarbs(option) || GlycemicCategory.takesInsulin(option) {
                            amountFor = option
                        } else {
                            onAnswer(option, nil, nil)
                        }
                    }
                    .buttonStyle(.bordered)
                }
            }
            if let category = amountFor {
                let isCarbs = GlycemicCategory.takesCarbs(category)
                HStack(spacing: 6) {
                    ForEach(isCarbs ? GlycemicAmount.carbPresets : GlycemicAmount.insulinPresets, id: \.self) { preset in
                        Button(isCarbs ? "\(Int(preset)) g" : "\(Int(preset)) u") {
                            submit(category, amount: preset)
                        }
                        .buttonStyle(.bordered)
                    }
                    TextField(isCarbs ? "g" : "u", text: $amountText)
                        .keyboardType(.decimalPad)
                        .textFieldStyle(.roundedBorder)
                        .frame(width: 64)
                    Button("Save") { submit(category, amount: GlycemicAmount.parse(amountText)) }
                        .disabled(GlycemicAmount.parse(amountText) == nil)
                    Button("Skip amount") { submit(category, amount: nil) }
                        .font(.footnote)
                }
            }
        }
        .disabled(isBusy)
    }

    private func submit(_ category: String, amount: Double?) {
        let carbs = GlycemicCategory.takesCarbs(category) ? amount : nil
        let insulin = GlycemicCategory.takesInsulin(category) ? amount : nil
        amountFor = nil
        amountText = ""
        onAnswer(category, carbs, insulin)
    }
}

/// Minimal wrapping row for the answer buttons.
struct FlowRow: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > width { x = 0; y += rowHeight + spacing; rowHeight = 0 }
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
        return CGSize(width: width == .infinity ? x : width, height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowHeight: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > bounds.minX && x + size.width > bounds.maxX { x = bounds.minX; y += rowHeight + spacing; rowHeight = 0 }
            s.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}

/// Inline card under the chart asking about the server's chosen event. Not a modal: only a hypo
/// justifies interrupting the user.
struct GlycemicEventPromptCard: View {
    let event: BackendAPI.GlycemicEvent
    let displayUnit: String
    let isBusy: Bool
    let onAnswer: (_ category: String, _ carbs: Double?, _ insulin: Double?) -> Void
    let onNotSure: () -> Void
    let onNothing: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Label(event.displayLabel, systemImage: event.isRise ? "arrow.up.right" : "arrow.down.right")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(GlycemicEventStyle.color(for: event))
                Spacer()
                Text(event.onsetAt, style: .time)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            Text(GlycemicEventCopy.question(for: event))
                .font(.footnote)
                .foregroundColor(.secondary)
            GlycemicAnswerButtons(options: event.promptOptions, isBusy: isBusy, onAnswer: onAnswer)
            HStack {
                Button("Not sure", action: onNotSure)
                Button("Nothing", action: onNothing)
            }
            .font(.footnote)
            .foregroundColor(.secondary)
            .disabled(isBusy)
        }
        .padding()
        .background(Color(.systemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(GlycemicEventStyle.color(for: event).opacity(0.4)))
    }
}
```

`GlucoseMonitor/GlycemicEventDetailSheet.swift`:

```swift
import SwiftUI

/// Detail for one event: label, plain-language summary, timeline, and the same answers while it's open.
struct GlycemicEventDetailSheet: View {
    let event: BackendAPI.GlycemicEvent
    @EnvironmentObject private var appState: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var history: [BackendAPI.GlycemicEventHistoryEntry] = []

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Label(event.displayLabel, systemImage: event.isRise ? "arrow.up.right" : "arrow.down.right")
                        .foregroundStyle(GlycemicEventStyle.color(for: event))
                    Text(GlycemicEventCopy.summary(for: event, displayUnit: appState.preferredGlucoseUnit))
                    Text("\(event.onsetAt.formatted(date: .abbreviated, time: .shortened)) – \(event.windowEnd.formatted(date: .omitted, time: .shortened))")
                        .foregroundColor(.secondary)
                }
                if event.state == "OPEN" && !event.isChild {
                    Section(GlycemicEventCopy.question(for: event)) {
                        GlycemicAnswerButtons(options: event.promptOptions, isBusy: appState.isAnsweringGlycemicPrompt) { category, carbs, insulin in
                            Task {
                                if await appState.answerGlycemicEvent(event, category: category, carbs: carbs, insulin: insulin) {
                                    dismiss()
                                }
                            }
                        }
                        Button("Nothing", role: .destructive) {
                            Task { if await appState.dismissGlycemicEvent(event) { dismiss() } }
                        }
                    }
                }
                if !history.isEmpty {
                    Section("Timeline") {
                        ForEach(Array(history.enumerated()), id: \.offset) { _, h in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(h.kind.capitalized + (h.categoryAfter.map { " · " + GlycemicCategory.buttonTitle($0) } ?? ""))
                                Text("\(h.at.formatted(date: .abbreviated, time: .shortened)) · \(h.actor == "USER" ? "You" : "Detected")")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Detected event")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .task { history = (try? await BackendAPI.fetchGlycemicEventHistory(id: event.id)) ?? [] }
        }
    }
}
```

Register both files:
`python3 /tmp/gm-add-swift.py HypoPromptView.swift GlycemicEventPromptCard.swift A03`
`python3 /tmp/gm-add-swift.py HypoPromptView.swift GlycemicEventDetailSheet.swift A04`

In `ContentView.swift`:
- directly after `extendedChartSection` in the dashboard `VStack`, add:

```swift
                            if let prompt = appState.visibleGlycemicPrompt {
                                GlycemicEventPromptCard(
                                    event: prompt,
                                    displayUnit: appState.preferredGlucoseUnit,
                                    isBusy: appState.isAnsweringGlycemicPrompt,
                                    onAnswer: { category, carbs, insulin in
                                        Task { _ = await appState.answerGlycemicPrompt(category: category, carbs: carbs, insulin: insulin) }
                                    },
                                    onNotSure: { appState.snoozeGlycemicPrompt() },
                                    onNothing: { Task { _ = await appState.dismissGlycemicPrompt() } })
                            }
```

- next to the existing `.sheet(item: $appState.openHypoEvent)`, add:

```swift
            .sheet(item: $selectedGlycemicEvent) { event in
                GlycemicEventDetailSheet(event: event)
                    .environmentObject(appState)
            }
```

- [ ] **Step 4: Run the tests and build**

Run the tests for `GlycemicEventTextTests`. Expected: `** TEST SUCCEEDED **`. Then run the build command. Expected: `exit=0`, count 1.

- [ ] **Step 5: Commit**

```bash
git add GlucoseMonitor/GlycemicEventPromptCard.swift GlucoseMonitor/GlycemicEventDetailSheet.swift GlucoseMonitor/ContentView.swift GlucoseMonitorTests/GlycemicEventTextTests.swift GlucoseMonitor.xcodeproj/project.pbxproj
git commit -m "feat(glycemic): one-tap prompt card and event detail sheet

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 21: History view with insights, and final verification

**Files:**
- Create: `GlucoseMonitor/GlycemicEventsView.swift` (suffix `A05`)
- Modify: `GlucoseMonitor/ContentView.swift` ("Detected events" row in `quickActions`; sheet)
- Modify test: `GlucoseMonitorTests/GlycemicEventTextTests.swift` (insights text and grouping)

**Interfaces:**
- Consumes: `BackendAPI.fetchGlycemicInsights`, `fetchGlycemicEvents`, `GlycemicEventDetailSheet`.
- Produces: `enum GlycemicInsightsText` with `static func peakHour(_ histogram: [Int]) -> Int?`, `static func noEffectLine(_ count: Int) -> String?` and `static func byDay(_ events: [BackendAPI.GlycemicEvent], calendar: Calendar) -> [(day: Date, events: [BackendAPI.GlycemicEvent])]`; `struct GlycemicEventsView: View`.

- [ ] **Step 1: Add the failing tests to `GlycemicEventTextTests`**

```swift
    func testPeakHour() {
        var h = Array(repeating: 0, count: 24)
        XCTAssertNil(GlycemicInsightsText.peakHour(h))
        h[10] = 3; h[16] = 1
        XCTAssertEqual(GlycemicInsightsText.peakHour(h), 10)
    }

    func testNoEffectLineOnlyWhenThereAreCases() {
        XCTAssertNil(GlycemicInsightsText.noEffectLine(0))
        XCTAssertEqual(GlycemicInsightsText.noEffectLine(4),
                       "4 insulin doses in the last 14 days showed no measurable effect. Consider checking your injection sites.")
    }

    func testByDayGroupsNewestFirstAndSkipsChildren() {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        let day1 = Date(timeIntervalSince1970: 1_790_000_000)          // some day, 10:13 UTC
        let day2 = day1.addingTimeInterval(86_400)
        func ev(_ id: String, _ at: Date, parent: String? = nil) -> BackendAPI.GlycemicEvent {
            BackendAPI.GlycemicEvent(id: id, category: "STRESS", detectorCategory: nil, userLabel: nil,
                                     displayLabel: "x", direction: "RISE", phase: "FINAL", state: "OPEN",
                                     onsetAt: at, windowStart: at, windowEnd: at.addingTimeInterval(600),
                                     resolvesAt: nil, confidence: 0.6, suppressed: false, linkedEventId: parent,
                                     meanResidualMmol: 2, promptOptions: [])
        }
        let groups = GlycemicInsightsText.byDay([ev("a", day1), ev("b", day2), ev("c", day2, parent: "b")], calendar: cal)
        XCTAssertEqual(groups.map { $0.events.map(\.id) }, [["b"], ["a"]])
    }
```

- [ ] **Step 2: Run to verify failure**

Run the tests for `GlycemicEventTextTests`.
Expected: the build fails (`GlycemicInsightsText` doesn't exist).

- [ ] **Step 3: Implement**

`GlucoseMonitor/GlycemicEventsView.swift`:

```swift
import SwiftUI

/// Pure helpers for the history view (unit tested).
enum GlycemicInsightsText {
    static func peakHour(_ histogram: [Int]) -> Int? {
        guard let max = histogram.max(), max > 0 else { return nil }
        return histogram.firstIndex(of: max)
    }

    static func noEffectLine(_ count: Int) -> String? {
        guard count > 0 else { return nil }
        return "\(count) insulin doses in the last 14 days showed no measurable effect. Consider checking your injection sites."
    }

    /// Top-level events grouped by calendar day, newest day first, newest event first within a day.
    static func byDay(_ events: [BackendAPI.GlycemicEvent], calendar: Calendar) -> [(day: Date, events: [BackendAPI.GlycemicEvent])] {
        let grouped = Dictionary(grouping: events.filter { !$0.isChild }) { calendar.startOfDay(for: $0.onsetAt) }
        return grouped.keys.sorted(by: >).map { day in
            (day, grouped[day]!.sorted { $0.onsetAt > $1.onsetAt })
        }
    }
}

/// 30-day history of detected events with a short insights header. Rows open the detail sheet, so old
/// events can still be answered or relabelled.
struct GlycemicEventsView: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var insights: BackendAPI.GlycemicInsights?
    @State private var events: [BackendAPI.GlycemicEvent] = []
    @State private var filter: String?
    @State private var selected: BackendAPI.GlycemicEvent?
    @State private var loadFailed = false

    private var filtered: [BackendAPI.GlycemicEvent] {
        events.filter { $0.state != "DISMISSED" && (filter == nil || $0.category == filter) }
    }

    var body: some View {
        List {
            if let insights {
                Section("Last 30 days") {
                    ForEach(insights.counts.sorted { $0.value > $1.value }, id: \.key) { category, count in
                        HStack {
                            Text(GlycemicCategory.buttonTitle(category))
                            Spacer()
                            if let hour = GlycemicInsightsText.peakHour(insights.hourHistogram[category] ?? []) {
                                Text(String(format: "mostly around %02d:00", hour))
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                            Text("\(count)").monospacedDigit()
                        }
                    }
                    if let line = GlycemicInsightsText.noEffectLine(insights.noEffectCount14d) {
                        Text(line).font(.footnote).foregroundColor(.purple)
                    }
                }
            }
            if loadFailed {
                Text("Couldn't load detected events. Pull to refresh.").foregroundColor(.secondary)
            }
            ForEach(GlycemicInsightsText.byDay(filtered, calendar: .current), id: \.day) { group in
                Section(group.day.formatted(date: .complete, time: .omitted)) {
                    ForEach(group.events) { e in
                        Button { selected = e } label: {
                            HStack {
                                Circle().fill(GlycemicEventStyle.color(for: e)).frame(width: 8, height: 8)
                                Text(e.displayLabel)
                                Spacer()
                                if e.state == "OPEN" { Text("Unanswered").font(.caption).foregroundColor(.orange) }
                                Text(e.onsetAt, style: .time).font(.caption).foregroundColor(.secondary)
                            }
                        }
                        .foregroundColor(.primary)
                    }
                }
            }
        }
        .navigationTitle("Detected events")
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Menu {
                    Button("All") { filter = nil }
                    ForEach(Array(Set(events.map(\.category))).sorted(), id: \.self) { c in
                        Button(GlycemicCategory.buttonTitle(c)) { filter = c }
                    }
                } label: { Label("Filter", systemImage: "line.3.horizontal.decrease.circle") }
            }
            ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
        }
        .refreshable { await load() }
        .task { await load() }
        .sheet(item: $selected, onDismiss: { Task { await load() } }) { e in
            GlycemicEventDetailSheet(event: e).environmentObject(appState)
        }
    }

    private func load() async {
        let now = Date()
        do {
            async let i = BackendAPI.fetchGlycemicInsights(days: 30)
            async let e = BackendAPI.fetchGlycemicEvents(from: now.addingTimeInterval(-30 * 86_400), to: now)
            (insights, events) = try await (i, e)
            loadFailed = false
        } catch {
            loadFailed = true
        }
    }
}
```

Register it: `python3 /tmp/gm-add-swift.py HypoPromptView.swift GlycemicEventsView.swift A05`.

In `ContentView.swift`:
- add `@State private var showGlycemicEvents = false`
- in `quickActions`, after the "Log activity" row and its `Divider()`, add:

```swift
                Button { showGlycemicEvents = true } label: {
                    quickActionRow(title: "Detected events", systemImage: "waveform.badge.magnifyingglass")
                }
                Divider()
```

- add the sheet next to the others:

```swift
            .sheet(isPresented: $showGlycemicEvents) {
                NavigationStack { GlycemicEventsView() }
                    .environmentObject(appState)
            }
```

- [ ] **Step 4: Run all glycemic iOS tests, the full iOS suite, and the build**

Run the test command with `-only-testing:GlucoseMonitorTests/GlycemicEventTextTests -only-testing:GlucoseMonitorTests/GlycemicEventDecodingTests -only-testing:GlucoseMonitorTests/GlycemicEventAppStateTests -only-testing:GlucoseMonitorTests/GlycemicEventMarkerLayoutTests`.
Expected: `** TEST SUCCEEDED **`.

Run the full suite (the test command without `-only-testing`).
Expected: the only failures are the pre-existing ones listed in Global Constraints.

Run the build command. Expected: `exit=0`, count 1.

- [ ] **Step 5: See it running**

Start the backend locally with the feature on, sign in on the simulator, and insert one OPEN `UNLOGGED_FOOD` event and one PROVISIONAL `STRESS` event for that user. The event date is today at 30 and 90 minutes before now (user wall time); use the pg14 `psql` from `dev-db-is-native-pg14`. Then launch the app and take simulator screenshots of:
- the dashboard chart with both markers
- the prompt card
- the detail sheet
- the history view

Remove the inserted rows afterwards.

- [ ] **Step 6: Commit**

```bash
git add GlucoseMonitor/GlycemicEventsView.swift GlucoseMonitor/ContentView.swift GlucoseMonitorTests/GlycemicEventTextTests.swift GlucoseMonitor.xcodeproj/project.pbxproj
git commit -m "feat(glycemic): detected-events history with insights

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
