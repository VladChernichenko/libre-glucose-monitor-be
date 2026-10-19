# Glucose Twin features: Specification

**Status:** Revised 2026-10-16 against the code and the reference engine. Read *Revision notes* and *Open decisions* before implementing
**Date:** 2026-10-15
**Author:** Vlad + Claude (from the claude.ai "Vlad's Glucose Twin" artifact and 8 days of live coaching, Oct 8–15 2026)
**Repo:** `glucose-monitor-be` (Spring Boot). The iOS app uses these APIs; UI notes are included per feature.

## Objective

For eight days, Vlad (T1D, MDI: Apidra + Tresiba 20 U at ~23:00, FreeStyle Libre, mg/dL) was coached in real time by Claude.
Every dose, meal, walk and reading went into a "twin" page that:

- forecast glucose 4 h ahead;
- scored itself honestly;
- tracked injection sites;
- turned every situation into a concrete instruction: how many units, when to inject, when to check again.

This spec moves those features into the app. The forecasting engine is already specified in
`specs/empirical-twin-port.md` (`EmpiricalTwinEngine`), so this document covers everything *around* it:

- logging
- dose advice
- **meal plans from a photo** (dose, pause, alarms, explanation; F12)
- alarms
- photo carb estimates
- site rotation
- scoring
- parameters
- pattern reports

**Prerequisite:** round 1 (`empirical-twin-port.md`) is merged, i.e. `EmpiricalTwinEngine.forecast(...)` exists and its golden test passes.
If it is not merged yet, do round 1 first. (As of 2026-10-16 round 1 is implemented and green but not committed.)

## Safety principles (apply to every feature)

1. The app **advises**; it never doses. Every dose is entered by the user after injecting.
2. Every threshold below is a **per-user setting** with the listed value as Vlad's default. None of them are hard-coded.
3. When rules conflict, the safer outcome wins: less insulin, more checks.
4. Each piece of advice shows its reasoning in one line, e.g. "18 g ÷ 10 = 1.8 U, minus 1.1 U still active → 1 U".
5. Show "Not medical advice. Confirm settings with your endocrinologist." once per session on the advisor screen.
6. Health data is private. Keep fixtures out of public repos; never log tokens or credentials.

## Revision notes (2026-10-16)

The first draft's golden cases were checked against its own rules and against the round-1 reference
engine (`specs/twin-port/twin-reference.js`, v2.4). Where they disagreed, the rule was kept and the case was recomputed,
except where noted. Engine values below are the reference engine's own output. **Review every item marked *(proposed)*.**

| # | Change | Why |
|---|---|---|
| R1 | F2 acceptance on the round-1 fixtures is **22/25**, not 42/56 | The fixtures end Oct 12 22:30 and hold only 25 checks after 14:40. 42/56 needs the Oct 13–15 data (D6). Round 1's "19/25" was the v2.3 score; v2.4 scores 22/25 on the same checks |
| R2 | F1.2 vs F2.1: editing an event re-runs only the snapshots anchored on it, with their own version and parameters | F1.2 said "re-run", F2.1 said "never recomputed" |
| R3 | A1 IOB is **0.7 U**, not 1.1 | The engine's IOB curve gives 0.72 U at 16:58 for 2 U @14:52 + 1 U @15:09. The answer is still 1 U |
| R4 | A3: last dose is **15:09** (A1 lists it), IOB **1.9 U**, recheck **16:39** | The draft said 14:52 / 2.0 U / 16:22 |
| R5 | Timing table row 2 also matches a falling trend (< −1) and a fat-flagged meal *(proposed)* | Without it A1 (155, falling, IOB 0.7) lands on "inject 20–30 min before" and P5 on "10–15 min before"; both cases came from real coaching at the first bite |
| R6 | Planned exercise cuts the dose when it starts within **4 h** (`dia`), not 2 h *(proposed)* | P6's run is 3 h 35 min after the meal. A 2 h window gives 5 U, which the engine puts at ~105 by the run; 4 h gives the case's 3 U. Safety principle 3 favours the cut |
| R7 | One peak rule: check at the predicted peak if it is > 180 (F5 said > 200 at +1 h; F12 said > 180 at the peak) | Contradiction between F5 and F12 |
| R8 | "+2 h" means 2 h after **eating** (`eatAt`); `eatAt` = `injectAt` + 15 min for `PRE_10_15`, + 25 min for `PRE_20_30` | Undefined before; P2's 12:05 was +2 h from injection |
| R9 | Expected range at a check = engine forecast at that time ± 10, rounded to 5 mg/dL. Checks past +240 min have a rule but no range | Undefined before; P5's 03:00 check is past the engine horizon |
| R10 | New F5 trigger: planned exercise → check 15 min before start | P6's 18:15 check had no rule behind it |
| R11 | 03:00 check after a fat-flagged meal eaten after 17:00, or after a run that day; the Tresiba reminder is daily | P5 is at 20:00 and the bedtime flag only starts at 21:30. The 22:45 check is dropped: no rule produces it |
| R12 | P1–P6 times and ranges recomputed from the rules and the engine | See F12.7. P1 and P3 now differ sharply from what happened on the day (D3) |
| R13 | Rounding: half rounds up, except when the trend is < −1 | Only the falling case was specified |

