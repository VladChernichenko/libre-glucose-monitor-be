# Dosing Safety Remediation — Design Spec

**Date:** 2026-08-01
**Status:** Draft — awaiting user approval
**Scope:** `glucose-monitor-be` — `InsulinCalculatorService`, `InsulinCalculatorController`,
`InsulinCalculationRequest`, `GlucoseCalculationsService` (note loading), `VerificationService`
(eligibility)

---

## 1. Background

A clinical review of the prediction and dosing core on 2026-08-01, conducted under a
"shipping to T1D patients in EU/US" lens, found four defects on the patient-harm path. This
spec covers those four. The nine model-mechanics findings from the 2026-07-31 pre-bolus audit
are tracked separately in
`docs/superpowers/specs/2026-07-31-prebolus-live-timer-design.md` and are not addressed here.

The four defects share one failure mode: **when the system lacks a value, it substitutes a
plausible number and presents it as fact.** The fixes below replace fabrication with refusal
at four specific sites.

### The defects

**C1 — Hardcoded carb ratio.** `InsulinCalculatorService:230` computes the meal component as
`request.getCarbs() / 12.0`, ignoring user settings entirely. A sibling `resolveIsf(userId)`
helper exists for the correction component but has no carb-ratio equivalent. Live for 100% of
users (`insulin-calculator-enabled: true`, `insulin-calculator-migration-percent: 100`). For a
patient on 25–30 g/U this recommends 2–2.5× the correct meal bolus.

Root cause: `user_settings.carb_ratio` is **not** an insulin:carb ratio. Per
`UserSettings.java:26` it is "mmol/L blood glucose rise per 10 g carbs absorbed, assuming no
insulin". No g/U value is stored anywhere in the schema, so the calculator had nothing to read.

**C2 — No input validation, no dose ceiling, no hypoglycemia guard.**
`InsulinCalculationRequest` declares four nullable `Double` fields with no constraint
annotations, and `InsulinCalculatorController` binds `@RequestBody` without `@Valid`.
Consequences:

- A mg/dL payload (`currentGlucose=180, targetGlucose=100`) yields a correction of
  `80 / 2.2 = 36.4 U` plus the meal component. No unit field, no range check, no cap.
- `targetGlucose = 0` yields a correction of `currentGlucose / isf`.
- Correction is applied only when `currentGlucose > targetGlucose`, so a patient at
  3.0 mmol/L receives a full meal dose with no reduction and no warning.
- `catch (Exception e)` in the controller returns a fixed 400 with no logging.

**C3 — Fail-open on database error.** `GlucoseCalculationsService.getRecentNotes` (`:519`) and
`getLongActingNotes` (`:503`) catch all exceptions and return an empty list. A transient DB
failure therefore produces `activeCarbsOnBoard = 0.0` and `activeInsulinOnBoard = 0.0`
returned as **HTTP 200 with no error signal**, and cached by the iOS client
(`BackendAPI.swift:841`). A patient who bolused 30 minutes earlier sees "0.00 units active"
and may stack a correction dose.

**H1 — Hypo-contaminated meals drive carb-ratio titration.**
`VerificationService.evaluateEvent` computes `actualDelta = twoHour - baseline` at face value.
If the patient went low after the meal and ate rescue carbs, the recovery is counted as
post-meal rise, producing `meanError > 0`, which suggests carb ratio **upward**, which
increases predicted rise, which increases doses, which causes more hypoglycemia. There is no
check for a low excursion in the window and no exclusion of rescue-carb notes.

---

## 2. Accepted residual risk

The schema is deliberately left unchanged (user decision, 2026-08-01). `V1__baseline_schema.sql:43-44`
declares:

```sql
carb_ratio  DOUBLE PRECISION NOT NULL DEFAULT 2.0,
isf         DOUBLE PRECISION NOT NULL DEFAULT 1.0,
```

Because both are `NOT NULL` with defaults, `settings.getIsf()` is never null, the existing
`resolveIsf` fallback to 2.2 is unreachable, and **application code cannot distinguish "never
configured" from "deliberately set to 1.0"**. An unconfigured user therefore carries
ISF = 1.0 mmol/L/U, a markedly insulin-resistant value that inflates every correction dose.

