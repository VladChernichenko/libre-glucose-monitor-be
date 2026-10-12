# Empirical twin port - Specification

**Status:** Ready for implementation
**Date:** 2026-10-12
**Author:** Vlad + Claude (from the claude.ai "Vlad's Glucose Twin" artifact, model v2.4)

## Objective

Over Oct 8-12 2026 a small, explainable forecasting model (the "empirical twin") was built and tuned
by hand against Vlad's own Libre readings and logged events. On logs made after its last retuning
(v2.3, Oct 12 14:40) it hit **19 of 25** +1 h/+2 h checks (76%), passing the 70% / 20-check target;
over all 161 historical checks it scores 75% hits, MAE 22.1 mg/dL.

This round ports that model into the backend as a **second prediction engine** that runs next to
the Hovorka engine, and adds a benchmark that scores both engines on the same anchors with the same
metric. It does **not** replace Hovorka, change the default engine, or touch the iOS app. Success =
the Java engine reproduces the reference forecasts exactly, and the benchmark prints a side-by-side
score for both engines on the bundled data.

## Inputs bundled with this spec

All in `specs/twin-port/` (move the JSON fixtures to `src/test/resources/twin/` during implementation):

| File | What it is |
|---|---|
| `twin-reference.js` | The oracle. Verbatim port of the artifact's model (~150 lines, Node, no deps). `node twin-reference.js --check` |
| `params.json` | Every model parameter with value, unit, label, provenance and the evidence behind it |
| `readings.json` | 342 Libre readings, Oct 8-12 2026, mg/dL, ISO times (+04:00) |
| `events.json` | 100 logged events: `ins` (Apidra U), `basal` (Tresiba U), `meal`/`fast` (g carbs, optional `fat`, `fruit`, `gi`), `walk` (with `end`), `alc` (drinks), `heat` (min, not modelled) |
| `golden-forecasts.json` | For each of 86 anchors: the reference forecast every 30 min to +240 and the CGM actual at +60/+120 |

**Privacy:** the fixtures are Vlad's real health data. Keep them out of any public repo; add them to
`.gitignore` or a private test-data location if this repo is ever shared.

## The model (read `twin-reference.js` for the exact arithmetic)

State is a single glucose value `g` stepped every 5 min for 240 min from the CGM value at the anchor.
Each step adds the increment of every logged effect over that 5-min window:

- **Rapid insulin** (`ins`): `-isf * U * Δ insAct(x)`; `insAct` is the exponential IOB curve
  (dia 240, peak 55). Doses ≤ 1 U are scaled by `oneU` (pen under-delivery on single presses; 1.0 now).
- **Meal** (`meal`): `+(isf / CR) * carbs * Δ carbAbs(x, k)`, `carbAbs = 1-(1+x/k)e^(-x/k)`.
  CR is `crAM` (7) for meals logged 05:00-11:00 local, else `crDay` (10). `k = kMeal` (35) or `kFruit` (18),
  times `(55/GI)^giPow` (giPow 0.5). Fatty meals (`fat`) split: `fatSlow` (30%) absorbs with `kSlow` (90).
- **Fast sugar** (`fast`): `+csf * g * Δ carbAbs(x, kFast·(65/GI)^giPow)` (csf 3.1, kFast 12).
- **Walking**: direct glucose use `walkDrop` (20 mg/dL/h) while walking, fading linearly over
  `walkDropTail` (1 h). Insulin-sensitivity boost `walkBoost` exists but is 1.0 (off): every multi-hour
  boost tested made past forecasts worse.
- **Alcohol** (`alc`): `-alcDrop * drinks`, spread from +2 h to +10 h.
- **Basal** is assumed to hold glucose flat (`drift` 0). Events count if logged at or before anchor + 20 min.

Evaluation: an **anchor** is every distinct logged minute of an `ins`/`meal`/`fast` event. A check is
the forecast vs the linearly interpolated CGM at +60 and +120 min (gaps > 50 min = no value). Hit =
`|pred − actual| ≤ max(20 mg/dL, 20% of actual)`.

## Requirements

1. (Must) New pure package `che.glucosemonitorbe.empirical` (no Spring, no DB), mirroring the
   `hovorka.learning` convention: `EmpiricalTwinParams` (record, defaults = `params.json`),
   `EmpiricalTwinEngine.forecast(anchorEpochMs, readings, events, params)` returning 5-min points
   to +240 min. Units at the boundary follow the existing code (mmol/L in `PredictionReplayEngine.Reading`);
   internally the engine works in mg/dL with the published constants, converting with 18.016.
2. (Must) Golden test: for all 86 anchors in `golden-forecasts.json` the Java forecast matches the
   reference within 0.5 mg/dL at every 30-min point. Use JUnit 5 + the existing test conventions.
3. (Must) Benchmark test (or a runnable `@Tag("benchmark")` test) that feeds the same fixtures through
   both engines - Hovorka via `PredictionReplayEngine` with default `TwinScales`, empirical via the new
   engine - over the same anchors and prints: checks, hits, hit %, MAE at +60 and +120 for each.
   Do not assert that either engine wins; assert only that both produce a score.
4. (Must) Map the app's event model onto the empirical event types. Where a field has no equivalent
   (`fruit`, `gi`, `fat`, walk start/end) add it as nullable, defaulting to the model's neutral value
   (no GI → ratio 1; no fat → no slow split). Document the mapping in this file.
5. (Should) Engine selection behind a per-user setting (`prediction_engine`: `HOVORKA` default,
   `EMPIRICAL`, `BOTH`), read by `GlucosePredictService`. `BOTH` returns the Hovorka path plus the
   empirical path as a second series. Flyway migration if a column is needed (follow CLAUDE.md
   schema rules).
6. (Should) E2E coverage with the `prediction-e2e-tests` skill for `POST /api/glucose-calculations/`
   when the empirical engine is selected: fasting flat line, insulin-only fall, fruit vs bread speed,
   fatty-meal late tail, walk dip.
7. (Could) Feed the empirical findings into Hovorka as priors for Vlad: fruit faster than meals, GI
   speed exponent 0.5, 30% slow share for fatty meals, morning CR 7 vs 10, walking direct uptake
   ~20 mg/dL/h with ~1 h tail (relevant to `ActivityModulation.gainIndep` in
   `specs/activity-integration.md`).

## Out of scope

iOS UI, live LibreLinkUp ingestion, automatic refitting of empirical params (the calibrator stays
Hovorka-only this round), the injection-site map, `heat` modelling.

## Known limits of the model (do not "fix" during the port)

- Basal is assumed perfect; dawn rise (03:00-06:00 on two nights) and an early-afternoon creep are
  unmodelled and show up as misses.
- Libre reads low during slow rises; the hit band absorbs some of that.
- Parameters were tuned on ~5 days of one person. They are a starting point, not population values.

## Acceptance

`./gradlew test` passes, golden test green, benchmark prints both engines' scores on the bundled data,
and `node specs/twin-port/twin-reference.js --check` still prints `OK`.
