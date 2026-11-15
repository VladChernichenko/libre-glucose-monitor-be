# Illness profile ("sick mode") - design

Date: 2026-11-16. Scope: backend (`glucose-monitor-be`) and the iPhone app (`glucose-monitor-iphone`).

## Intent

When a person with type 1 diabetes is ill, insulin works less well and background insulin need
rises. Today the app assumes healthy sensitivity at all times: forecasts fall too fast, suggested
doses are too small, and - since the twin now fits ISF from corrections - sick-day corrections
would be learned as if they were normal and stay applied after recovery.

Sick mode lets the user say "I am sick, this badly". The model then uses a lower ISF and a higher
basal need for as long as it is on, every dose suggestion follows from that, and the sick period is
kept out of everything the app learns.

## Goals

- The user switches sick mode on with a severity, changes it, and switches it off.
- While on, forecasts and all dose suggestions (corrections and meal doses) reflect reduced
  sensitivity and raised basal need.
- A high that does not respond to a correction while sick leads to a proposal to step up - never
  to an automatic change.
- Sick periods do not influence the digital twin, the meal-window ISF estimates or the personal
  insulin curve.

## Non-goals

- Detecting illness automatically or estimating the multiplier from data (a sick episode is days
  long: too little data).
- Any logic that depends on ketone measurements. Most users have no strips.
- Suggesting long-acting (basal) doses. The app shows the raised basal need as information only.
- The web app, and the Apple Watch app beyond whatever it already renders from the dashboard strip.

## Anti-goals

- A larger dose applied or suggested without the user having confirmed the state that produced it.
- Sick mode left on by accident after recovery (it must lapse on its own).
- A forecast that contradicts the dose suggestion (e.g. shows a low after the suggested dose).
- Stacking: a second correction priced without the insulin still working from the first.

## Decisions made with the user

1. **Severity table** (user-supplied):

   | Severity | Meaning | ISF multiplier | Basal need |
   |---|---|---|---|
   | MILD | runny nose, mild cold, no fever | x0.85 | +0% |
   | MODERATE | fever 37.5-38.5 C, body aches | x0.70 | +15% |
   | SEVERE | fever above 38.5 C, infection | x0.50 | +30% |
   | SEVERE_UNRESPONSIVE | severe, and a correction did not bring a high down | x0.40 | +30% |

   The fourth level is the user's "+25% correction" expressed as a state the forecast knows about
   (0.50 / 1.25 = 0.40), so the dose and the forecast stay consistent. It is reached only through
   the stubborn-high proposal, never offered in the picker, and keeps the SEVERE basal need.
   *(reachability and basal figure: my call)*

   Basal figures are the low end of the user's ranges (0-10 / 15-20 / 30-40%): a higher figure raises
   the forecast and with it every suggested dose, so the cautious end was chosen. *(my call, accepted)*

2. **Meal doses rise too**, by the same factor as corrections.

3. **Stubborn high -> propose and confirm.** Condition: sick mode is on, a correction was logged
   at least 2 h ago during the episode, glucose is still above 13.0 mmol/L, and it is less than
   1.0 mmol/L below the reading at that correction. The app then proposes moving up one severity
   level: MILD -> MODERATE (about 21% more insulin), MODERATE -> SEVERE (about 40%),
   SEVERE -> SEVERE_UNRESPONSIVE (25%). At SEVERE_UNRESPONSIVE there is no further step: the app
   only repeats the ketone hint and the medical-help line. Nothing changes until the user accepts.

4. **Ketones are an optional hint.** The stubborn-high proposal says "check ketones if you can".
   At SEVERE and above it adds one plain line: vomiting, fast breathing, or highs that will not come down are
   reasons to get medical help. No logic reads a ketone value.

5. **Ending.** The user ends it, or answers "No" to the morning question. Without any confirmation
   for 48 h it ends by itself; falling back to normal ISF is the cautious direction.

6. **Entry points.** A control on the iPhone dashboard, and the chat ("I'm sick" / "I'm better").

7. **Backdating.** The picker asks "Since when?": now, this morning, or yesterday. Doses already
   logged in that span get the sick-day multiplier in the forecast, and the span is kept out of
   learning like the rest of the episode.

## Approaches considered

