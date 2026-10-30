# Dashboard watch strip: design

Date: 2026-10-30. Repos: `glucose-monitor-be` (computation) and `glucose-monitor-iphone` (display).

## 1. Intent

Vlad wants the app to "show when it's necessary" the way his Glucose Twin notes do: attention is
scheduled around the moments that matter (after a dose or meal, a forecast low, a low being treated,
activity on top of insulin), with a concrete answer each time ("take 15 g now, recheck in 15 min",
"no action, in range by 20:00", "1 U").

The long-term home for this is a full-colour lock screen Live Activity. That needs push updates, and
push needs the paid Apple Developer Program; the app is on a free team today. So the first step is an
in-app **watch strip** on the dashboard. The same backend output will later drive the Live Activity and
notifications unchanged.

## 2. Goals

1. A strip at the top of the dashboard appears only while something needs watching, and disappears
   once things settle.
2. Every strip gives a computed result from everything the system knows (reading, trend, forecast,
   insulin and carbs on board, logged activity, the user's settings) plus the basis for it.
3. Corrections aim at a per-user target glucose (new setting, default 100 mg/dL).
4. Activity changes suggestions based on the user's own history, not fixed population numbers.

## 3. Non-goals

- Lock screen Live Activity, push, notifications (later, after the paid developer account).
- Telegram alerts. That feature is dead; its branches were deleted on 2026-10-30. Nothing here may
  depend on it.
- Changing how the forecast itself is built, beyond feeding it learned activity values (§6.5).
- Long-acting (Tresiba) dose advice.

## 4. Anti-goals: it failed if…

- **It suggests a dose or carbs from stale data.** When LibreLinkUp is unavailable or the reading is
  old (the dashboard's existing `libreDataStale` / >15 min rule), there is no suggestion at all.
- **It overdoses.** Insulin counted twice (forecast already includes it), a rising trend arrow pushing a
  correction up, corrections stacked on active insulin, or a dose suggested below 3.9 mmol/L.
- **It disagrees with the dashboard.** COB/IOB, the forecast and hypo thresholds must come from the
  existing single sources, not new copies (see `2026-08-08-dual-computation-findings.md`).
- **It nags or flickers.** A strip that appears and vanishes on alternate refreshes around a threshold.
- **It hides why.** A number without its basis.

## 5. Decisions made with the user

| Topic | Decision |
|---|---|
| Surface | In-app dashboard strip for now; Live Activity / notifications later |
| Look | Option A from the mockups: a compact one-to-two-line strip above the glucose card. Green "calm" mood (watching after a dose/meal), red "urgent" mood (low/high coming or happening). The card and chart below stay as they are |
| Suggestions | Computed, concrete, from all current state and settings |
| Target glucose | Configurable per user, default 100 mg/dL (5.55 mmol/L), shown in the user's unit |
| Activity | Based on the user's own experience (learned), see §6.5 |
| Telegram | Deleted; not reused |

## 6. How it works

### 6.1 Where it is computed (my call)

The backend computes the strip. It is added as a `watch` object on the existing
`GlucoseCalculationsResponse`, which the app already fetches on every refresh, so the strip, the card
and the chart always come from one calculation. iOS only renders it.

```
watch: {
  state:        NONE | CALM | URGENT_LOW | URGENT_HIGH,
  headline:     "Low likely ~19:30 (3.6)",          // server-formatted in mmol/L, see §6.6
  detail:       "falling fast · 1.8 U still active",
  suggestion: {
    kind:        CARBS | INSULIN | NO_ACTION | REFUSED,
    grams:       15,        // CARBS only
    units:       1.0,       // INSULIN only
    refusalCode: "GLUCOSE_BELOW_SAFE_THRESHOLD",   // REFUSED only, reuses existing codes
    basis:       "11.2 now → 9.8 in 4 h, target 5.6, ISF 2.1"
  },
  nextCheckAt:  "2026-10-30T19:15:00",
  landing:      { at: "...", mmol: 6.1 },          // where the forecast ends up, for "in range at 20:00"
  activityEstimate: LEARNED | DEFAULT               // DEFAULT = "activity estimate still rough"
}
```

Field names are a suggestion; the builder may rename. `state = NONE` means no strip.

### 6.2 When the strip shows (states; thresholds are my calls)

Evaluated in this order; the first match wins.

1. **No data** → `NONE` with no suggestion when the newest reading is older than 15 min or the
   LibreLinkUp upstream circuit is not available (`LibreLinkUpSyncService.upstreamStatus`). The app
   already shows its stale banner in the second case. Freshness rules (my calls, after the builder
   check found no existing 15-min rule):
   - The server decides freshness itself from its own newest stored reading in `cgm_readings` (any
     data source, so Nightscout users are covered), not from the number the client sent, which has no
     timestamp. If the client's `currentGlucose` differs from that reading, the strip uses the stored
     reading.
   - A glucose that comes only from a manual note (no CGM reading in the last 15 min) never produces a
     suggestion; the strip may still show `CALM` text without one.
   - The app hides the whole strip whenever `calculationsStale` is true or the calculation was
     restored from `DashboardCache` and not yet refreshed. An old "take 1 U" must never stay on
     screen.
2. **URGENT_LOW** when any of:
   - newest reading < `HypoThresholds.HYPO_MMOL` (3.9);
   - forecast drops under 3.9 within 30 min;
   - a low is being handled: a `hypo_events` row that is `OPEN`, or `CONFIRMED` with no
     `recovered_at` (my call: confirming the rescue is when treatment starts, so it must not end the
     strip). It stays until the reading is ≥
     `HypoThresholds.RECOVERY_MMOL` (4.5) **and** the forecast stays ≥ 4.5 for the next 60 min.
     Uses the existing hysteresis so it cannot flicker.
3. **URGENT_HIGH** when the forecast stays above 10.0 mmol/L at +2 h and is not heading back into
   range by +4 h (`fourHourPrediction` > 10.0).
4. **CALM** within 4 h of a logged rapid dose, a meal ≥ 10 g carbs, or an activity, while IOB ≥ 0.3 U or
   COB ≥ 5 g.
5. Otherwise `NONE`.

To avoid flicker at the 10.0 and 30-min edges, a state that was shown stays until two consecutive
**new CGM readings** no longer meet it, unless a higher-priority state replaces it (my call: counted
per stored reading, not per API call, because the endpoint is called every minute while data is old
and also by the Notes what-if forecast; the what-if calls must not touch the hold). The builder
decides where the hold is stored (e.g. per-user last state in memory).

### 6.3 What it suggests

- **URGENT_LOW → CARBS, never insulin.**
  grams = enough to lift the forecast minimum to 4.5 mmol/L plus a 0.5 margin, using
  `user_settings.carb_ratio` (which is **mmol/L rise per 10 g**, not g/U):
  `grams = ceil5(max(0, 5.0 − forecastMin) / carb_ratio × 10)`, at least the user's
  `rescue_carbs_g` (default 15), at most 40 g. Basis shows the forecast minimum and when.
  `nextCheckAt` = now + 15 min. Rescue carbs already logged are in COB and the forecast
  (`RescueCarbProfile`), so they are **not** subtracted again. Within 15 min of a logged rescue the
  strip suggests no more carbs and says "recheck, wait for the 15 g to act" (my call).
- **URGENT_HIGH → INSULIN** via the existing `InsulinCalculatorService` safety path, with these
  changes for a correction (no carbs):
  - aim from the forecast landing (`fourHourPrediction`), not the current reading. The forecast already
    counts IOB, COB and activity, so **active insulin is not subtracted again** (pass `activeInsulin = 0`
    or add a forecast-based entry point; builder's choice, but the double count must be impossible);
  - target = the user's `target_glucose_mmol`;
  - ISF = the same time-of-day, twin-scaled ISF the forecast used, so dose and forecast agree;
  - keep every existing refusal (below 3.9, above 0.3 U/kg, implausible settings) and surface it as
    `REFUSED` with its code;
  - round down to 0.5 U; under 0.5 U → `NO_ACTION` ("small rise, recheck in 1 h");
  - no correction within 2 h of the last rapid dose → `NO_ACTION` "insulin still landing" (the twin
    notes' no-stacking rule);
  - the trend arrow never increases the dose (PubMed: arrow methods overdose on rising trends,
    doi:10.1177/19322968211043162).
  `nextCheckAt` = now + 60 min.
- **CALM → NO_ACTION** with the landing ("in range at 20:00" or "lowest 4.6 at 21:10"). If the
  forecast minimum in the next 4 h is under 4.5 but not within 30 min, say so ("dip to 4.3 around
  21:10, have 10–15 g ready"). `nextCheckAt` = the earlier of the forecast minimum/peak time and
  now + 60 min.

Any missing input (no forecast, settings invalid) → `REFUSED`/no suggestion with the reason, never a
guess.

### 6.4 New settings

Two columns on `user_settings`, added in `V1__baseline_schema.sql` per the schema rules (needs
`flyway repair` on the dev DB, see memory note on the native Homebrew Postgres):

- `target_glucose_mmol DOUBLE PRECISION NOT NULL DEFAULT 5.55` (validate 4.4–10.0);
- `rescue_carbs_g INTEGER NOT NULL DEFAULT 15` (validate 5–40).

Exposed through `UserSettingsDTO` and the iOS settings screen, entered and shown in the user's unit
(`AppState.preferredGlucoseUnit`), stored in mmol/L. The AI assistant's hard-coded
`CORRECTION_TARGET_MMOl = 6.5` in `ContextAggregatorService` is replaced by the same setting (user's
decision, 2026-10-30): one target everywhere, so the strip and the assistant never give different doses
for the same moment. This makes existing AI corrections aim ~0.95 mmol/L lower by default; accepted.

### 6.5 Activity from experience

Today `ActivityModulation` uses fixed gains for everyone (`GAIN_INSULIN = 1.0`,
`TAIL_HALF_LIFE_MIN = 120`, `GAIN_INDEP = 0.005`) and `DigitalTwinCalibrator` accounts for activity but
does not learn its strength.

- Extend the twin calibration to fit two personal activity values on anchors that have logged
  activity in the previous 6 h: an insulin-sensitivity gain scale and the tail half-life. Clamps:
  gain ×0.5–×3, half-life 60–360 min (Vlad's notes show a fall 3–4 h after a walk, so the long end
  must be reachable). Ridge toward the current defaults like the other twin scales.
- Fit only with at least 5 logged activities in the training window; otherwise keep the defaults and
  report `activityEstimate = DEFAULT`.
- Store on `UserDigitalTwin` next to the other learned scales; `HovorkaGlucosePredictionService`
  builds `ActivityModulation` from them. Because suggestions come from the forecast, a walk on top of a
  dose then shows up as an earlier low warning, a smaller or withheld correction, and carbs in advance.
- Validate like the existing twin: out-of-sample MAE must not get worse, or the learned values are not
  used.
- My call: a separate stage **after** the existing scales are fitted (they stay fixed during it), with
  its own applied flag and its own MAE check against the calibrated-without-activity result. So
  existing users' ISF and other scales cannot shift because of this, and activity learning can be
  switched off on its own.

### 6.6 iOS

- New strip view at the top of `DashboardView` in `ContentView.swift` (above the glucose card, below
  the existing stale banner), rendering `calculations.watch`. Hidden when `state = NONE`, when
  `libreDataStale`, when `calculationsStale`, or when showing a cached calculation (§6.2).
- Calm: green tint; urgent: red tint; layout as mockup A (bold headline, one detail line with the
  suggestion basis and next check).
- The server sends mmol/L values; the app formats glucose numbers in the user's unit. The builder
  decides whether the server sends structured numbers plus a template, or the app composes the text;
  either way no glucose number may be shown in the wrong unit.
- Tap scrolls to the forecast chart (my call).
- New Swift files must be added to `GlucoseMonitor.xcodeproj/project.pbxproj` by hand.

## 7. Constraints from the existing code

- Backend glucose is mmol/L end to end; mg/dL is display only.
- COB/IOB come only from `GlucoseCalculationsService.activeCobIobInputs` (persisted notes only).
- Hypo thresholds come only from `service/observer/HypoThresholds`.
- Freshness: the app already forces a sync once a minute while the reading is over 5 min old, so the
  strip updates at least that often while outdated, and on every 5-min refresh otherwise.
- Data integrity: unlogged food and insulin are common (memory *data-integrity-gaps*). The basis line
  makes that visible; suggestions do not try to infer unlogged doses.

## 8. Testing

- Backend unit tests (mock-first, per project rules) for each state and its order, the flicker hold,
  the carb formula and its floor/cap, the correction path (forecast landing, no IOB double count,
  2-h no-stacking, 0.5 U rounding, every refusal), stale data → no suggestion.
- Calibration test with synthetic activity episodes: learned gain/half-life recover the generating
  values within tolerance; fewer than 5 activities → defaults.
- iOS: decoding test for `watch`, unit formatting in mmol/L and mg/dL, strip hidden on `NONE` and when
  stale.

## 9. Left to the builder

Exact wording, colours and spacing within mockup A; DTO field names; how the flicker hold is stored;
whether the correction uses a new calculator entry point or the existing one with `activeInsulin = 0`.
