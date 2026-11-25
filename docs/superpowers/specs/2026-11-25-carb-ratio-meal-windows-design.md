# Carb ratio per meal window - design

Date: 2026-11-25. Scope: backend (`glucose-monitor-be`) and the iPhone app (`glucose-monitor-iphone`).

## Intent

The insulin-to-carb ratio (grams of carbs per unit, `user_settings.grams_per_unit`) is one number for
the whole day. Real ratios change with time of day - typically fewer grams per unit at breakfast -
so one number either over-doses some meals or under-doses others. ISF already varies by meal window;
the ratio should too.

The user also wants the app to work the per-window ratios out from their own meals and suggest
them, the way it already suggests per-window ISF, rather than having to find them by trial and
error.

## Goals

- Four optional ratios, one per meal window, using the ISF windows: Breakfast 05:00-11:00,
  Lunch 11:00-16:00, Dinner 16:00-22:00, Night 22:00-05:00. An unset window falls back to the
  single `grams_per_unit`.
- Every consumer of the ratio uses the ratio of the relevant window: the bolus calculator, the
  Hovorka forecast, the classic COB forecast, the empirical twin forecast, sick-mode scaling.
- The app learns a ratio per window from clean meals in the last 14 days and proposes it on the
  existing morning suggestion card, next to the ISF proposals, with one Apply.
- ISF learning and ratio learning use different events (corrections vs meals), so they cannot drift
  against each other.

## Non-goals

- Clearing a window override back to "use the default" from the app. Today `null` on save means
  "leave unchanged" for the ISF windows too; the ratio windows behave the same.
- Windows other than the four ISF windows, or user-defined window boundaries.
- Re-deriving the AZT1D seed users' ratios (`scripts/seed_azt1d_profiles.sql`).
- The web app (`glucose-monitor-fe`) and the Apple Watch app.
- Changing how the digital twin fits its scales.

## Anti-goals

- A ratio reaching dosing or forecasts without the user applying it. Learned values are proposals
  only, exactly like learned ISF today.
- A forecast that contradicts the dose: a meal dosed exactly by its window's ratio must forecast
  flat (no false low or high from the meal/insulin pair).
- ISF and ratio estimates feeding each other in a loop.
- A learner biased toward "your ratio is fine" because it throws away the meals that went wrong
  (meals that needed a follow-up correction or rescue carbs).
- A suggestion that moves a ratio by a large jump in one step.
- Sick days teaching the ratio.

## Constraints

- Schema rule (project `CLAUDE.md`): new columns on an existing table are added by editing the
  migration that owns it (`V1__baseline_schema.sql` for `user_settings`), plus the matching
  `ADD COLUMN IF NOT EXISTS` block in `scripts/upgrade-existing-db.sql` for deployed databases. The
  dev DB then needs a `flyway repair` (see the dev-DB notes). A brand-new table gets a new
  `V23__...sql`.
- Branches: the backend checkout is on `meal-gut-chains`, already merged to `origin/main`. The iPhone
  checkout's `main` is 12 commits behind `origin/main` and holds someone's uncommitted work (late
  protein rise) in `BackendAPI.swift` and `ContentView.swift`, the same files this feature edits.
  Build both repos on feature branches cut from `origin/main` in worktrees, and do not touch that
  uncommitted work.
- Backend builds need Java 21 and Docker (Testcontainers).
- Run focused tests while working; run the full backend suite and the iOS build/tests once before
  merge.

## Approaches considered

After a meal, how high glucose ends up depends on both ISF and the ratio, so the same meal can
"teach" either number. The question was how to keep the two learners apart.

- **A. Split by event type - chosen.** ISF learns only from corrections (insulin with no carbs
  nearby); the ratio learns only from meals, using that window's current ISF. ISF errors barely
  move the ratio when a meal was dosed about right (in the worked example below, a 25% wrong ISF
  changes the ratio by ~2%). It is simple, explainable, and matches the digital twin, which fits ISF
  on corrections only since 2026-11-15. Cost: windows with few corrections get fewer ISF proposals.
- **B. Joint fit per window.** Fit ISF and ratio together from all events. Uses every event, but with
  a handful of meals per window in 14 days it is unstable, needs a pull toward current values, and
  is hard to explain. Not chosen.