- **A. Sick mode as a state the model knows about - chosen.** A timed episode with a severity; the
  ISF multiplier attaches to each dose by the time it was taken; basal need raises background
  glucose production; forecast, dashboard strip, chat meal plan and watch all follow from one source.
  Cost: it touches the forecast path and needs careful tests.
- **B. Adjust suggested doses only.** Smaller, but the forecast would contradict the dose: it would
  show a low after the larger correction, and the strip's "insulin still landing" wait would fight it.
- **C. Detect illness and estimate the multiplier.** Rejected: not enough data inside one episode.

The user picked A.

## How it works

### The episode

- Stored per user as time segments: `severity`, `started_at`, `ended_at` (null while open),
  `last_confirmed_at`, `end_reason` (USER, MORNING_NO, LAPSED). Changing severity closes the open
  segment and opens a new one, so every moment in the past has exactly one severity or none.
  New table -> a new migration file is allowed by the project's schema rule.
- At most one open segment per user.
- A start may be backdated to "this morning" (06:00 today, user's clock) or "yesterday" (06:00 the
  day before); never further, and never before the end of a previous episode. *(my call on the
  exact times)*
- The episode freezes the user's healthy usual daily long-acting amount at its start
  (`BasalInsulinResolver.usualDailyUnits` over the log before `started_at`), carried on every
  segment of the episode. *(my call - see Basal need)*
- Lapse: an open segment whose `last_confirmed_at` is older than 48 h is closed at
  `last_confirmed_at + 48 h` the next time it is read (no scheduler needed).

### Effect on the model

- **ISF.** The effective ISF at time *t* is `settings ISF(t) x twin ISF scale x sick multiplier(t)`.
  A dose keeps the multiplier of the moment it was taken for its whole action, exactly as it keeps
  its meal-window ISF and its insulin curve today. All ISF reads go through
  `UserSettingsDTO.getEffectiveIsf(time)` (8 call sites: forecast, dashboard, watch strip via
  `TwinIsfService`, meal plan, history dose, calculator, context aggregator); the multiplier should
  enter at that one seam so no caller can miss it.
- **Meal doses.** The calculator prices a meal as `carbs / gramsPerUnit`, which does not involve
  ISF. Sick mode therefore also multiplies grams-per-unit by the same factor at dose time. The
  glucose rise per gram of carbs is unchanged (ISF and grams-per-unit move together).
- **Basal need.** `BasalInsulinResolver` already measures logged long-acting insulin against the
  user's usual daily amount. While sick, the amount needed for full coverage is
  `healthy usual x (1 + basal need)`. A user who raises their long-acting dose accordingly sees no
  drift; a user who does not sees the forecast drift upward, which is what happens in reality.
  - "Healthy usual" is the amount frozen on the episode, not the live log: the resolver reads
    "usual" from a 56 h window, so a raised sick-day dose would otherwise become the new usual
    within a day and the drift would return. *(my call)*
  - The frozen amount is also used as "usual" for 56 h after the episode ends, so a normal dose
    after recovery does not read as under-coverage against the raised sick-day doses. *(my call)*
  - With no long-acting dose in the log the forecast keeps its existing steady-state assumption:
    no drift is forced. The card still shows the basal need as information. *(my call)*
- **Engines.** The Hovorka path (the default, and the only one with a per-dose ISF) is exact: each
  dose carries the multiplier of its own time, and the gut model's `aG` is left untouched so the
  carb rise is unchanged. The legacy step-wise path and the empirical engine take the multiplier
  in force at "now" for all doses; that approximation is accepted. *(my call)*
- **Grams-per-unit guard.** The stored grams-per-unit is validated against the existing 3-30
  range first and scaled afterwards; the scaled value is not re-checked against that range. The
  final-dose guards still apply. *(my call)*
- **Dose suggestions** need no separate rule: the strip prices corrections from where the forecast
  lands and already waits while insulin is landing, so a lower ISF produces larger suggestions with
  insulin on board accounted for. Existing dosing guards (maximum bolus per kg, refusals) stay.

### Stubborn high

- Evaluated where the dashboard strip is built (`WatchStripService`). It yields a new strip
  proposal kind: "step up to <severity>".
- Accepting a step-up changes the episode severity (new segment); the next strip suggestion is then
  priced at the new ISF as usual. At SEVERE_UNRESPONSIVE the strip shows the hint and help line
  with no step-up.
