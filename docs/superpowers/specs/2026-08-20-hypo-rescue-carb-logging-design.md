# Hypo rescue-carb logging — design

- Date: 2026-08-20
- Scope: `glucose-monitor-be` (backend) + `glucose-monitor-iphone` (iOS)
- Status: approved, ready for implementation planning

## Problem

There is no way to log a fast-acting rescue carb (glucose gel, dextrose tablets, juice) taken to treat a hypo. Two consequences:

1. **The model sees an unexplained rise.** A hypo recovery looks to `UnloggedEventDetectionService` like unlogged food, so every treated hypo generates a false flag.
2. **The user has to notice the hypo themselves.** Nothing prompts them, even though `GlucoseAlertEvaluator` already computes a 3.9 mmol/L hypo threshold on a 5-minute cadence.

Investigating (2) surfaced a third, pre-existing problem: the observer that would raise that alert reads glucose from a source CGM sync never writes, so it is dormant in production. See §4 — fixing it is a prerequisite for this feature and is folded into this spec.

Logging a rescue as an ordinary note is worse than not logging it: the default 45-minute half-life and 240-minute duration would leave phantom carbs on board for hours, when a glucose gel is clinically done in 20–30 minutes.

## Goals

- A user whose glucose drops below 3.9 mmol/L is prompted, and can log a rescue carb in one tap.
- Rescue carbs are modelled with a genuinely fast absorption profile.
- Rescue carbs feed physiology (COB, Hovorka, twin replay, unlogged-event matching) but cannot corrupt the parameter-titration loops.
- The alert observer evaluates real CGM data rather than a source that is never populated.

## Non-goals

- **APNs push delivery.** `GlucoseAlertService.deliverAlert` is still a stub. The prompt therefore appears in the foreground or on next app open, not while the app is closed. Accepted as a known limitation; see *Known limitations*.
- Watch support.
- Any change to insulin dosing. A rescue carb never produces a bolus recommendation.

## Decisions

| Question | Decision |
|---|---|
| Scope | Backend + iOS |
| Representation | New note type with a dedicated fast absorption profile |
| Who decides a hypo is happening | Backend, persisted as a flag; the client renders it |
| Confirm UX | Quick-pick row (10 / 15 / 20 g) plus custom entry; no new settings |
| Learning loops | Excluded from titration, kept in physiology |
| Dormant detector | Repoint `GlucoseAnomalyDetector` at `cgm_readings` as part of this work |

### Why backend-owned detection

A client-side threshold would be a second definition of "is this a hypo", alongside the existing 3.9 constant in `GlucoseAlertEvaluator`. This codebase has already paid for duplicated computation once — see `2026-08-08-dual-computation-findings.md`, where the dashboard and the Experiments tab disagreed on COB/IOB. Detection stays in one place, on the cadence that already exists.

Latency cost is acceptable: the observer runs every 5 minutes, which is the CGM delivery cadence, so a backend-detected prompt is at most one reading behind a client-side one.

## Architecture

### 1 · Note type

`Note.TYPE_HYPO_TREATMENT = "hypo_treatment"` joins `normal`, `long_acting`, `activity`.

No migration needed for `notes`: `type` is already `VARCHAR(20)` and the value is 14 characters. Add `Note.isHypoTreatment()` alongside the existing `isLongActing()` / `isActivity()` predicates.

### 2 · `hypo_events` table — `V11__hypo_events.sql`

A brand-new table, so a new migration file is justified under the project's schema rules (existing tables are edited in place, never `ALTER`ed in a new file).

```sql
CREATE TABLE IF NOT EXISTS hypo_events (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               UUID        NOT NULL,
    trigger_glucose_mmol  DOUBLE PRECISION NOT NULL,
    state                 VARCHAR(12) NOT NULL DEFAULT 'OPEN',
    note_id               UUID,
    detected_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at           TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_hypo_events_user  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_hypo_events_note  FOREIGN KEY (note_id) REFERENCES notes(id) ON DELETE SET NULL,
    CONSTRAINT chk_hypo_event_state CHECK (state IN ('OPEN','CONFIRMED','DISMISSED','EXPIRED'))
);
CREATE INDEX IF NOT EXISTS idx_hypo_events_user_state ON hypo_events(user_id, state);
```