- **Dropped:** per-window meal scales in the digital twin (needs far more data, and would tie dose
  advice to the twin, which by the user's earlier choice only touches forecasts); keeping the ISF
  learner unchanged and feeding its output to the ratio learner (circular).

## Decisions made with the user

- Per-window ratios, and the app learns and suggests them (not manual fields only).
- One suggestion card for ISF and ratio together, with a single Apply.
- Approach A: split learning by event type.

## Design

### 1. Settings

`user_settings` gains four nullable columns, each with the same 3-30 CHECK as `grams_per_unit`:
`grams_per_unit_breakfast`, `grams_per_unit_lunch`, `grams_per_unit_dinner`,
`grams_per_unit_night`. Entity `UserSettings` and `UserSettingsDTO` gain `gramsPerUnitBreakfast`,
`gramsPerUnitLunch`, `gramsPerUnitDinner`, `gramsPerUnitNight`. `UserSettingsService` saves each
only when non-null (as for the ISF windows) and returns them on read.

`UserSettingsDTO`:
- New `getWindowGramsPerUnit(LocalDateTime time)`: the override for `time`'s meal window, else the
  single `gramsPerUnit`. Mirrors `getWindowIsf`.
- `getEffectiveGramsPerUnit(time)` becomes `getWindowGramsPerUnit(time) × sick multiplier` (today
  it is the single value × sick multiplier).

### 2. Consumers

- **Bolus calculator** (`InsulinCalculatorService.resolveGramsPerUnit`): validates the window value
  in effect at the dose time (override, else single) against 3-30 and refuses exactly as today;
  returns the effective (sick-scaled) window value. `calculateForecastCorrection` keeps calling it as
  its plausibility check.
- **Hovorka forecast** (`HovorkaGlucosePredictionService.mealWindowFactor`): `A_G` stays built from
  the single ISF and single ratio. Each meal is rescaled so that its carb effect per gram is
  window ISF ÷ window ratio:
  `factor = (windowIsf / baseIsf) × (baseGramsPerUnit / windowGramsPerUnit)`, clamped to the existing
  0.33-3.0. No sick multiplier and no twin scale, as today. When the single ratio is unusable (it
  did not shape `A_G`) but the window ratio is usable, the ratio part sizes the meal from the window
  ratio itself: `mealMagnitude(baseIsf, VG, windowGramsPerUnit)` divided by the raw (un-twinned)
  `A_G` a missing ratio gives, so the twin's meal scale stays a multiplier. When neither ratio is
  usable the whole factor is 1.0 (no ISF part either). When only the window ratio is unusable, the
  ratio part is 1.0. This keeps "a meal dosed by its window's ratio forecasts flat".
- **Classic COB forecast** (`GlucoseCalculationsService`): already reads
  `getEffectiveGramsPerUnit(t)`, so it becomes window-aware with no further change. It resolves by
  step time, as its ISF already does; left as is.
- **Empirical twin forecast** (`EmpiricalTwinParams` / `EmpiricalTwinEngine`): replace the two
  ratios `crAM` / `crDay` with four window ratios, looked up per meal from the meal's local hour
  (the engine already uses `e.localHour()`). `forUser` takes the four window values (each falling
  back to the single ratio); with no usable ratio at all, the defaults keep 7 g/U for breakfast and
  10 g/U for the other three. The twin's ISF stays the "now" ISF, as today: insulin and carbs both
  use it, so ratio-dosed meals still net flat. `csf` keeps following from ISF and the single ratio.
- **Sick mode:** unchanged rule (the ratio is scaled by the same multiplier as ISF), now applied to
  the window value.

### 3. ISF learner becomes corrections only

`IsfMealWindowProfileService.estimateForBolus` keeps only events that today get
`WEIGHT_CORRECTION` (carbs in the event envelope plus prior COB below `CARB_THRESHOLD_GRAMS`, 10 g).
Meal-attached events no longer produce an ISF estimate; `WEIGHT_MEAL` goes away. The small-carb
term `absorbedGrams / ratio` uses the window ratio at the bolus time
(`getWindowGramsPerUnit(t0)`), not the single ratio. Everything else (14 days, CGM lookup, stacking
rule, plausibility band, `MIN_WEIGHTED_SAMPLES`) is unchanged. Expected effect: fewer windows with
ISF data, so fewer ISF proposals.

### 4. Ratio learner (new)

A new service next to the ISF learner (name left to the builder, e.g.
`CarbRatioMealWindowProfileService`), using the same data access and the same time handling as
`IsfMealWindowProfileService` (note `LocalDateTime` to epoch through the JVM zone; bucket on the
note's hour).

Per meal event:
- **Anchor:** a rapid (not long-acting) bolus with at least 10 g of non-rescue carbs logged within
  30 min either side of it. When several boluses fall within 30 min of the same meal (a split
  bolus), the earliest is the anchor and the others are counted as its insulin, not as new anchors.
- **Window:** the event starts at `START`, the earlier of the meal note and the anchor bolus `t0`,
  and runs to `tEnd = START + DIA` of the user's current rapid insulin. A bolus given after eating
  therefore does not move the CGM baseline past the rise it was given for. Each anchor bolus anchors
  at most one event: the first meal note (in time order) to claim it consumes it, whether that event
  is then used or skipped by a check below, and a later note sharing it is a duplicate. Its carbs
  still count through the carbs window, and the first note defines `START`.
- **Clean meal - skip the event when:** another meal (at least 10 g of non-rescue carbs) is logged in
  `(START + 30 min, tEnd]`, since its carbs and insulin would be mixed into this estimate and filed
  under the wrong window; CGM is missing at `START` or `tEnd` (same 15-min tolerance); or `t0` falls
  in a sick period excluded from learning. Smaller snacks, rescue carbs and correction doses inside
  the window do not disqualify a meal; they are counted as below. A meal skipped because of a later
  meal does not stop that later meal from being its own event, except that a later note within
  30 min of an already-claimed bolus is a duplicate (see "Window").
- **Carbs:** every carb entry logged in `[START − 30 min, tEnd]`, **including rescue carbs**, plus COB
  at `START` from meals in `[START − 8 h, START − 30 min)`, minus COB at `tEnd` (same COB service as
  the ISF learner). Rescue carbs count because dropping meals that needed them would hide a ratio
  that is too aggressive.
- **Insulin:** all insulin on board at `START` from earlier rapid doses, plus any dose at `START` in
  full, plus every rapid dose in `(START, tEnd]` counted by the share of it that acted by `tEnd`
  (units − its IOB at `tEnd`, on its own insulin curve). A bolus given after the meal is counted
  this way; the anchor bolus counts in full only when it is at `START`. Follow-up corrections count
  because dropping those meals would hide a ratio that is too weak.
- **Estimate:** `Δ = CGM(tEnd) − CGM(START)`; `ISF_w` = the window ISF from settings at `t0`
  (`getWindowIsf`, no sick multiplier, no twin scale);
  `unitsNeeded = insulin + Δ / ISF_w`; skip if `unitsNeeded < 0.5 U`;
  `ratio = carbs / unitsNeeded`; skip if outside 3-30 g/U.
- **Worked example:** 60 g, 6 U, ended 1.5 mmol/L higher, ISF 2.5 → needed 6.6 U → 9.1 g/U. With ISF
  2.0 instead: 6.75 U → 8.9 g/U.

Per window: the **median** of the estimates when there are **at least 3** in the last 14 days,
otherwise no data. Each estimate is filed under the window of the anchor bolus `t0` (the dose time
the calculator prices the meal at), not of `START`.

Storage: new table `carb_ratio_meal_window_snapshots` (new migration `V23`): `user_id`,
`meal_window`, `grams_per_unit` (nullable = no data), `sample_count`, `computed_at`; unique on
(`user_id`, `meal_window`).

Recompute: the existing nightly `IsfMealWindowScheduler` (02:00 UTC) and the existing
`POST /api/isf/meal-windows/recompute` recompute both learners.

### 5. Suggestion card

Backend: extend `IsfMealWindowSuggestionService` and its DTO; the endpoints stay
`/api/isf/meal-windows/suggestion`, `/suggestion/accept`, `/suggestion/dismiss`.

- Each `WindowProposal` gains `currentGramsPerUnit`, `proposedGramsPerUnit`, `ratioHasData`,
  `ratioSamples`. Current = the window override, else the single ratio.
- Proposed ratio = the learned median, **limited to within ±20% of the current value**, rounded to
  0.1 g/U.
- A ratio line is a material change when it differs from current by **at least 10%**.
- ISF lines keep today's rules, including the twin-ready gate: when the twin is not ready, ISF lines
  carry no proposal.
- The card shows when the 3-day cadence allows it and either the ISF part qualifies (today's rules)
  or at least one ratio line is a material change. The twin gate no longer blocks ratio lines.
- **Accept** applies, in one settings save, every ISF proposal (only when the twin is ready) and
  every ratio proposal with data, then records `lastAcceptedAt`. It no longer throws just because
  the twin is not ready; it refuses only when there is nothing to apply. As today, accept recomputes
  the suggestion server-side rather than trusting what the client showed.
- **Ratios need the client's opt-in:** `POST /suggestion/accept` takes an optional JSON body
  `{"includeRatios": true}`. Ratio proposals are applied only when it is true. An older app build
  sends no body, shows only ISF lines, and so applies only ISF - it can never change a ratio the user
  did not see. The new app always sends `includeRatios: true`.
- **Dismiss** is unchanged; one cadence covers the whole card.

iPhone (`ContentView.isfSuggestionBanner`):
- Title "Updated settings ready"; the caption mentions ISF and carb ratios.
- Each window line shows ISF `current → proposed` and ratio `current → proposed g/U`; a value
  without a proposal shows as the current value; "No data yet" when neither has data.
- Decode the new fields as optional, so an older backend simply shows no ratio column.
- Apply sends `{"includeRatios": true}` (an older backend ignores the body).

```
✨ Updated settings ready
             ISF            Carb ratio
Breakfast    2.50 → 2.20    10 → 8 g/U
Lunch        2.50           10 → 12 g/U
Dinner       No data yet
Night        —
[ Not now ]          [ Apply ]
```

### 6. iPhone settings

- `BackendAPI.COBSettings` gains four optional fields matching the DTO (omitted on encode when nil,
  so a save cannot clear a stored window).
- `SettingsView` is grouped by meal (user's decision): one section per meal window holding that
  window's ISF and carb ratio together. The "Insulin-to-carb ratio" row stays in "User Settings" as
  the default for windows not set. Each ratio row shows `window ?? gramsPerUnit ?? 5` and pins the
  window on edit, like the ISF rows. Save, Insulin Preferences and Sign Out move below the meal
  sections so Save still comes after everything it saves.

```
USER SETTINGS
  Glucose Units            mmol/L
  Insulin-to-carb ratio    5      grams per unit * default
  Body Weight / Target Glucose / Rescue Carbs

BREAKFAST · 05:00 – 11:00
  ISF                      2.5    mmol/L per unit
  Carb ratio               5      grams per unit
LUNCH · 11:00 – 16:00
  ISF / Carb ratio
DINNER · 16:00 – 22:00
  ISF / Carb ratio
NIGHT · 22:00 – 05:00
  ISF / Carb ratio

  Save
  Insulin Preferences  >
  Sign Out
```

## Testing

- Unit: window resolution and sick scaling in `UserSettingsDTO`; the calculator prices with the
  window ratio and refuses an out-of-range window value; `mealWindowFactor` with ISF and ratio
  overrides; the empirical twin picks the ratio by meal hour; the ISF learner ignores meal events;
  the ratio learner (worked example, follow-up correction counted, rescue carbs counted, split bolus
  counted once, a second meal inside the window skips the first but still counts itself, fewer than
  3 meals gives no data, sick period skipped, out-of-range dropped); the suggestion (±20% cap, 10%
  materiality, ratio lines shown while the twin is not ready, accept applies both with
  `includeRatios: true` and ISF only without it).
- End-to-end (`prediction-e2e-tests` skill): a breakfast dosed by the breakfast ratio, with
  breakfast ISF and ratio overrides set, forecasts roughly flat.
- iPhone: `COBSettings` round-trip with the window ratios; suggestion decoding with and without the
  ratio fields.

## My calls (not discussed with the user)

- Ratio windows reuse the four ISF windows.
- 14-day history, at least 3 clean meals per window, median.
- Meal anchor: bolus with at least 10 g carbs within ±30 min.
- A meal with another meal inside its insulin window is skipped, not merged.
- Rescue carbs and follow-up insulin counted, not rejected (rather than requiring "no other bolus
  within 90 min", which would bias toward the current ratio).
- Applying ratios needs an opt-in flag from the app, so an older build can't change them unseen.
- Proposals capped at ±20% of the current value per apply; a line counts as a change at 10%.
- Ratio lines do not wait for the digital twin.
- The single ratio row stays in Settings as the default.
- Separate snapshot table instead of new columns on `isf_meal_window_snapshots`.

## Left to the builder

- Class, method and test names; how the event-anchoring is implemented within the rules above.
- Whether to also expose the ratio profile on its own GET endpoint (not needed by the app).
- Display rounding on the iPhone.
- Bean validation on the new DTO fields, beyond the DB CHECK constraints.
- A pre-bolus that crosses a window boundary is priced at the dose time's window by the calculator
  and forecast at the meal time's window, as with ISF today; no special handling.