## Open decisions (recommended default in **bold**; confirm before building the affected feature)

| # | Decision | Affects | Options |
|---|---|---|---|
| D1 | Where twin events live | F1, F2, F7 | **Extend `notes` and `/api/notes`** (new nullable columns, new `type` values) · a new `twin_events` table and `/api/twin/events`. A second store would split COB/IOB, which the code unified on purpose |
| D2 | Units and parameters | F3, F4, F8 | The app's settings are mmol/L, and `user_settings.carb_ratio` is a glucose-rise coefficient, not g/U. **A new per-user, versioned empirical parameter set in mg/dL and g/U (F8), holding every F3/F4 threshold**; API in mg/dL with mmol/L added for the app |
| D3 | Trend in the forecast | F3 what-if, F12 forecast and checks | The engine starts flat and ignores trend, while F3 projects BG with it. In P1 the explanation says "falling ~1.2/min" while the engine forecasts a rise to 166. **Add a decaying trend term in the advisor only (the golden round-1 test stays as is) and recompute F12.7** · keep the engine as is and show the trend only in text |
| D4 | Push delivery | F5, F12 alarms | APNs is a stub (`GlucoseAlertService.deliverAlert` only logs). **Wire APNs as its own prerequisite step before F5** · in-app polling only |
| D5 | Vision model for F6 | F6, F12 | The app uses Qwen with an Ollama fallback (`NutritionVisionService`). **Keep the existing gateway and extend its prompt** · add Claude (photos then go to a new external API) |
| D6 | Oct 13–15 data | F2 acceptance, F10, F11 | Not in the repo. **Export from the artifact into the git-ignored `src/test/resources/twin/`**, then add the 42/56 acceptance back |
| D7 | P4 history | F12.7 P4 range | IOB 0.6 U at 14:50 needs the doses that produced it. **List them** |

## Glossary

| Term | Meaning |
|---|---|
| BG | Glucose value (Libre or fingerstick), mg/dL |
| IOB | Insulin on board: units of rapid insulin still to act, from the engine's `iobFrac` curve (dia 240, peak 55) |
| COB | Carbs on board: grams not yet absorbed, from the engine's absorption curves |
| ISF | Insulin sensitivity factor, mg/dL per U (27; mornings behave closer to 36) |
| CR | Carb ratio, g per U: `crAM` 7 for 05:00–11:00, `crDay` 10 otherwise |
| Trend | Slope over the last 15 min of readings, mg/dL per min |

---

## F1. Event logging

### F1.1 Event types

| Type | Fields | Notes |
|---|---|---|
| `ins` | units, time, site? | Rapid insulin (Apidra) |
| `basal` | units, time, site? | Tresiba |
| `meal` | carbs g, time, gi?, fat flag, fruit flag, photoId?, note | Normal or fatty meal; fruit uses the fruit curve |
| `fast` | carbs g, time, gi? | Juice, gel, cola, glucose: rescue or fast sugar |
| `walk` / `exercise` | start, end, intensity (`walk`/`run`/`other`) | A run is also logged as exercise with intensity `run` |
| `alc` | drinks, time | Standard drinks |
| `heat` | start, minutes | Hot shower or sauna; speeds absorption (logged, not modelled yet) |
| `caffeine` | time, note | Coffee; logged for pattern analysis |
| `fingerstick` | BG, time | Meter value, used for sensor offset (F10) |
| `note` | text, time | Free-text timeline note (F9) |