`note_id` uses `ON DELETE SET NULL` rather than `CASCADE`: deleting the rescue note should not erase the record that a hypo occurred and was prompted.

The table deliberately mirrors `unlogged_event_flags` so the detect → prompt → confirm/dismiss lifecycle reads identically in both places.

### 3 · Rescue absorption profile

A single constant holder, `RescueCarbProfile`, consumed by **both** `CarbsOnBoardService` and the Hovorka warm-up path.

| Constant | Value | Rationale |
|---|---|---|
| `HALF_LIFE_MIN` | 15 | Glucose gel/tablets are substantially absorbed within 15 min |
| `MAX_DURATION_MIN` | 45 | Clinically complete; vs. the 240 min default |
| `GI` | 100 | Pure glucose reference |
| `tMaxG` | `HALF_LIFE_MIN / 1.68` ≈ 8.9 min | Reuses the existing `HovorkaParameterService.HALF_LIFE_TO_TMAX_G` |

Rescue notes bypass `user_settings.carb_half_life` and `max_cob_duration` entirely — a rescue does not absorb at the user's mixed-meal rate.

**One definition, two consumers.** The same curve has twice been hand-copied into a second code path in this repo and drifted (see the warm-up replay divergence fixed in `f4fc956`). `RescueCarbProfile` exists so that cannot happen again, and its tests assert both consumers agree.

### 4 · Prerequisite — repoint the detector at `cgm_readings`

`GlucoseAnomalyDetector.evaluateUser` currently sources glucose from **notes**:

```java
// Notes are the unified source of truth until a dedicated CGM table is added.
List<Note> recentReadings = noteRepository.findByUserIdAndTimestampBetween(userId, rocStart, now)
        .stream().filter(n -> n.getGlucoseLevel() != null && n.getGlucoseLevel() > 0)
```

That comment is stale. `cgm_readings` was added (V1) and populated every 5 minutes by both sync schedulers, but the detector was never migrated. `notes.glucose_value` is written **only** from client-supplied note payloads (`NoteMapper`, `NotesService`); neither CGM sync path writes it, and the `observer` package contains no reference to `CgmReading`.

Consequence: the detector needs two glucose-bearing *notes* within a 20-minute window to evaluate anything, so all four existing alert types (`PREDICTED_HYPO`, `RAPID_DROP`, `UNLOGGED_MEAL`, `PREDICTED_HYPER`) are effectively dormant on real CGM data.

This is a prerequisite, not adjacent cleanup: a hypo prompt built on the current detector would never fire.

**Change:** swap `NoteRepository` for `CgmReadingRepository` as the glucose source in `evaluateUser`, reading the ROC window from `cgm_readings` via the existing `findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc`. `NoteRepository` is retained for the `minutesSinceLastMeal` lookup, which is legitimately note-based.

`computeRoc` currently takes `List<Note>`. It is retargeted to a minimal `GlucosePoint(long epochMs, double mmol)` record so the OLS maths is independent of the storage entity.

**mg/dL conversion.** `cgm_readings.sgv` is mg/dL. `MGDL_PER_MMOL = 18.0182` is currently declared privately in three separate classes (`ReplayMetrics`, `UnloggedEventDetectionService`, `DigitalTwinCalibrationService`). Rather than adding a fourth copy, promote it to one shared constant and repoint the three existing declarations at it. Three one-line changes; removes duplication instead of extending it.

**Test position.** `GlucoseAnomalyDetector` and `computeRoc` have **no existing tests** — the class is entirely uncovered, which is part of why the dormancy went unnoticed. This task therefore adds the first tests for it rather than updating existing fixtures.

### 5 · Hypo event lifecycle

With the detector reading real CGM, add to the same 5-minute pass:

- Latest reading **< `HYPO_THRESHOLD` (3.9 mmol/L)** → open a `hypo_events` row, unless one is already `OPEN` for that user, and unless suppressed (below).
- Latest reading **≥ `HYPO_RECOVERY_THRESHOLD` (4.5 mmol/L)** → any still-`OPEN` event transitions to `EXPIRED`.

The hysteresis gap (3.9 open, 4.5 close) prevents a reading hovering at the threshold from flapping the prompt on and off.

**Re-prompt suppression.** A `CONFIRMED` or `DISMISSED` event suppresses new events for that user for **15 minutes**. Without this, resolving an event leaves no `OPEN` row, so the very next 5-minute pass would re-open while the user is still low — a prompt loop every 5 minutes.

Fifteen minutes is chosen to match the clinical "rule of 15": treat with 15 g, recheck after 15 minutes, re-treat if still low. So after the suppression window, a user still below 3.9 **is** re-prompted, which is the correct clinical behaviour rather than a nag.

**Threshold sourcing:** `HYPO_THRESHOLD = 3.9` is currently private to `GlucoseAlertEvaluator`. It is *promoted* to a shared constant and referenced by both the evaluator and the new detector path. It must not be copied — that is the dual-computation failure mode this design exists to avoid.

### 6 · API

Mirrors `UnloggedEventController`, including the feature-flag gate.

| Method | Path | Behaviour |
|---|---|---|
| `GET` | `/api/hypo-events?state=OPEN` | List the authenticated user's events, optionally filtered by state |
| `POST` | `/api/hypo-events/{id}/confirm` | Body `{ "grams": 15.0 }`. Creates a `hypo_treatment` note at *now*, links `note_id`, sets state `CONFIRMED` |
| `POST` | `/api/hypo-events/{id}/dismiss` | Sets state `DISMISSED` |

**Confirm is idempotent.** If the event is already `CONFIRMED`, return the existing linked note rather than creating a second one. Double-logging rescue carbs during a hypo is a patient-safety issue, not merely a data defect: the phantom carbs would suppress a subsequent genuine hypo prompt and inflate predicted glucose while the user is still low.

Validation: `grams` must be present, finite, and within `(0, 100]`. Out-of-range input is a 400, not a clamp.

Gated by a new feature flag `app.features.hypo-rescue-logging-enabled` — Java default `false`, YAML default `true`, following the established convention for `digital-twin-enabled` and `unlogged-event-detection-enabled`.

### 7 · Learning-loop isolation

| Loop | Treatment | Change required |
|---|---|---|
| Verification titration | Excluded | `preCheckEligibility` returns `"hypo_treatment"` |
| Observational ISF | Excluded | Rescue notes filtered from the nearby-carb sum and the expected-rise subtraction |
| COB / Hovorka path | **Included** | Uses `RescueCarbProfile` |
| Digital-twin replay | **Included** | Real input; no change needed |
| Unlogged-event detector | **Included** | Explains its own recovery; no change needed |

Two notes on the exclusions:

**Verification is already safe, but incidentally.** `preCheckEligibility` returns `"no_insulin"` for any note without insulin, so a rescue would be `SKIPPED` today. The explicit `"hypo_treatment"` reason makes the intent legible and survives any future relaxation of the insulin check. It also gives a truthful skip reason in the audit trail.

**Observational ISF is genuinely exposed.** `IsfMealWindowProfileService` sums carbs within ±2 h of a bolus and compares against `CARB_THRESHOLD_GRAMS = 10.0`. A 15 g rescue crosses that threshold, which both reclassifies a correction bolus from `WEIGHT_CORRECTION` (1.0) to `WEIGHT_MEAL` (0.4) *and* subtracts a phantom meal rise from the ISF estimate. This is a real corruption path and the exclusion is required, not defensive.

### 8 · iOS

`HypoPromptView`, presented from `AppState` when a `GET /api/hypo-events?state=OPEN` poll returns an event on the existing dashboard-refresh cycle.

