# Unlogged-event detector - Specification

**Status:** Draft
**Date:** 2026-07-05
**Author:** spec interview

## Objective

Self-logged input to the glucose model is systematically unreliable: users forget to log food or
insulin, and automated food detection mis-estimates macros. These gaps corrupt the digital-twin
calibration (a forgotten meal looks like the model under-predicting; a forgotten bolus looks like the
user being more insulin-sensitive than they are) and degrade live predictions, with no mechanism today
to notice them - the calibrator only downweights them as generic statistical outliers.

This feature adds a backend **unlogged-event detector**: a scheduled job that runs the physiological
prediction from each user's logged inputs (COB/IOB) over their recent CGM window and flags **sustained,
unexplained residuals** - glucose moving in a way the logged events do not account for. Each flag is
persisted and serves two consumers: (1) it is surfaced via a REST API so the app can ask the user to
confirm/backfill the missing data, and (2) the digital-twin calibration down-weights the flagged window
so an unlogged/mis-logged event can't bias the fit. Success = the calibrator stops learning from
windows the user (or the residual signal) indicates are untrustworthy, and users get a targeted,
low-false-positive prompt to correct their log.

This round is **backend-only** (detection, persistence, API, calibration integration). The in-app
prompt UI (iOS/FE) is a separate follow-up.

## Requirements

1. (Must) A scheduled job scans, per user, a recent CGM window on a fixed cadence (default every
   20 min; configurable) and evaluates it for unexplained residuals.
2. (Must) Detection uses the **model residual**: run the raw Hovorka prediction (no twin overlay) from
   the user's logged carbs/insulin (COB/IOB) across the window, and compare predicted vs actual CGM.
   Because logged events are already accounted for, a large residual is "unexplained."
3. (Must) A window is flagged only when the windowed mean residual exceeds **~2× the user's own robust
   residual σ** (MAD-based, consistent with the calibrator) **and** the exceedance persists for
   **>= ~45 min (>= ~9 consecutive 5-min readings)**. Both thresholds are configurable.
4. (Must) Each flag is classified into one of four categories from the residual sign and whether a
   matching-type event is logged in the window:
   - `UNLOGGED_FOOD` - sustained positive residual (actual > predicted), **no** carbs logged in window.
   - `UNDER_ESTIMATED_FOOD` - sustained positive residual, carbs **are** logged but insufficient.
   - `UNLOGGED_INSULIN` - sustained negative residual (actual < predicted), **no** bolus logged.
   - `UNDER_ESTIMATED_INSULIN` - sustained negative residual, bolus **is** logged but insufficient.
5. (Must) Each flag is persisted with: user, category, direction, window start/end, magnitude
   (mean residual and/or robust-σ multiple), detected-at, and state.
6. (Must) Flag state is one of `OPEN`, `CONFIRMED`, `DISMISSED`. New flags are `OPEN`.
7. (Must) A REST API exposes:
   - list a user's flags (filterable by state);
   - **confirm** a flag - optionally carrying a corrected carbs and/or insulin amount;
   - **dismiss** a flag.
8. (Must) On **confirm with an amount**, the system backfills the log: create/adjust a `Note` at the
   window (carbs and/or insulin), so the model uses real data rather than merely down-weighting. On
   **confirm without an amount**, the flag is simply marked `CONFIRMED`.
9. (Must) The digital-twin calibration excludes/down-weights anchors that fall inside a window flagged
   `OPEN` or `CONFIRMED`. Windows flagged `DISMISSED` keep full weight (the move was real model error
   to learn from). Confirmed flags that were backfilled into notes are treated as normal logged data on
   the next fit (the correction now explains the move).
10. (Must) Detection is idempotent across scans: a still-open flag covering the same window is updated
    in place, not duplicated.
11. (Must) The feature is behind a config flag (default follows the existing `digital-twin-enabled`
    convention) and is a no-op when disabled.
12. (Nice-to-have) Emit a per-scan summary log (users scanned, flags opened/updated) mirroring the
    existing scheduler summaries.
13. (Nice-to-have) Seed (AZT1D `azt1d-subject-%@dataset.local`) users are excluded from scans, as in
    the calibration batch.

## Inputs & Outputs

**Inputs**
- CGM readings (`cgm_readings`, mg/dL -> mmol/L) for the scan window.
- Logged events (`notes`: carbs, insulin, macros, timestamp) for COB/IOB.
- The user's Hovorka base parameters and rapid-insulin/IOB settings (existing services).
- The user's robust residual σ - derived from recent predicted-vs-actual residuals (same MAD-based
  estimate the calibrator uses).