This spec does not close that path. It bounds the consequences instead: the dose ceiling
(§3.4), the plausibility envelope on the derived ratio (§3.1), and the hypoglycemia refusal
(§3.3) all constrain the output regardless of whether the inputs were configured. Making
`isf`/`carb_ratio` nullable so that "unconfigured" becomes detectable remains open — see §6.

---

## 3. Design

### 3.1 C1 — Derive the insulin:carb ratio from ISF and carb ratio

`carbRatio` is mmol/L per 10 g; `ISF` is mmol/L per U. Therefore:

```
gramsPerUnit = 10 × ISF / carbRatio
```

At population values (ISF 2.2, carbRatio 2.0) this yields 11 g/U, which is why the hardcoded
12 was approximately right on average and wrong for every individual. Deriving it means the
ratio automatically inherits the digital twin's per-user calibration of both parameters.

Add to `InsulinCalculatorService`:

```java
/** Physiological envelope for insulin:carb ratio, g per unit. */
private static final double MIN_GRAMS_PER_UNIT = 3.0;
private static final double MAX_GRAMS_PER_UNIT = 30.0;

private double resolveGramsPerUnit(String userIdStr);
```

Behaviour:

- Reads `UserSettings.isf` and `UserSettings.carbRatio` for the user.
- If either is null, non-finite, or `<= 0` → throw `DosingRefusedException(SETTINGS_INVALID)`.
- Computes `10 × isf / carbRatio`.
- If the result falls outside `[MIN_GRAMS_PER_UNIT, MAX_GRAMS_PER_UNIT]` → throw
  `DosingRefusedException(INSULIN_PARAMS_INCONSISTENT)`.

The envelope is a **refusal boundary, not a clamp**. A derived ratio of 1 g/U means ISF and
carbRatio are mutually inconsistent; silently substituting 3.0 would conceal that and still
produce a wrong dose. The range 3–30 g/U spans a toddler through a severely insulin-resistant
adult.

`calculateRecommendedInsulin` replaces `request.getCarbs() / 12.0` with
`request.getCarbs() / resolveGramsPerUnit(request.getUserId())`.

### 3.2 C2a — Bean validation on the request

Add constraints to `InsulinCalculationRequest` and `@Valid` to the controller parameter:

| Field | Constraint | Rationale |
|---|---|---|
| `carbs` | `@NotNull`, `@DecimalMin("0.0")`, `@DecimalMax("300.0")` | 300 g is an extreme but conceivable single meal |
| `currentGlucose` | `@NotNull`, `@DecimalMin("1.0")`, `@DecimalMax("33.0")` | physiological mmol/L range |
| `targetGlucose` | `@NotNull`, `@DecimalMin("4.0")`, `@DecimalMax("10.0")` | clinically sane target band |
| `activeInsulin` | `@DecimalMin("0.0")`, `@DecimalMax("50.0")` | null permitted, treated as 0 |

`targetGlucose` becoming `@NotNull` with a floor of 4.0 closes the `targetGlucose = 0` path
directly.

#### Unit disambiguation

The `@DecimalMax("33.0")` on `currentGlucose` is the mg/dL guard: any plausible mg/dL reading
(70–400) exceeds the mmol/L physiological ceiling and is rejected. This is enforced as a
validation failure with reason code `GLUCOSE_IMPLAUSIBLE_UNIT` rather than a silent
conversion — guessing the unit is exactly the fabrication pattern this spec removes.

No `glucoseUnit` field is added; the API contract remains mmol/L, consistent with
`GlucoseCalculationsResponse.currentGlucoseUnit`.

### 3.3 C2c — Hypoglycemia handling

Two distinct behaviours, split at the ADA/ATTD Level 1 hypoglycemia threshold of 3.9 mmol/L:

- **`currentGlucose < 3.9`** → refuse with `GLUCOSE_BELOW_SAFE_THRESHOLD`. The clinical action
  is to treat the low, not to bolus. No dose is returned.
- **`3.9 <= currentGlucose < targetGlucose`** → apply a **negative** correction:
  `correction = (currentGlucose - targetGlucose) / isf`, reducing the meal dose. This replaces
  the current behaviour of ignoring the below-target case entirely, and matches standard bolus
  calculator practice.