### F1.2 Quick entry

- A bare `Nu` (e.g. `3u`) logs `ins` of N U **now**.
- `20u T` logs `basal` of 20 U now.
- Times default to now and are editable afterwards. Editing an event's time or amount re-runs the snapshots anchored on that event,
  using each snapshot's own model version and parameters (F2.1). This corrects data entry; it is not a re-fit.
- Duplicate detection uses the **full timestamp (date + time)**, never time-of-day alone. In the artifact a time-only check silently dropped a Tresiba dose logged at the same clock time on another day.

### F1.3 API

- `POST /api/twin/events`, `PATCH /api/twin/events/{id}`, `DELETE /api/twin/events/{id}`, `GET /api/twin/events?from&to`
- Nullable `gi`, `fat`, `fruit`, `end_time`, `intensity`, `site_zone`, following the CLAUDE.md schema rules: columns on an existing table
  go into the migration that owns it (for `notes`, `V1__baseline_schema.sql`, which needs `flyway repair` on a populated DB); only new tables
  get a new `V{n}__` file. Endpoints depend on D1. Round 1 already added `gi`, `fattyMeal` and `fruit` to `notes.nutrition_profile`.

**Acceptance**
- Logging `3u` at 10:56 and `3u` at 10:56 the next day produces two events.
- Logging the same type, amount and timestamp twice within 60 s asks "Duplicate?" instead of saving.

---

## F2. Forecast at each log and honest scoring

### F2.1 Frozen forecast snapshots

When an `ins`, `meal` or `fast` event is saved:

- Compute the engine forecast from that moment, using events logged up to +20 min (a dose plus the meal it covers count as one decision).
- Store it as a **snapshot** with the model version and parameter hash.
- Snapshots are never recomputed when parameters change later. That is what makes the score prospective.
  The only exception is a correction to the anchoring event's own data (F1.2).

### F2.2 Scoring

- A check compares the snapshot with the interpolated CGM value at **+60 and +120 min**. Gaps over 50 min mean no value.
- **Hit** = `|forecast − actual| ≤ max(20, 0.2 × actual)`.
- **Calibration cut (CAL):** each model version records the time it was set. The headline score counts only checks whose anchor is **after** that version's CAL. Earlier checks show as "fitted on" and are greyed out.
- Headline:
  - "Confidence 75% · 42 of 56 checks since v2.3"
  - meter with a 70% target line
  - a minimum of 20 checks before the score is called reliable
- Also show MAE at +60 and +120.

### F2.3 UI (iOS)

- An anchor picker (← list →) with a chart for each anchor:
  - forecast line
  - ±20% band
  - actual CGM
  - dose, carb and basal markers
- Stats row: value at the log, then +1 h, +2 h and +4 h shown as forecast / actual, with a hit/miss pill.
- An event table, where tapping a row opens its forecast.

### F2.4 API

- `GET /api/twin/score` returns `{modelVersion, cal, checks, hits, pct, mae60, mae120, reliable}`
- `GET /api/twin/anchors/{eventId}` returns the snapshot and actuals.

**Acceptance**
- With CAL = 2026-10-12T14:40+04:00 and the v2.4 parameters, the round-1 fixtures score **22/25 (88%)**, reliable.
- Once the Oct 13–15 data is exported (D6): 42/56 (75%) over the extended data.
- Changing a parameter does not change any existing snapshot or the headline.

---

## F3. Dose advisor

A screen and an endpoint that answer "how many units, and when?" for a meal, a correction, or both.

### F3.1 Inputs

- current BG and trend
- IOB, COB
- planned carbs (+ gi, fat, fruit)
- time of day
- recent exercise, alcohol and heat
- bedtime flag (auto after 21:30)

### F3.2 Algorithm (in order)

1. **Meal part** = carbs ÷ CR(time of meal). CR is 7 for 05:00–11:00, otherwise 10.
2. **Projected BG** = BG + trend × 30 min. The trend is clamped to ±3 mg/dL/min.
3. **Correction part**, only if the projected BG is above the correction threshold:
   - daytime threshold: 150
   - bedtime threshold: 170
   - after exercise (≤ 6 h): 180

   Correction part = (projected BG − target) ÷ ISF, with **target 100** (user range 90–100). Use ISF 36 for 05:00–11:00 when correcting without food.
