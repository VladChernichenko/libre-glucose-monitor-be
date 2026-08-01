# Pre-bolus live timer in `/api/predict` — design

- **Date:** 2026-07-31
- **Status:** approved, ready for implementation planning
- **Scope:** `glucose-monitor-be`; one additive field on the iOS request payload

## Problem

The iOS app already measures the real pre-bolus interval. A note with
`meal ∈ {"pre-bolus", "prebolus"}` and `insulin > 0` starts a timer
(`PreBolusTimer.state()` in `GlucoseMonitor/DashboardExtras.swift`); the timer stops
when a note with `carbs > 0` (excluding `correction` and the pre-bolus aliases) is
logged, or after 2 hours. The interval is therefore already in the database as the
difference between two note timestamps.

`POST /api/predict` does not use it, and gets three things wrong as a result.

### Defect 1 — the recommended pause has inverted semantics

`GlucosePredictService.predict()` pins the prospective meal at `now`
(`GlucosePredictService.java:118`) and searches bolus timestamps at
`now.plusMinutes(pause)` (`:217`, `:160`). A pre-bolus means *inject, wait, eat*. As
implemented the search covers *eat, wait, inject*, so the `preBolusMinutes` returned
to the client describes the opposite of what the field name promises.

### Defect 2 — the already-logged dose is counted twice

Starting the timer requires the client to write the pre-bolus note first. That note is
picked up by `loadRecentNotes` / `toInsulinDoses` into `pastDoses`
(`GlucosePredictService.java:88`, 8-hour window). Every simulation then appends
`req.insulinDose` as an additional synthetic dose on top of that history — once per
candidate inside `optimisePreBolus` (`:212`), and once more in the final run (`:155`).
Each individual simulation therefore sees the logged dose plus a synthetic duplicate of
it. When the client sends the dose it has just logged, the insulin channel of the
prediction is driven by roughly twice the real dose.

### Defect 3 — historical meals lose their macros and GI

`GlucosePredictService.toCarbsEntries` (`:271`) builds each `CarbsEntry` with only
`carbs`, `mealType` and `originalCarbs`. `CarbsEntry` has `estimatedGi`,
`glycemicLoad`, `fiber`, `protein` and `fat` fields, and `Note.nutrition_profile`
holds the values, but nothing populates them on this path. Every meal in history is
therefore modelled as pure carbohydrate at the default GI of 70: no GLP-1 ileal brake,
no protein gluconeogenesis tail, no fiber viscosity. Only the single prospective meal
in the request carries macros.

`GlucoseCalculationsService.convertNoteToCarbsEntry` (`:526`) already performs exactly
this hydration for the legacy path. The fix is reuse, not new logic.

## Out of scope

Found during the audit, deliberately excluded from this spec, recorded so they are not
lost:

- Protein and fat slow absorption through three independent multiplicative channels
  (`caloricScale` on `k_gri`/`k_max`/`k_min`, `effectiveKAbs` on `k_abs`, and the GLP-1
  ileal brake `φ`).
- `giScale = GI/100` is normalised against pure glucose while the Dalla Man constants
  are calibrated on a mixed meal, so the default GI of 70 applies a systematic 0.7×
  slowdown.
- GI is applied to gastric emptying (`k_gri`, `k_max`, `k_min`), which depends on
  volume and caloric density rather than glycemic index.
- `tMaxG` is a single scalar for the whole forecast, so a prospective meal's macros
  retroactively change the absorption rate of food already being digested.
- The `x3` EGP-suppression branch is numerically inert: `KB3 = 1.5e-5` corresponds to
  `S_IE = 5e-4` against Hovorka 2004's `520e-4`, and the `V_I_SCALE = 12.0` bridge
  yields a peak pseudo-insulin of 0.56 against a physiological ~35 mU/L. Measured EGP
  suppression at the peak of a 6 U bolus is 0.02%. This is entangled with an
  over-strong ISF channel and must not be corrected in isolation.
- Glycemic load is computed and stored but never enters the ODE.
- `insulinEffect` and `activityRate` are held constant across all four RK4 stages,
  reducing the order of accuracy for the forcing term.
- The pre-bolus cost sum weights 5-minute and 10-minute emission points equally, so the
  tail of the horizon is under-counted (`GlucosePredictService.java:224`).
- `calculatePreBolusTimingContribution` (`GlucoseCalculationsService.java:408`) models
  bolus-to-meal timing a second time, as a ±1.2 mmol/L additive heuristic on the legacy
  path, and ignores the `pre-bolus` marker entirely.

