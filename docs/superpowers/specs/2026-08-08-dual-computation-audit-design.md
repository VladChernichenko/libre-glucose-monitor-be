# Dual-Computation Divergence Audit — Design Spec

**Date:** 2026-08-08
**Status:** Draft — awaiting user approval
**Scope:** `glucose-monitor-be` — `GlucoseCalculationsService`, `HovorkaGlucosePredictionService`
and collaborators (`BasalInsulinResolver`, `DallaManGutModel`, `PredictionResidualProvider`),
`CarbsOnBoardService`, `InsulinCalculatorService`, `ExperimentService`

---

## 1. Background

On 2026-08-07/08 a dashboard review found that the iOS app's "2h forecast" headline and its
own forecast chart disagreed — sometimes in direction (headline predicting a rise while the
chart showed a steep fall toward hypo range). Root cause: `GlucoseCalculationsService.
calculateGlucoseData` computed `twoHourPrediction` via a standalone COB/IOB-only formula
(`calculatePredictedGlucose`) that never looked at `predictionPath`, the Hovorka-model array
the chart actually renders. Fixed in commit `30b76bd` by reading `twoHourPrediction` off
`predictionPath` at the 2h index instead, mirroring how `fourHourPrediction` already worked.

That fix addressed one instance of a general pattern: **a logical quantity computed by more
than one independent code path, with nothing enforcing that the two agree.** This spec is a
plan to search the backend's prediction/calculation core for other instances of that pattern,
before deciding which (if any) to fix.

A second, related screenshot (2026-08-08, 2:24 PM) showed a visible upward step in the
prediction chart immediately after the "now" boundary, while IOB=3.65u (active correction
bolus, 1:02 PM) and COB=9.7g (active lunch, 1:50 PM) were both live — insulin action should be
pulling glucose down through that point, not up. This is plausibly the same family of bug,
localized to the boundary between the chart's "now" anchor (pinned to the raw current sensor
reading) and the Hovorka model's own first computed point, specifically under active-dose
conditions. It's called out as a specific, prioritized target below rather than left to the
general sweep, since it's a live, reproducible symptom, and the one existing test for "no jump"
(`waningBasal_noCobNoIob_risesGraduallyWithoutInitialJump`) only covers the *idle*
(zero COB/IOB) case.

## 2. Goal

Produce a findings report — no fixes yet — listing every place in scope where a logical
quantity (a glucose prediction at some horizon, COB, IOB, ISF, trend, confidence, or similar)
is computed via more than one independent path, with an assessment of whether that's a bug or
an intentional simplification.

## 3. Scope

**In scope:** `glucose-monitor-be` only. Within it:

- `GlucoseCalculationsService` (the response DTO this returns is the primary trace target)
- `HovorkaGlucosePredictionService` + `BasalInsulinResolver`, `DallaManGutModel`,
  `PredictionResidualProvider`
- `CarbsOnBoardService`, `InsulinCalculatorService`
- `ExperimentService`
- Specific prioritized target: continuity between `predictionPath[0]` (and the anchor-adjacent
  points) and the `currentGlucose` value passed in, under active IOB/COB — i.e. does the
  Hovorka warm-up (`buildWarmState`, `HovorkaState.steadyState`, the per-dose IOB timelines)
  produce a first-step value consistent with the raw sensor reading it's supposed to continue
  from, when a dose is actively working?

**Out of scope (explicitly deferred):**

- iOS app and web frontend (`glucose-monitor-fe`) — this pass is backend-only
- Broader bug classes (stale caches, unit conversion, timezone/clock handling, off-by-one
  windowing) — only the dual-computation-divergence pattern
- Any area outside the calculation/prediction domain listed above

If findings suggest the pattern is present elsewhere (e.g. in `glucose-monitor-fe`, which has
its own `ForecastChartCard` / `CompactGlucoseCard`), they'll be noted but not chased in this
pass.

## 4. Method

For each response DTO built by the in-scope services (starting with
`GlucoseCalculationsResponse`, then `ExperimentService`'s outputs), enumerate every field and
trace it to its computing method(s) by direct code reading. Flag any two fields/paths that
represent the same or overlapping physiological quantity but reach it independently.
Explicitly re-verify fields that already share a data source (like `fourHourPrediction` reading
off `predictionPath`) to confirm the sharing is real and not just apparent.

For the prioritized Hovorka-continuity target: trace `buildWarmState` and the forward
integration loop specifically for the case where `pastInsulinDoses` / `pastCarbsEntries` are
non-empty at "now" (an active dose/meal), and check whether the state handed from warm-up to
the forward loop is consistent with what the warm-up "should" produce at t=0 vs. what the
forward loop's first emitted point (t=5min) actually is.

**Handling uncertainty:** where a divergence could be either a bug or an intentional
simplification and the code/comments don't settle it, stop and ask rather than guess.

## 5. Output

A findings report (markdown), each entry containing:

- The quantity in question
- The competing computation paths, with file:line references
- A concrete scenario in which they'd disagree (ideally with rough numbers, in the style of the
  9.0 → 9.5-vs-5.7 case from the 2h-forecast bug)
- Confidence: confirmed-by-reading vs. suspected-needs-verification

No code changes in this pass. After the report, the user picks which findings get the
test-first fix treatment used for the 2h-forecast bug (failing test → review → fix → green →
commit).

## 6. Testing

Not applicable to this spec directly — this is an audit/investigation, not a code change. Each
finding that gets promoted to a fix will get its own failing-test-first treatment as a
follow-up, per existing project convention (see `GlucoseCalculationsServiceTest`,
`HovorkaGlucosePredictionServiceTest`).