4. **Subtract IOB** (full value) from the sum. COB that the earlier insulin was meant to cover is already counted in IOB, so do not add it back.
5. **Adjustments:**
   - exercise in the last 6 h, or planned to start within the next 4 h (`dia`): −30% of the meal part, no correction part *(proposed, R6)*
   - alcohol in the last 10 h: no correction below 200
   - hot shower or sauna in the next hour: show a warning ("acts faster")
6. **Anti-stacking guard:** if a rapid dose was taken < 90 min ago, **block the correction part**. Show "wait, recheck at <last dose + 90 min>" unless BG > 250 or the trend is > +2 mg/dL/min.
7. **Rounding** to the pen step (1 U; setting for 0.5). Round to nearest, an exact half rounding **up**; when the trend is falling (< −1 mg/dL/min), an exact half rounds **down**. Never return a negative dose.
8. **Timing** (pre-bolus table; the first matching row wins). BG is the current value, not the projected one:

   | Situation | Timing |
   |---|---|
   | BG < 90, or BG < 120 and falling (trend < −1) | Eat first, inject after |
   | Insulin on board ≥ 1 U, trend < −1, activity in the last 6 h, or a fat-flagged meal *(proposed, R5)* | Inject at the first bite |
   | BG 90–150, steady | Inject 10–15 min before eating |
   | BG > 150 | Inject 20–30 min before eating |

9. **What-if:** run the engine for the proposed dose and ±1 U. Return +30/60/90/120/180/240 for each, plus the lowest point. If the proposed dose's lowest point is < 80, lower the dose by 1 U and say why.

### F3.3 Output

```json
{ "units": 1, "timing": "AT_FIRST_BITE",
  "explain": "18 g ÷ 10 = 1.8 U; 1.1 U still active; falling ~1.2/min → 1 U",
  "whatIf": [{ "units": 0, "curve": [...], "min": 142 }, { "units": 1, "curve": [...], "min": 128 }, { "units": 2, "curve": [...], "min": 101 }],
  "checks": ["18:00"], "warnings": [] }
```

### F3.4 Golden advisor cases (from real days; tolerance ±0 U on `units`, exact on `timing`)

Where doses are listed, IOB is computed by the engine from them; otherwise the IOB shown is an input.

| # | Situation | Expected |
|---|---|---|
| A1 | Oct 15 16:58. BG 155, trend −1.2/min, doses 2 U @14:52 + 1 U @15:09 (IOB 0.7 U). Apple 18 g, GI 38, fruit | **1 U, AT_FIRST_BITE** (falling). Projected 119 < 150, so no correction. 1.8 − 0.7 = 1.1 → 1 |
| A2 | Oct 15 14:50. BG 135, steady, IOB 0.6 U. Chocolate hazelnuts 7 g, fat | **0 U**. 0.7 − 0.6 = 0.1 → 0 |
| A3 | Oct 15 16:00. BG 199, steady, doses 2 U @14:52 + 1 U @15:09 (last dose 51 min ago, IOB 1.9 U) | **0 U**, anti-stacking. Recheck at 16:39 or later |
| A4 | Oct 12 10:46. BG 154, activity 09:35–09:55. Oats 48 g at 10:50 | **5 U, AT_FIRST_BITE**. 48 ÷ 7 = 6.9, −30% = 4.8; no correction after activity |
| A5 | Oct 13 23:48. Bedtime after a run (18:30). BG 165, steady, IOB 0.3 U | **0 U**. Post-exercise threshold is 180. The real 1 U given here led to a 79 at 04:48 |
| A6 | Oct 15 10:05. BG ~122, steady, IOB 0. Oats 60 g + milk 250 g = 48 g, morning | **7 U, PRE_10_15** (48 ÷ 7 = 6.9). Real: 4 U was taken, leading to a 250 peak and a 3 U correction |

---

## F4. Low and high guidance

### Lows

| Condition | Guidance |
|---|---|
| BG < 100 **and** trend < −1 with IOB > 0.5 U | "Take 10–15 g fast sugar now" |
| BG < 80 | "Take 15 g fast sugar now" |
| BG < 70 | Urgent: "Take 15–20 g fast sugar, fingerstick, recheck in 15 min" |