Each of the first five requires a `BacktestHarness` run to verify, because they
partially cancel one another.

## Approach

Trust the logged history and suppress synthesis. The Hovorka ODE already models
bolus-to-meal timing correctly from real timestamps — no separate timing term is
needed. The fix is largely subtractive: stop adding a dose that is already present,
and stop searching for a pause that has already been measured.

Rejected alternatives:

- **An explicit `mode` field with no auto-detection.** Cleaner contract, but nothing
  improves until an iOS release ships, and every existing client keeps double-counting
  insulin until then.
- **Dose-level deduplication only.** A small patch, but matching on (timestamp, units)
  cannot distinguish two identical doses given twenty minutes apart, and it fixes
  neither the inverted sign nor the missing observed-pause output.

## Components

### `PreBolusContext` (new record)

Fields: `bolusTime`, `units`, `elapsedMinutes`, `source ∈ {EXPLICIT, DETECTED}`.
Data only.

### `PreBolusResolver` (new `@Component`)

Single method:

```java
Optional<PreBolusContext> resolve(LocalDateTime insulinLoggedAt,
                                  List<Note> recentNotes,
                                  LocalDateTime now)
```

A pure function of its arguments — no repository, no clock — so it is testable in
isolation.

1. If `insulinLoggedAt` is present, find the note at that timestamp (±1 minute) with
   `insulin > 0` and `!isLongActing()`. Found → `EXPLICIT`. Not found → fall through
   to step 2.
2. Detection, mirroring `PreBolusTimer.state()`: the latest note with
   `meal.toLowerCase() ∈ {"pre-bolus", "prebolus"}`, `insulin > 0`,
   `!isLongActing()`, timestamp within 2 hours of `now`, and no note with `carbs > 0`
   and `meal.toLowerCase() ∉ {"correction", "pre-bolus", "prebolus"}` at or after that
   timestamp. Found → `DETECTED`, otherwise empty.

The 2-hour window and the excluded meal names are duplicated from Swift. This is a
known drift risk: any change to `PreBolusTimer.state()` must be mirrored here, and the
resolver's tests are the guard.

### `NoteToCarbsEntryMapper` (new `@Component`)

Extracted from `GlucoseCalculationsService.convertNoteToCarbsEntry` (`:526`) without
behavioural change, including `absorptionMode` defaulting and the
`app.features.nutrition-aware-prediction-enabled` toggle.
`GlucoseCalculationsService` delegates to it; `GlucosePredictService.toCarbsEntries`
starts using it, which resolves defect 3.

### Contract changes

Additive; no existing field changes type or name.

| Location | Field | Type | Meaning |
|---|---|---|---|
| `PredictRequest` | `insulinLoggedAt` | nullable timestamp | when the pre-bolus note was written |
| `PredictResponse` | `observedPreBolusMinutes` | nullable integer | measured elapsed minutes |

`preBolusMinutes` retains its existing meaning — a recommendation — and is populated
only on the advisory path. On the live-timer path it is `null`, because no
recommendation is computed. Clients that read it unconditionally must tolerate `null`;
the iOS timer holds the elapsed value locally, so nothing regresses there.

Exactly one of the two fields is non-null in any response.

## Data flow

`predict()` resolves the context once, then branches.

### Live-timer path — context resolved

- Do not append `req.insulinDose`. The dose is already in `pastDoses`.
- Do not call `optimisePreBolus`. One ODE run replaces eight.
- The prospective meal stays at `now`; the PGN-equivalent entry stays at
  `now + pgnOnset`.
- `observedPreBolusMinutes = context.elapsedMinutes()`, `preBolusMinutes = null`.
- If `req.insulinDose > 0` and differs from `context.units()` by more than 0.05 U, log
  a warning and use the note. The database is the source of truth for what was
  injected.

### Advisory path — no context

- Bolus at `now`, meal at `now + pause`. This is the defect-1 sign fix.
- The PGN-equivalent entry is anchored to the meal, so it moves to
  `now + pause + pgnOnset`.
- Each candidate simulates `horizon + pause` minutes and is scored over that whole
  window — `now` to `now + horizon + pause` — from the injection onward. With a fixed
  `horizon` a 30-minute pause would receive 30 fewer minutes of post-meal curve than a
  0-minute pause, biasing the optimiser toward longer pauses for purely numerical
  reasons; extending the simulation by the pause removes that bias.
- The window deliberately includes the pre-meal interval `[now, meal)` rather than
  starting at the meal. That interval is exactly where an over-long pre-bolus does its
  damage — insulin acting with no carbs yet — and it grows with the pause, so excluding
  it would weaken the hypo penalty precisely for the most aggressive candidates.