- Config: scan cadence, window length, σ-multiple threshold, persistence minimum, feature flag.

**Outputs**
- Persisted flagged-event records (new entity/table) with state machine.
- REST responses: list of flags; confirm/dismiss results; a created/adjusted `Note` on backfill.
- A signal consumed by the digital-twin calibrator to exclude/down-weight flagged windows.

## Constraints

- Java 21 / Spring Boot backend (`glucose-monitor-be`); PostgreSQL via Flyway; existing scheduling
  infra (`@EnableScheduling` + `taskScheduler`), reused rather than a new mechanism.
- Reuse the existing prediction stack (`HovorkaGlucosePredictionService`, `PredictionReplayEngine`
  or equivalent) - do not re-implement the physiological model.
- Residual σ and Huber conventions must match the calibrator (`DigitalTwinCalibrator`) so the flag
  threshold and the fit's down-weighting are consistent.
- New DB table for flags introduced via a new Flyway migration (a brand-new table justifies a new
  `V{n+1}__` file per the schema rules); do not `ALTER` existing tables in a new migration.
- Server assumed UTC (matches existing hour-of-day / epoch conventions).
- Backend only - no iOS/FE code in this round.
- Backfilled notes must pass the same validation as user-entered notes (non-negative, plausible caps).

## Edge Cases to Handle

- When a short transient (sensor compression low, dropout, warm-up) causes a spike shorter than the
  persistence minimum, then no flag is raised (persistence gate rejects it).
- When the user has too little residual history to estimate a trustworthy robust σ, then the scan
  skips that user (no flags) rather than using an unstable σ.
- When a window already has an `OPEN` flag, then a subsequent scan updates it in place (window bounds,
  magnitude) instead of creating a duplicate.
- When the logged events fully explain the move (small residual), then no flag is raised.
- When excluding flagged windows would drop the calibration below its minimum-anchor requirement, then
  the fit proceeds without the exclusion (accuracy of the flagged data is preferable to no fit) and
  this is logged.
- When a confirm request carries an implausible or negative backfill amount, then it is rejected with
  a validation error and the flag stays `OPEN`.
- When confirm/dismiss targets a flag that is already resolved or belongs to another user, then the
  request is rejected (idempotent/authorized).
- When the feature flag is off, then the scanner does nothing and the calibrator ignores flags.
- When both a rise and an opposing event coexist ambiguously in a window, then the flag is classified
  by the dominant residual sign over the window.

## Out of Scope

- iOS / FE prompt UI and push notifications (separate follow-up round).
- Any therapeutic/dosing action - this never adjusts insulin or dosing settings.
- Sensor-artifact taxonomy/classification beyond the persistence gate (no compression-low/dropout
  detectors as such).
- Per-data-source noise models, changepoint/regime-shift detection, DDE modelling.
- Online/per-reading learning - detection is a periodic scan, calibration remains the nightly batch.

## Definition of Done

- [ ] A scheduled scanner runs on the configured cadence and, for a user with a synthetic unexplained
      rise (carbs withheld), opens an `UNLOGGED_FOOD` flag over the correct window.
- [ ] The four categories are produced correctly for the four constructed scenarios (unlogged vs
      under-estimated × food vs insulin).
- [ ] A transient spike shorter than the persistence minimum does **not** produce a flag.
- [ ] The threshold adapts per user: the same absolute residual flags a low-σ user but not a high-σ
      user.
- [ ] Re-running the scan over the same window updates the existing `OPEN` flag rather than creating a
      duplicate.
- [ ] `GET` returns a user's flags; `confirm` (with and without amount) and `dismiss` transition state
      correctly; confirm-with-amount creates/adjusts a `Note`.
- [ ] The digital-twin calibration excludes/down-weights anchors inside `OPEN`/`CONFIRMED` windows and
      keeps `DISMISSED` windows at full weight - verified on a constructed case where excluding a
      corrupted window improves the out-of-sample fit.
- [ ] Confirm/dismiss are authorized (a user cannot act on another user's flag) and idempotent.
- [ ] The feature flag disables scanning and calibration consumption cleanly.
- [ ] Tests: unit tests for detection/classification/threshold/persistence; an integration test for the
      API state transitions and the backfill note; a calibration test proving flagged-window exclusion.
- [ ] `./gradlew build` (compile + tests) is green.