- Every low prompt schedules a **15 min recheck** (F5).
- If still under 80 or falling at the recheck, repeat.
- **Rebound warning:** if more than 30 g of rescue carbs are logged within 45 min, show "rebound likely. Don't correct for 2 h unless above 250".
- **Activity rule:** if a walk or run starts within 2 h of a dose ≥ 2 U, prompt for 15–20 g of carbs first.

### Highs

| Condition | Guidance |
|---|---|
| BG > 250 and rising | Fingerstick, pen check (air shot, new needle), new site; correction via F3 |
| BG flat > 180 for 2 h with IOB < 0.5 | Suggest a correction via F3 and flag a possible basal or site issue |

---

## F5. Follow-up checks (alarms)

Server-side scheduled checks, delivered as push notifications. Each has a title, an expectation and a rule:

> "17:00 · expect ~100–110 · under 90 and falling → 10–15 g"

| Trigger | Check at | Notes |
|---|---|---|
| Correction dose | +60 min, +2 h | |
| Meal with dose | +2 h after eating | Also at the predicted peak time if the peak is > 180 |
| Low treatment | +15 min | |
| IOB ≥ 2 U while walking, or in the 2 h after heat | +45 min | |
| Bedtime | Tresiba reminder at 23:00, daily | 03:00 check after a fat-flagged meal eaten after 17:00, or after a run that day |
| Planned exercise | 15 min before it starts | Rule: eat 15–20 g if IOB ≥ 1 U or under 150 |

- **Supersede rule:** a new reading inside a check's window (±15 min) marks it done. A newer dose or meal cancels checks whose expectation is no longer valid.
- Never leave stale alarms.
- Expected range = engine forecast at the check time ± 10, rounded to 5 mg/dL. A check past the engine's +240 min horizon has a rule but no range.
- `GET/POST/DELETE /api/twin/checks`. The iOS app shows the next check on the home screen.

**Acceptance**
- A reading at 16:55 cancels the 17:00 check.
- A 1 U dose at 16:59 replaces the 17:00 "2 U landing" check with an 18:00 check.

---

## F6. Food photo → carbs

- `POST /api/twin/food-estimate` takes a photo and an optional weight in grams and note.
- It calls a vision LLM (Claude) with a fixed prompt and returns:
  `{items:[{name, grams, carbsG, gi, fat, fruit}], totalCarbsG, confidence, assumptions}`
- If a kitchen scale is visible in the photo, read the grams. If a nutrition label is visible, use it.
  If label numbers contradict each other (e.g. kcal versus carbs), say so and pick a range midpoint.
- Show **GL** = carbs × GI ÷ 100 with a band:
  - ≤ 10: low
  - 11–19: medium
  - ≥ 20: high

  GL is display only. Dosing stays on carbs.
- The estimate feeds the meal plan (F12), which is the normal path. A plain "log meal" (F1) is also available.
- Reference estimates for prompt tests:

  | Food | Expected estimate |
  |---|---|
  | Lacasa dark-chocolate hazelnuts 19 g | ~7 g, fat |
  | Ice cream 40 g | ~9 g, GI 51, fat |
  | Golden apple ~170 g | ~18 g, GI 38, fruit |
  | Oats 60 g + milk 250 g | ~48 g, GI 55 |
  | Fresh figs 160 g | ~25 g, GI 61, fruit |

---

## F7. Injection site rotation

### Zones

| Code | Site | View |
|---|---|---|
| `abd_ur` | Belly, upper right | Front |
| `abd_ul` | Belly, upper left | Front |
| `abd_lr` | Belly, lower right | Front |
| `abd_ll` | Belly, lower left | Front |
| `thigh_r` | Thigh, right front | Front |
| `thigh_l` | Thigh, left front | Front |
| `arm_r` | Arm, right (back) | Back |
| `arm_l` | Arm, left (back) | Back |
| `flank_r` | Flank, right | Back |
| `flank_l` | Flank, left | Back |
| `butt_r` | Buttock, right upper-outer | Back |
| `butt_l` | Buttock, left upper-outer | Back |

### Behaviour

- After each `ins` or `basal` event, ask "Where did you inject?" with a front/back body map.
  "Don't remember" is a valid answer (`unknown`).
