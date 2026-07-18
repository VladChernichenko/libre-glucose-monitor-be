# Activity logging — Specification

**Status:** Draft
**Date:** 2026-07-05
**Author:** spec interview

## Objective

Physical activity strongly changes glucose (raises insulin sensitivity and glucose uptake, with a
post-exercise tail), and the model already has the ODE machinery to represent it (`ActivityProvider`
`a(t) ∈ [0,1]` → `ActivityModulation`). What it lacks is a data source: heart-rate/wearable data isn't
always available. This feature lets users **log an activity as a note** — its type, a chosen intensity,
and a duration — and makes that log the primary activity signal: it is persisted and retrievable, it
feeds the glucose model, and the digital twin learns a per-user activity response from it.

Success = a user can log "moderate run, 40 min," and (1) it is stored and returned like any note, (2)
the forecast reflects it (a deeper dip during and after the activity), (3) the unlogged-event detector
no longer mistakes that exercise-driven drop for unlogged insulin, (4) the digital-twin calibration
accounts for it, and (5) over time the twin personalizes how strongly that user responds to activity.
When no activity is logged, behavior is exactly as it is today.

## Requirements

1. (Must) A new `ACTIVITY` note type is added to the `notes` table with typed columns: `activity_type`
   (e.g. walking/running/cycling/strength/other), `intensity` (LOW/MODERATE/HIGH/VERY_HARD), and
   `duration_min` (integer minutes). Columns are added by editing `V1__baseline_schema.sql` (project
   rule: no `ALTER` in a new migration). The note's `timestamp` is the activity start.
2. (Must) Users can create, retrieve, update, and delete an activity note through the existing notes
   API/service, supplying type + intensity + duration. These fields are validated at the boundary
   (known type, known intensity level, `duration_min` > 0 and within a sane cap).
3. (Must) Intensity maps to `a(t)`: LOW→0.25, MODERATE→0.5, HIGH→0.75, VERY_HARD→1.0.
4. (Must) Activity type is persisted and available for analysis/learning but does **not** change the
   ODE this round; the effect is driven by intensity + duration via the existing lowering `a(t)` term
   (aerobic-style assumption, documented).
5. (Must) A notes-derived `ActivityProvider` yields, for a user at time `t`, the mapped intensity of any
   logged activity whose window `[start, start + duration_min]` contains `t` (the maximum when windows
   overlap), clamped to `[0,1]`, else 0. The existing `ActivityModulation` post-exercise tail applies on
   top.
6. (Must) The notes-derived provider is wired into: (a) the live prediction path, (b) the
   unlogged-event detector's raw prediction, and (c) the digital-twin calibration replay
   (`PredictionReplayEngine`), so all three account for logged activity.
7. (Must) With **no** activity logged for a user (empty provider), predictions, detection, and
   calibration are **identical** to current behavior (the activity path is inert).
8. (Must) The digital twin fits a **per-user activity gain** as an additional calibrated parameter,
   **gated**: it is fitted only when the user has at least a minimum amount of activity that overlaps
   CGM, and is applied only if the calibrated twin still beats the un-calibrated model on the held-out
   window (the existing out-of-sample gate). Otherwise the population-default gain is used. The learned
   gain is persisted on the user's twin (edit `V8__user_digital_twin.sql` + entity).
8b. (Must) The whole feature is behind a feature flag (following the `digital-twin-enabled` convention);
   when disabled, activity notes may still be stored but are not consumed by the model.
9. (Nice-to-have) The activity gain's status (fitted vs population default, value) is exposed on the
   existing digital-twin status endpoint.

## Inputs & Outputs

**Inputs**
- Activity note fields: `activity_type`, `intensity` (level), `duration_min`, `timestamp` (start).
- Existing CGM, meals, insulin (unchanged) for prediction/calibration.

**Outputs**
- Persisted activity notes, returned via the notes API (with the new fields).
- Prediction path modulated by logged activity (`a(t)` → sensitivity amplification + insulin-independent
  uptake + tail).
- Unlogged-event detector residuals computed against an activity-aware prediction.
- Digital-twin calibration that accounts for activity, plus a persisted per-user activity gain (and,
  nice-to-have, its status in the twin status response).

## Constraints

- Java 21 / Spring Boot backend (`glucose-monitor-be`); PostgreSQL via Flyway. Reuse the existing
  `Note` entity/notes service, `HovorkaGlucosePredictionService`, `ActivityProvider`/`ActivityModulation`,
  `PredictionReplayEngine`, `DigitalTwinCalibrator`, and the unlogged-event detector — do not
  re-implement them.
- Schema changes via editing the owning migration files (`V1` for `notes`, `V8` for `user_digital_twin`),
  not new `ALTER` migrations.
- UTC time convention; validate all user input at the boundary.
- Backend only. No iOS/FE UI (the app builds the logging screen).
- Keep files under 500 lines; follow existing conventions.
- No new external dependencies.

## Edge Cases to Handle

- When an activity note has `duration_min ≤ 0`, an unknown `activity_type`, or an unknown `intensity`,
  the create/update is rejected with a validation error.
- When two logged activities overlap in time, `a(t)` is the maximum of their mapped intensities (clamped
  to 1), not the sum.
- When a user has logged no activity, the provider returns 0 everywhere and the model output is
  bit-identical to today.
- When an activity note is edited or deleted, the next prediction/detection/calibration reflects the
  change (no stale activity is used).
- When activity precedes the prediction anchor, its post-exercise tail is warm-started (existing
  `ActivityModulation.WARMUP_MINUTES` behavior) so a recent workout still lowers the current forecast.
- When the user has activity but too little overlapping CGM to identify the gain, the population-default
  gain is used (no per-user fit).
- When the per-user gain fit does not beat the held-out baseline, it is not applied (twin stays on the
  population default / un-calibrated model).
- When the feature flag is off, activity notes are not consumed by prediction/detector/calibration.

## Out of Scope

- Live heart-rate / wearable ingestion (still deferred; this manual log is the primary source now).
- Type-specific physiology (e.g. anaerobic/strength transiently *raising* glucose) — type is stored but
  does not change the ODE this round.
- iOS / FE UI and any push notifications/alerts.
- Per-activity effect analytics endpoints beyond the twin-gain status (nice-to-have only).

## Definition of Done

- [ ] An `ACTIVITY` note with type + intensity + duration can be created and retrieved via the notes API,
      persisted in the new typed columns; update/delete work; invalid type/intensity/duration are
      rejected.
- [ ] Intensity→`a(t)` mapping is unit-tested (LOW/MODERATE/HIGH/VERY_HARD → 0.25/0.5/0.75/1.0).
- [ ] The notes-derived `ActivityProvider` is unit-tested: level inside the window, 0 outside, maximum
      on overlap, clamp to [0,1].
- [ ] With no activity logged, prediction output is bit-identical to the current model (regression test).
- [ ] An integration test shows a logged activity makes the forecast dip more during and after the
      activity than with no activity note.
- [ ] An integration test shows the unlogged-event detector does **not** flag an exercise-driven drop
      when the matching activity is logged, but does when it is absent.
- [ ] The activity provider is threaded through `PredictionReplayEngine`; a calibration test shows the
      replay accounts for activity (different result with vs. without a logged activity on a constructed
      case).
- [ ] Per-user activity gain: a test shows it is fitted and applied when there is sufficient
      activity-overlapping data and it beats the holdout, and falls back to the population default
      otherwise; the learned gain is persisted on the twin.
- [ ] Feature flag off ⇒ activity is not consumed by the model (test).
- [ ] `./gradlew build` (compile + tests) is green.