- The per-point penalties are aggregated as a **trapezoidal time-weighted mean**,
  `Σ ½(cᵢ + cᵢ₊₁)·Δtᵢ / (t_last − t_first)`, not a per-sample average. The ODE grid is
  not uniform — 5-minute steps below 240 min, 10-minute steps beyond — so a per-sample
  mean counts a sparse-tail point as worth the same as a dense-head point, at half its
  true duration. Since the window length varies with the pause, so does the dense/sparse
  mix, and a candidate could win on emission arithmetic rather than on modelled glucose.
  Weighting by Δt makes the score a mean penalty per minute, independent of both sample
  count and emission density. A window yielding fewer than two points scores
  `Double.MAX_VALUE`, never 0.0, so a degenerate window can never win.
- The final client-facing simulation also runs for `horizon + bestPause`, so the returned
  curve is the winning candidate's own curve rather than one truncated `bestPause`
  minutes short of every window that was scored.
- Consequently the meal entry and the PGN entry are rebuilt inside the candidate loop
  rather than hoisted above it.
- `preBolusMinutes = bestPause`, `observedPreBolusMinutes = null`.

Both paths share the defect-3 fix through `NoteToCarbsEntryMapper`.

## Edge cases

Every resolver failure degrades to "no context" rather than an error. A `400` would
break a prediction the user is actively watching, and the advisory path is always safe.

| Input | Behaviour |
|---|---|
| `insulinLoggedAt` in the future | ignored, falls through to detection |
| `insulinLoggedAt` older than 2 hours | ignored — the timer would have expired |
| `insulinLoggedAt` matches no note | ignored, falls through to detection |
| Several pre-bolus notes, no meal between | latest wins, matching iOS |
| Long-acting note marked `pre-bolus` | excluded via `isLongActing()` |
| Malformed `nutrition_profile` | mapper logs and returns the entry without macros |
| Nutrition toggle off | no macros; predict behaves as it does today |

Taking the latest of several pre-bolus notes is safe rather than merely conventional:
every logged dose is already in `pastDoses` regardless of which note resolves, so the
context governs only whether a synthetic dose is appended — never which doses are
simulated.

### Timezone convention

`predict()` derives `now` from `LocalDateTime.now()` (server clock), notes are stored
as `TIMESTAMPTZ`, and `PredictRequest` separately carries `clientTimezone`. This
exposure already exists in `minsAgoFromNow`, where it merely shifts a curve. Once
`observedPreBolusMinutes` is returned it becomes a visibly wrong number displayed next
to a running timer.

The implementation must pin one convention explicitly rather than inherit it: resolve
`insulinLoggedAt` and note timestamps into the same zone before differencing, and cover
it with a test that runs under a non-UTC server default.

## Testing

Mock-first, per the project's TDD London convention: mock
`HovorkaGlucosePredictionService` and assert on captured arguments.

**`PreBolusResolverTest`** — port `GlucoseMonitorTests/PreBolusTimerTests.swift` case
for case: no notes, zero insulin, nil timestamp, meal after bolus, `prebolus` alias,
case-insensitivity, 2-hour expiry. Plus the explicit-field cases: exact match, ±1
minute tolerance, future timestamp, no matching note.

**`NoteToCarbsEntryMapperTest`** — macro hydration, malformed JSON, toggle off, and a
characterisation test asserting `GlucoseCalculationsService` output is unchanged after
the extraction.

**`GlucosePredictServiceTest`** — one regression per defect:

- Live-timer path: total units reaching the ODE equal the note's units (defect 2).
- Advisory path: bolus timestamp ≤ meal timestamp for every candidate (defect 1).
- Past meals reach the ODE carrying protein, fat and GI (defect 3).
- Advisory path: the PGN entry sits at `meal + pgnOnset`.
- Advisory path: candidates are scored over equal-length post-meal windows.
- Timezone: elapsed minutes are correct under a non-UTC server default.

Endpoint-level coverage uses the existing `prediction-e2e-tests` skill.

## Risks

- **Swift/Java duplication.** `PreBolusResolver` restates `PreBolusTimer.state()`. The
  ported test suite is the only guard against drift.
- **`preBolusMinutes` becomes null** on the live-timer path. Additive in type but
  behavioural for any client that reads it unconditionally.
- **Auto-detection misfires** if a user logs a `pre-bolus` note and then eats without
  logging carbs. The 2-hour expiry bounds the blast radius, and the consequence is a
  suppressed recommendation rather than a wrong dose.