- One proposal per correction, whatever the answer: after an accept or a decline, that correction
  cannot trigger another. A later correction that also fails can. So one unresponsive correction
  moves the user at most one level. *(my call)*
- "The correction" is the most recent note labelled Correction with insulin, no carbs, not
  long-acting (the rule `DigitalTwinCalibrationService.toEvent` already uses), logged inside the
  episode. The proposal is suppressed when any carbs were logged after it: a high after a meal is
  not evidence of worse illness. *(my call)*

### Learning

- CGM readings inside any sick segment are excluded from the twin calibration, the same way
  flagged glycemic-event windows are, but unconditionally: if too little data remains, that night's
  calibration is skipped rather than fitted on sick data.
- Boluses inside a sick segment are skipped by the meal-window ISF profile and by the
  personal-insulin-curve stage.
- Meals inside a sick segment are skipped by `HistoryDoseService` (they carry more insulin per
  gram than a healthy day needs). Meal episodes are still built and searchable. *(my call)*
- For all of the above, "inside a sick segment" extends 6 h past the episode's end: a dose taken
  just before the end keeps acting, and sensitivity does not recover at the tap. With that buffer
  no replayed dose ever needs a sick multiplier. *(my call)*
- The glycemic-event engine is not changed: with the sick ISF in the forecast its residuals are
  already measured against the right expectation. *(my call)*

### What the user sees (iPhone)

```
Dashboard, sick mode on               Morning, first open of the day
+--------------------------------+    +--------------------------------+
| Sick - moderate - day 2        |    | Still sick?                    |
| ISF -30% - basal need +15%     |    | [Yes, same] [Change] [No]      |
|                  [Change/End]  |    +--------------------------------+
+--------------------------------+
Stubborn high
+------------------------------------------------+
| Glucose is not coming down as expected.        |
| Treat this as severe illness? (ISF -50%)       |
| Check ketones if you can.                      |
| [Yes, raise] [Keep moderate]                   |
+------------------------------------------------+
```

- Off state: a small "I'm sick" entry on the dashboard opening a three-option severity picker with
  the symptom descriptions from the table.
- The chat handles a present-tense statement ("I'm sick", "I have a fever") as a new intent: it
  replies with a confirmation card showing the severity (asking for it if not given) and the
  "since when" choice, and the mode switches only when the user confirms. "I'm better" ends it the
  same way. This replaces the tagged-note proposal for those messages. A remark about a past event
  ("felt sick after lunch") keeps today's behaviour: a note tagged `ILLNESS`, no mode change.
  *(my call; the confirm step follows from the anti-goal on unconfirmed state)*

### API (shape, names left to the builder)

- Read current state (severity, since, day count, multipliers, whether the morning question is due).
- Start / change severity / confirm "still sick" / end.
- The dashboard strip payload carries the stubborn-high proposal; one call accepts or declines it.

## Constraints

- Project rules: Flyway migrations (new table = new file), files under 500 lines, tests run after
  changes, validation at boundaries.
- The forecast path is shared by live predictions and calibration replay; sick multipliers must
  apply in live predictions and be irrelevant to replay (sick time is excluded there).
- Timestamps: notes are stored as the user's wall time; sick segments must be on the same clock.

## Left to the builder

- Table, class, endpoint and field names; how the multiplier is carried into `getEffectiveIsf`.
- Whether the +30% basal need at SEVERE produces a plausible forecast drift for an unchanged
  long-acting dose; if tests show it is implausibly steep, report back rather than tune silently.
- Exact wording of user-facing texts (English and Russian, as the chat already supports both).
- iPhone view structure, following the existing dashboard cards (`WatchStripView`, prompt cards).

## Verification

- Unit: multiplier by time (before / inside / after a segment, across a severity change), lapse at
  48 h, stubborn-high condition and its non-repetition, calibration exclusion.
- Forecast: same inputs with and without sick mode - smaller insulin effect, unchanged carb rise,
  upward drift only when long-acting was not raised.
- Dose: correction and meal dose both scale by 1 / multiplier; guards still refuse oversized doses.
- End to end on the dev database: switch on, check the strip suggestion grows, switch off, confirm
  the next calibration ignores the period.