- **Colours:**
  - used < 24 h: red
  - used 24–72 h: amber
  - suggested next: dashed outline
- **Suggestion:** the zone unused for longest, excluding buttocks for rapid insulin (slow absorption).
- **Warning:** the same zone 3 times in a row shows "rotate: repeated use causes lumps that absorb unpredictably". This happened with `abd_ur` on Oct 13.
- Store the zone on the event (`site_zone`).
- Absorption analysis (feeds F10): compare forecast error by zone and show zones whose doses act late.

---

## F8. Parameters with provenance

- One table of parameters (the `params.json` set from round 1). Each row has:
  - value, unit, label
  - **evidence tag**: `good` (several confirmations), `est` (few events), `default` (label or literature)
  - a one-paragraph "how we got this"
- Every change creates a new **model version** with a time (the new CAL), the author and a reason.
- "Restore calibrated values" resets to the last version.
- User-editable on iOS, with step sizes (e.g. ISF ±1, CR ±0.5).
- Planned refits, as stubs only (do not implement in this round): morning ISF ≈ 36, post-run long tail, protein/fat late rise, heat speed-up.

---

## F9. Timeline journal (calibration notes)

- Reverse-chronological notes, each tied to a time and optionally an event. Example:
  > "16:00 — 199 →. Snacks absorbed faster than 3 U acted; afternoon creep 4th day; no stacking."
- Created by the user, or by the advisor when it gives advice (store the explanation string from F3 and F4).
- Model-version notes are pinned and highlighted.
- Searchable. Exportable as Markdown or PDF for doctor visits (with F10).

---

## F10. Patterns and endocrinologist report

Nightly job that detects these patterns and adds them to a weekly report:

| Pattern | Rule | Seen |
|---|---|---|
| Afternoon creep | Rise ≥ 40 between 13:00 and 17:00 with IOB < 0.5 U and no carbs in the prior 2 h | Oct 11, 12, 13 (and possibly 15) |
| Late rise after a fat/protein dinner | Rise ≥ 50 between +3 h and +6 h after a meal flagged `fat` with ≤ 20 g carbs | Oct 11, 12, 15 nights |
| Dawn rise | Rise ≥ 30 between 03:00 and 07:30 with no events | |
| Delayed post-exercise low | Fall ≥ 15/h for ≥ 3 h starting 3–10 h after a run or a walk ≥ 90 min | Oct 13–14 night, bottom 79 |
| Morning ISF stronger | Fasting corrections 05:00–11:00 with observed drop per U > 1.25 × ISF | ~36 vs 27 |
| Late-acting doses | Forecast error at +60 > +40 and at +120 < −30 for the same dose | Oct 13 |
| Overdosing habit | Actual dose minus advised dose ≥ 1 U in ≥ 30% of advised events | |

Also include:

- **Sensor offset:** fingerstick versus Libre, split by trend (steady/high: Libre about 11% high; slow rise: about 7% low). Show the offset table; do not auto-correct values.
- **Report:** time in range (70–180), lows (< 70, < 54), each detected pattern with dates and a mini-chart, forecast score, site rotation summary.
- `GET /api/twin/report?from&to` returns JSON. iOS renders it and offers "Share PDF".

---

## F12. Meal plan from a photo

The main user flow. It ties F6, F3, F4, F5 and F9 together. The user photographs food, and the app returns **one plan**
covering what to inject, when, when to eat, and when to check, with the reasoning shown. Accepting the plan sets the alarms.
This is how the coaching worked in chat ("send a photo → dose, pause, alarms").

### F12.1 Flow

1. **Capture.** The user takes a photo. Optional extras:
   - weight in grams (or a visible scale)
   - "eating now / in X min"
   - a voice or text note ("plus 200 g milk")
2. **Estimate (F6).** Items, carbs, GI, fat and fruit flags, confidence and assumptions. Low confidence (< 0.6) asks one question first, e.g. "How many grams?" or "Sweet or sour cherries?".
3. **Context snapshot.** Latest BG and trend (a reading older than 15 min shows "scan first" and blocks the plan), plus IOB, COB, last dose time, exercise, alcohol, heat, time of day and bedtime flag.
4. **Dose (F3).** Units, the explanation string, and what-if curves for the dose ±1 U.
5. **Timing.** From the F3 pre-bolus table, as clock times:
   - `injectAt`, then `eatAt = injectAt + pause`, with pause 15 min for `PRE_10_15` and 25 min for `PRE_20_30` (the card shows the range)
   - or "eat first, inject after" with `injectAt = eatAt + 0–15 min`