The existing `Math.max(0.0, ...)` floor on the final dose is retained, so a large negative
correction yields 0 rather than a negative dose.

### 3.4 C2d — Absolute maximum-bolus ceiling

```
maxBolusUnits = 0.3 × bodyWeightKg
```

`user_settings.body_weight_kg` is nullable; when unset, use the 70 kg population default
already documented in the schema comment, giving a 21 U ceiling. 0.3 U/kg is a large single
bolus for essentially any patient, so this bounds catastrophic outputs while scaling correctly
between a child and an adult.

The ceiling is evaluated against the **final** recommended dose — after the meal component,
the correction, and the IOB subtraction — since that is the number the patient would act on.

If that final dose exceeds the ceiling → refuse with `DOSE_EXCEEDS_MAX_BOLUS`. The dose is
**not** clamped to the ceiling: a request that computes to 36 U indicates bad input, and
returning 21 U would present a fabricated number as a recommendation.

### 3.5 C2e — Structured refusals and traceability

Introduce `DosingRefusedException(DosingRefusalReason reason, String detail)` with an enum:

```
INVALID_INPUT
SETTINGS_INVALID
INSULIN_PARAMS_INCONSISTENT
GLUCOSE_BELOW_SAFE_THRESHOLD
GLUCOSE_IMPLAUSIBLE_UNIT
DOSE_EXCEEDS_MAX_BOLUS
```

`InsulinCalculatorController` handles it as **HTTP 422** with body:

```json
{ "reason": "GLUCOSE_BELOW_SAFE_THRESHOLD", "message": "<clinician-safe text>", "backendMode": true }
```

Bean-validation failures map to the same 422 shape. A `currentGlucose` violation of
`@DecimalMax("33.0")` specifically maps to `GLUCOSE_IMPLAUSIBLE_UNIT`; every other constraint
violation maps to `INVALID_INPUT`. The existing blanket `catch (Exception e)` is replaced by handling for
`DosingRefusedException` plus a distinct handler for unexpected exceptions that **logs at
error level with the user id and reason** before returning 500. A dose calculation that
misbehaves must leave a trace.

Both frontends will need to handle 422 explicitly. That is intentional: a 200 carrying a null
dose or a zero dose is the silent-failure pattern this review flagged, and "recommended dose:
0" reads as a clinical statement rather than a refusal.

### 3.6 C3 — Fail closed on missing note data

Remove the `try`/`catch` blocks in `GlucoseCalculationsService.getRecentNotes` and
`getLongActingNotes` so repository exceptions propagate. `GlucoseCalculationsController`
returns 503 rather than a 200 asserting zero insulin on board.

Rationale for propagation over a `dataComplete: false` flag: the iOS client caches this
response, and a flag that a client fails to render leaves a fabricated `0.00 units` on screen.
An error is the only representation that cannot be misread as data.

### 3.7 H1 — Exclude hypo-contaminated meals from titration

In `VerificationService.fullEligibilityCheck`, add two checks over the window
`[note.timestamp, note.timestamp + 2h]`:

1. **Low excursion** — query `cgmReadingRepository` across the window; if any reading is below
   3.9 mmol/L, skip with reason `hypo_in_window`.
2. **Rescue carbs** — if any *other* note in the window carries carbs with no insulin
   (`insulin == null || insulin <= 0`), skip with reason `rescue_carbs_in_window`.

Both reasons are added to the existing `skipReason` vocabulary. The window query reuses the
epoch-millis bridge already present in `toEpochMs`, and the mg/dL → mmol/L conversion already
present in `findClosestCgm`.

This severs the feedback loop: a meal followed by a low can no longer push the carb ratio
upward.

---

## 4. Testing

TDD — a failing test precedes each fix.

**`InsulinCalculatorServiceTest`**
- derived ratio matches `10 × isf / carbRatio` for representative settings
- derived ratio outside 3–30 g/U refuses with `INSULIN_PARAMS_INCONSISTENT`
- null/zero ISF or carbRatio refuses with `SETTINGS_INVALID`
- `currentGlucose = 3.0` refuses with `GLUCOSE_BELOW_SAFE_THRESHOLD`
- `currentGlucose = 5.0, targetGlucose = 6.0` produces a reduced (not unchanged) dose
- dose above `0.3 × bodyWeightKg` refuses with `DOSE_EXCEEDS_MAX_BOLUS`
- body weight null uses the 70 kg fallback ceiling

