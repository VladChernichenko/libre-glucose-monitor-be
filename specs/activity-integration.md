# Physical-activity integration - Specification

**Status:** Draft
**Date:** 2026-07-05
**Author:** spec interview

## Objective

Physical activity is the largest driver of insulin-sensitivity change that the glucose model does not
represent today. Exercise increases glucose uptake - both directly (contraction-mediated, insulin-
independent) and by amplifying insulin action - and that elevated sensitivity persists for hours after
the activity ends, which is a leading cause of delayed post-exercise hypoglycemia. Because the model
ignores activity, exercise-driven drops appear as unexplained residuals: they degrade predictions and
would surface as false "unlogged insulin" flags in the unlogged-event detector.

This round adds an **activity term to the Hovorka ODE** driven by a normalized activity intensity
`a(t) ∈ [0,1]`, plus a clean `ActivityProvider` input interface and a HUPA-dataset adapter to validate
it offline. It does **not** wire live activity ingestion, persistence, an API, digital-twin gain
calibration, or the detector - those only pay off once a live activity feed exists and are explicit
follow-ups. Success = the model can consume an activity signal and reproduce the exercise-driven extra
glucose drop and its post-exercise tail, with zero change to predictions when no activity data is
present.

## Requirements

1. (Must) The Hovorka ODE integration accepts a per-minute activity intensity `a(t) ∈ [0,1]` and, when
   `a(t) > 0`, modulates glucose dynamics in two coupled ways from a single intensity:
   - **Insulin-sensitivity amplification** - scales the insulin effect up by `(1 + gainInsulin*a)`.
   - **Insulin-independent uptake** - adds a glucose-removal flux proportional to `gainIndep*a` (acts
     even when IOB ≈ 0).
2. (Must) A single population-default gain configuration drives both effects (one calibratable knob
   conceptually; not per-user calibrated this round). Gains are configurable constants.
3. (Must) **Post-exercise tail** - the sensitivity amplification does not drop to zero the instant
   activity stops; `a(t)` fed to the sensitivity term includes an exponentially-decaying tail after
   activity ends, with a configurable half-life (default ~2 h).
4. (Must) Activity reaches the predictor through an `ActivityProvider` abstraction that returns
   `a(t)` for a given time. A `NONE` provider returns `0` everywhere.
5. (Must) With the `NONE` provider (the production default - no live activity data), predictions are
   **identical** to the current model (the activity term is fully inert).
6. (Must) A HUPA adapter builds an `ActivityProvider` from a subject's data: `a(t)` = heart-rate
   reserve `(HR − rest)/(max − rest)`, clamped to `[0,1]`, using default resting/max HR when unknown;
   falls back to a normalized steps rate when HR is missing; `a = 0` when both are absent. A **fixed
   deadband** (a constant reserve threshold, ~0.30 - i.e. ≈ >99 bpm at rest 60 / max 190 - not a
   per-user calibration) is applied so ordinary daily heart rate does not register as low-grade
   exercise; the reserve above the deadband is rescaled to `[0,1]`.
7. (Must) `a(t)` is clamped to `[0,1]`, and the added terms are bounded so the ODE stays stable
   (glucose never driven negative or to unphysical values; existing state floors respected).
8. (Must) A data-gated HUPA validation harness runs the calibration/prediction sweep with vs. without
   the activity term and reports overall MAE for both (informational), analogous to the existing
   `HupaUcmCalibrationValidationTest`.
9. (Nice-to-have) The harness also reports MAE restricted to high-activity windows (where the term is
   expected to help most).

## Inputs & Outputs

**Inputs**
- `a(t) ∈ [0,1]` per integration minute, supplied by an `ActivityProvider`.
- HUPA per-row `heart_rate` and `steps` (and default resting/max HR constants) for the adapter.
- Configurable constants: insulin-sensitivity gain, insulin-independent-uptake gain, tail half-life,
  HR resting/max defaults, steps normalization cap.

**Outputs**
- Modulated glucose prediction path (unchanged shape/DTO; values reflect the activity term when `a>0`).
- HUPA harness report: overall MAE with vs. without the activity term (and, nice-to-have, on
  high-activity windows).

## Constraints

- Java 21 / Spring Boot backend (`glucose-monitor-be`); reuse `HovorkaOdeSolver`,
  `HovorkaGlucosePredictionService`, and the existing prediction/replay path - do not re-implement the
  model or the gut/insulin sub-models.
- The `ActivityProvider` interface mirrors the existing `PredictionResidualProvider.NONE` pattern
  (a `NONE` default that makes the feature inert).
- No new dependencies. No DB migration, entity, persistence, or REST API this round.
- No live activity ingestion (HealthKit / wearable / Nightscout) this round.
- UTC time convention consistent with the rest of the codebase.
- Backend only; HUPA validation is data-gated (`-Dhupa.dir=...`), skipped when absent, like the
  existing AZT1D/HUPA harnesses.
- Keep files under 500 lines; follow existing package/naming conventions.

## Edge Cases to Handle

- When the `ActivityProvider` is `NONE` (or returns 0), then the ODE behaves exactly as today (no
  measurable prediction change).
- When heart rate is missing for a sample, then the adapter falls back to steps; when both are missing,
  then `a = 0` for that time.
- When a raw signal implies `a` outside `[0,1]` (e.g. HR above max, huge step count), then it is
  clamped to `[0,1]`.
- When activity is sustained and intense, then the added uptake is bounded so glucose stays within
  physiological limits (no negative/unphysical values).
- When activity has ended, then the sensitivity tail decays toward zero within a few half-lives and
  does not persist indefinitely.
- When activity occurs while IOB ≈ 0, then the insulin-independent term still produces a glucose drop
  (the case a pure sensitivity multiplier would miss).

## Out of Scope

- Live activity ingestion, persistence (no table/entity), and any REST API.
- Making the activity gain a per-user digital-twin-calibrated parameter.
- Feeding activity into the unlogged-event detector (activity-awareness of detection).
- Per-user heart-rate calibration (age-based max HR, measured resting HR).
- Stress, illness, and other non-activity sensitivity modulators.
- iOS/FE changes.

## Definition of Done

- [ ] A deterministic unit test shows that, given an activity pulse, the activity-aware ODE predicts a
      **larger glucose drop during** the pulse than the no-activity model, and a **decaying elevated-
      sensitivity tail after** it ends (drop continues/relaxes over the configured half-life).
- [ ] A unit test shows the insulin-independent term produces a drop even when IOB ≈ 0.
- [ ] A regression test shows the `NONE` provider yields predictions identical to the current model
      (bit-for-bit on a fixed scenario).
- [ ] `a(t)` clamping and ODE stability verified (no negative/unphysical glucose under max sustained
      activity).
- [ ] The HUPA adapter derives `a(t)` from HR reserve with a steps fallback and `a=0` when both absent
      (unit-tested on constructed rows).
- [ ] A data-gated HUPA harness runs and reports overall MAE with vs. without the activity term;
      activity-aware overall MAE is **not worse within a small tolerance (<= 0.05 mmol/L)** than without.
      (On HUPA the term is expected to be roughly neutral, not beneficial: the cohort has little genuine
      exercise and gains are population defaults, not per-user tuned. Real-world benefit needs the
      deferred per-user gain calibration and cleaner activity data.)
- [ ] `./gradlew build` (compile + tests) is green.