6. **Checks (F5).** Each with a clock time, an expected range from the engine forecast for the planned dose, and an action rule.
   Defaults: +2 h after `eatAt`, the predicted peak time (if > 180), and extras from F4 and F5 (e.g. IOB ≥ 2 U before a walk, planned exercise).
   A fat-flagged meal after 17:00 also gets the 03:00 check (F5).
7. **Warnings.** Activity in the next 2 h, a hot shower, stacking, a fat/protein late rise ("expect a rise 3–5 h later; don't correct before +3 h unless > 250"), a rebound risk.
8. **Present** the plan card. The user can **Accept**, **Adjust** (change units, carbs, or eat time; the plan recomputes live) or **Discard**.

### F12.2 Plan object

```json
{
  "planId": "…", "createdAt": "2026-10-15T16:58+04:00", "status": "PROPOSED",
  "food": { "items": [{ "name": "golden apple", "grams": 170, "carbsG": 18, "gi": 38, "fruit": true }],
            "totalCarbsG": 18, "confidence": 0.8, "assumptions": ["weight estimated from photo"] },
  "context": { "bg": 155, "trend": -1.2, "iob": 0.7, "cob": 3, "lastDoseAt": "15:09", "flags": [] },
  "dose": { "units": 1, "explain": "18 g ÷ 10 = 1.8 U; 0.7 U still active; falling ~1.2/min → 1 U" },
  "timing": { "mode": "AT_FIRST_BITE", "injectAt": "17:00", "eatAt": "17:00",
              "explain": "You're falling with insulin still active, so inject as you start eating, not before" },
  "forecast": { "curve": [[0,155],[30,160],[60,166],[90,165],[120,163],[150,160],[180,158],[210,157],[240,157]], "min": 153, "peakAt": null },
  "whatIf": [{ "units": 0, "min": 153, "at240": 184 }, { "units": 2, "min": 130, "at240": 130 }],
  "checks": [{ "at": "19:00", "expect": "155–175", "rule": "under 100 and falling → 10–15 g fast sugar" }],
  "warnings": [],
  "summary": "1 U as you start eating. Expect 155–175 by 19:00. Check at 19:00."
}
```

The numbers are the reference engine's for P1. It ignores the falling trend, which is why the forecast rises (D3).

### F12.3 Card layout (iOS)

- **Headline:** "**1 U · inject as you start eating**". If there is a pause: "**5 U now · eat at 10:20**", with a countdown timer to eating.
- **Why:** 2–4 short lines from the `explain` strings: carbs, the ratio used, insulin still active, trend, rule adjustments.
- **Forecast:** a mini chart of the planned dose against the ±1 U alternatives, and the lowest point.
- **Checks:** a timeline of check times with their expected ranges.
- **Warnings:** shown as chips.
- **Buttons:** Accept, Adjust, Discard.

### F12.4 Lifecycle

| Status | What happens |
|---|---|
| `PROPOSED` | Nothing scheduled. Expires after 30 min (BG context is stale) and asks to re-plan |
| `ACCEPTED` | Creates F5 checks and a "time to eat" alarm at `eatAt` if there is a pause, plus an "inject now" alarm if `injectAt` is in the future. Writes a journal note (F9) with the summary |
| `IN_PROGRESS` | The first matching event is logged (dose or meal). A dose of any size within ±30 min of `injectAt` is linked to the plan, and the meal is logged from `food` when the user taps "Eating now" (or at `eatAt` with confirmation) |
| `DONE` | After the last check |
| `CANCELLED` | Discard, or superseded by a newer plan. Removes its pending alarms |

### F12.5 Deviations and re-planning

The user often doses differently from the plan (e.g. +1 U).

- **Dose ≠ planned:**
  - recompute the forecast and checks with the actual dose
  - replace the plan's alarms
  - show one line, e.g. "You took 3 U (plan 2 U): extra ~27 mg/dL drop. New check 17:00 (expect ~100–110). Keep fast sugar nearby."
  - if the new lowest point is < 80, add a check 15 min before the predicted low and a pre-emptive "eat 10–15 g if under 100" rule