**`InsulinCalculatorControllerTest`**
- mg/dL payload (`currentGlucose=180, targetGlucose=100`) returns 422, not a dose
- `targetGlucose = 0` returns 422
- missing `carbs` returns 422
- `DosingRefusedException` maps to 422 with the reason code in the body

**`GlucoseCalculationsServiceTest`**
- repository throwing during note load propagates rather than returning zero COB/IOB

**`VerificationServiceTest`**
- a meal with a sub-3.9 mmol/L CGM reading in the 2 h window is skipped `hypo_in_window`
- a meal with a carbs-only note in the window is skipped `rescue_carbs_in_window`
- a clean meal still evaluates and updates the rolling summary

The full existing suite must stay green. Build requires
`JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`.

---

## 5. Client impact

`POST /api/insulin/calculate` gains a 422 response. Both the web frontend and iOS currently
handle only the 200 and generic-error paths, so each needs a branch rendering the refusal
reason. Until that lands, a refusal surfaces as a generic error — degraded but not unsafe,
since no dose is displayed.

`POST /api/glucose-calculations/` gains a 503 on data-load failure where it previously
returned zeros. The iOS caching path in `BackendAPI.swift` must not persist a 503 as a
successful reading.

Client changes are **not** in scope for this spec.

---

## 6. Out of scope

Found during the review, deliberately excluded, recorded so they are not lost:

- **Schema defaults** (`carb_ratio` 2.0, `isf` 1.0, both `NOT NULL`) make "unconfigured"
  undetectable — see §2. The highest-value remaining fix.
- **Four divergent ISF/carb-ratio defaults** across `GlucoseCalculationsService` (1.0 / 2.0),
  `InsulinCalculatorService` (2.2), `VerificationService` (1.0 / 2.0),
  `IsfMealWindowSuggestionService` (2.2).
- **Unbounded, unaudited parameter writes** — `VerificationService.acceptSuggestion` can
  double or halve carb ratio in one step, compounding across acceptances, with no history row.
- **`IsfMealWindowSuggestionService.accept` applies freshly recomputed values**, not the ones
  displayed, and checks only `twinReady` rather than `show`/`suppressReason`.
- **Prediction clamp `[1.0, 25.0]`** saturates inside the severe-hypoglycemia region with no
  `clamped` flag on the response.
- **`calculateConfidence`** is an entry counter reported as confidence, while the fitted
  `PredictionUncertaintyModel` residual σ goes unused on this path.
- **No hypoglycemia prediction output** — no predicted-low flag, time-to-low, or
  time-below-range anywhere in `GlucoseCalculationsResponse`.
- **Client-supplied clock** drives all COB/IOB decay, unvalidated against server time.
- **±20 min CGM matching tolerance** in `VerificationService`, with
  `MEAN_ERROR_THRESHOLD_CR = 0.5 mmol/L` sitting inside CGM noise.
- **`consistencyScore = 1 - stddev/|meanError|`** collapses toward 0 as the model improves.
- **3 h stacking window vs 4.5 h DIA** in `fullEligibilityCheck`.
- **`extractFat` hand-parses JSON** and returns 0 on failure, so malformed profiles let HFHP
  meals into titration.
- **Duplicate frontend/backend dosing implementations** split by `migration-percent`.
- **COB tri-phase fractions** are clamped independently then summed and clamped again,
  producing a flat 100% plateau before decay begins; `gi` defaults to 55 here vs 70 in the ODE.
- **IOB is subtracted from the total dose** including the carb component; most bolus
  calculators subtract from the correction component only. Deliberately unchanged here to keep
  this spec to the four approved defects.
- **Stub endpoints** — `/api/insulin/active-insulin` returns `"backendMode": true` with
  `"note": "Database integration pending"`; `CarbsOnBoardService.getCOBTimeline` returns a
  two-point placeholder.