- Quick-pick row: **10 / 15 / 20 g**, plus a custom-entry path.
- Trigger glucose rendered via the existing `GlucoseUnit.fromMmol(_:displayUnit:)`. The backend stays mmol/L end to end; mg/dL is display-only and reuses the converter that already exists — no second conversion constant.
- Dismiss is available but does not permanently silence: a *new* hypo event after recovery opens a fresh prompt.
- Confirm posts to the API, then inserts the note optimistically into local state.

## Data flow

```
CGM sync writes cgm_readings (5 min)
  └─ GlucoseAnomalyDetector.evaluateUser  [now reads cgm_readings, not notes]
       ├─ latest < 3.9, no OPEN event, not suppressed → INSERT hypo_events (OPEN)
       ├─ latest ≥ 4.5 and OPEN event                 → UPDATE state = EXPIRED
       └─ existing four alert types evaluate on real CGM for the first time

iOS dashboard refresh
  └─ GET /api/hypo-events?state=OPEN
       └─ HypoPromptView  ──confirm(grams)──▶ POST /{id}/confirm
                                                 ├─ create Note(type=hypo_treatment)
                                                 ├─ link note_id, state=CONFIRMED
                                                 └─ (idempotent on repeat)

Next prediction
  └─ CarbsOnBoardService / Hovorka read the note via RescueCarbProfile
       → COB decays over 45 min, not 240
```

## Error handling

| Case | Behaviour |
|---|---|
| Confirm on an already-`CONFIRMED` event | Return the existing note, 200. No second note. |
| Confirm on `DISMISSED` / `EXPIRED` | 409 Conflict — the prompt is no longer live |
| Confirm on another user's event | 404, not 403 — do not disclose existence |
| `grams` missing / non-finite / outside `(0, 100]` | 400 with a field-level message |
| Feature flag off | 404, matching `UnloggedEventController` |
| Detector fails for one user | Logged and skipped; the scan continues for other users, matching the existing per-user try/catch |

## Testing

**Backend unit**
- `RescueCarbProfile`: COB decays to zero by 45 min; both consumers derive identical curves from the shared constants.
- Detector source: `evaluateUser` evaluates from `cgm_readings` with **zero** glucose-bearing notes present — the regression test for the dormancy bug.
- `computeRoc`: existing OLS cases still pass against the retargeted pair type.
- Detector lifecycle: opens on first sub-3.9 reading; does not re-open while one is `OPEN`; expires at ≥ 4.5; does not expire between 3.9 and 4.5.
- Re-prompt suppression: a `DISMISSED` event blocks a new one for 15 min, then permits one if still below 3.9.
- Confirm idempotency: two confirms create exactly one note.
- `preCheckEligibility` returns `"hypo_treatment"` for a rescue note.
- `IsfMealWindowProfileService`: a bolus with a 15 g rescue nearby retains `WEIGHT_CORRECTION` and an uncorrupted ISF estimate.

**Backend integration**
- Full flow: sub-3.9 reading → event opens → confirm → note exists → COB reflects it → COB is zero 45 min later.

**iOS**
- Trigger glucose displays correctly in both mmol/L and mg/dL.
- Quick-pick and custom entry post the expected grams.

The rescue COB path falls under `che.glucosemonitorbe.service.CarbsOnBoardService`, which is inside the existing JaCoCo 80 % line-coverage rule.

## Known limitations

**Push notification delivery is not wired.** `GlucoseAlertService.deliverAlert` logs at WARN and returns; APNs was never implemented. The hypo prompt therefore surfaces only when the app is in the foreground or on next launch — which is precisely the situation where a hypo alert matters least. This is the single largest gap in the feature as scoped, and was accepted deliberately to keep this spec implementable; wiring APNs is the natural follow-on and should be treated as such rather than forgotten.

**Alert cooldown state remains in-memory.** The `hypo_events` table gives the *prompt* durable dedup, but `GlucoseAlertService`'s existing cooldown map still resets on restart and is per-instance. Unchanged by this work.

## Related

- `docs/superpowers/specs/2026-08-08-dual-computation-findings.md` — why detection is not duplicated client-side
- `backend-functional-schema.md` §11 (observer), §7 (learning loops)
- `V9__unlogged_event_flags.sql` — the lifecycle pattern this mirrors