- **More food added** (a second photo or text within 60 min): merge it into the same plan. The extra dose comes from F3 with the plan's dose counted as IOB, and checks are recomputed.
- **New reading** off the expected range by more than max(20, 20%) at a check: the check notification links to a **re-plan** using the current context, i.e. F3 correction or F4 low treatment.
- **Activity or heat started** (logged) while the plan is active: re-run with exercise and heat flags and warn if the lowest point is < 90.

### F12.6 API

- `POST /api/twin/meal-plans` (multipart: photo, grams?, eatAt?, note?) returns a `PROPOSED` plan
- `POST /api/twin/meal-plans/{id}/adjust` with `{units?, carbsG?, eatAt?}` returns a recomputed plan
- `POST /api/twin/meal-plans/{id}/accept`, `/discard`
- `GET /api/twin/meal-plans/active`
- Plans persist in a `meal_plan` table (JSON columns for food, context, forecast and checks) with an FK to the linked events. Use a Flyway migration.

### F12.7 Golden plan cases

| # | Photo / context | Expected plan |
|---|---|---|
| P1 | Apple ~170 g at 16:58. BG 155, trend −1.2, doses 2 U @14:52 + 1 U @15:09 (IOB 0.7) | 1 U, AT_FIRST_BITE at 17:00. Check 19:00 expecting 155–175 (engine 163). Peak 167, so no peak check |
| P2 | Oats 60 g + 250 g milk at 10:05. BG 122, steady, IOB 0 | 7 U now, eat at 10:20 (PRE_10_15). Check 12:20 expecting 115–135 (engine 124). Peak 129, so no peak check; lowest 111 |
| P3 | Same as P2, user logs 4 U | Deviation: "4 U vs 7 U planned: expect ~190 by 12:20, still rising". Checks 12:20 expecting 180–200 (engine 191) and peak 14:05 expecting 185–205 (engine 197), rule "above 200 and rising → correction via F3". The real day peaked at 250; the engine underpredicts it |
| P4 | Chocolate hazelnuts 19 g at 14:50. BG 135, steady, IOB 0.6 | 0 U ("too small to dose with insulin still active"). Check 16:50. Expected range once the dose history is known (D7) |
| P5 | Pork dinner, high fat/protein, 10 g carbs, fat-flagged, at 20:00. BG 120, steady, IOB 0 | 1 U at the first bite (fat, R5). Warning: late rise 23:00–02:00. Checks 22:00 expecting 110–130 (engine 119), Tresiba reminder 23:00, 03:00 check (past the horizon: no range) |
| P6 | Soup + bread 45 g at 14:55, run planned at 18:30. BG 120, steady, IOB 0 (BG was missing from the draft; 120 assumed) | 3 U (4.5 −30% = 3.15, R6), PRE_10_15: inject 14:55, eat 15:10. Checks 17:10 expecting 145–165 (engine 157) and pre-run 18:15 expecting 150–170 (engine 158) with the rule "eat 15–20 g if IOB ≥ 1 U or under 150" |

Tolerance: units exact, times ±5 min, expected ranges should overlap the engine forecast ±20.

---

## F11. Tests

- **Unit:** advisor cases A1–A6; meal-plan cases P1–P6 (with F6 stubbed to the listed carbs); F12 lifecycle and deviation re-plans; F5 supersede rules; F7 suggestion and warning; F10 detectors on the round-1 fixtures plus the Oct 13–15 data (export from the artifact).
- **E2E:** use the `prediction-e2e-tests` skill for the advisor and score endpoints.
- **Golden:** the round-1 golden test keeps passing; `node specs/twin-port/twin-reference.js --check` prints `OK`.

## Out of scope this round

- Automatic insulin delivery or any pump control.
- Live LibreLinkUp ingestion changes. Use whatever reading source the app already has.
- Automatic parameter refitting (the F8 refits stay manual).
- Modelling heat or caffeine (logged only).

## Suggested order

1. F1 event model and migration
2. F2 snapshots and score
3. F3 advisor with golden cases
4. F5 checks
5. F4 low/high rules
6. F6 photo estimates
7. **F12 meal plan from photo (the main user flow)**
8. F7 sites
9. F8 parameter versions
10. F9 journal
11. F10 report

Commit after each feature, with tests green.
