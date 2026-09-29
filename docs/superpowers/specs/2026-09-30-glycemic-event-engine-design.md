# Glycemic event engine — design

- Date: 2026-09-30
- Scope: `glucose-monitor-be` (backend) + `glucose-monitor-iphone` (iOS)
- Status: design approved in conversation; written spec awaiting review
- Supersedes: the category/lifecycle model of `specs/unlogged-event-detector.md` (its detection core is kept)

## Problem

Glucose regularly moves in ways the logged inputs (COB/IOB) do not explain. Today
`UnloggedEventDetectionService` notices some of these moves, but:

1. **It only knows four explanations**, all food or insulin (`UNLOGGED_FOOD`, `UNDER_ESTIMATED_FOOD`,
   `UNLOGGED_INSULIN`, `UNDER_ESTIMATED_INSULIN`). Stress, insulin absorption problems, the dawn
   phenomenon and exercise are all mislabelled as one of those four.
2. **It actively mislabels one physiological pattern.** When insulin absorbs late (site issue), the
   delayed "slingshot" drop is flagged as a separate `UNLOGGED_INSULIN` event.
3. **It keeps only the single strongest run per scan**, and its window start is the first point past
   threshold, not the inflection point where the move began.
4. **It does not learn.** Flags store mean residual and σ-multiple only; there is no feature vector and
   no way for the user's answers to improve recognition.
5. **Nobody sees it.** The iOS app never calls `/api/unlogged-events`. The user cannot confirm or
   correct anything.

## Goals

Priority order agreed with the user: **real-time awareness first, reviewable history second.** Feeding
labels back into prediction accuracy is a later spec (beyond the existing calibration down-weighting).

- Detect unexplained glucose moves and classify them into ten categories (see *Taxonomy*), using an
  explicit, ordered IOB/COB/slope rule matrix.
- Classify patterns whose identity depends on what happens later (resistance vs. site issue vs.
  lipodystrophy; unlogged insulin vs. aerobic exercise; anaerobic spike-then-drop) via a
  provisional → resolved lifecycle.
- Mark each event on the iOS forecast chart at its true onset, with a label, within one scan of detection.
- Ask the user one tap to confirm or correct, and learn from those answers per user.
- Provide a reviewable history with simple insights.

## Non-goals

- **Push notifications.** No APNs (free Apple team; see `repo-topology-and-ios-build` notes). Delivery is
  in-app on refresh, like hypo events. No local/background notifications either.
- **Any dosing action.** Nothing here changes insulin, ISF, carb ratio or any dosing setting.
- Feeding event labels into the prediction model beyond today's down-weighting (follow-up spec).
- Injection-site logging, heart-rate/HRV input, calendar/stress markers (listed as future enablers).
- Backfilling activity notes from a confirmed exercise event.
- Changes to `GlucoseAnomalyDetector` (5-min ROC alerts). It overlaps with this engine and is a
  candidate to retire later; untouched here.
- Watch and widget support.

## Known limitations (stated, not solved)

- **Detection lag.** The 45-minute persistence gate (sensor-noise guard) plus a 10-minute scan means an
  event is tagged ~45–65 min after onset. The marker is drawn back at the onset.
- **Food vs. stress under active insulin.** A snack eaten while a correction is active lands in the
  Stress cell (R4). The one-tap correction exists for exactly this case and trains the learner.
- **Stress has no direct marker.** Without HR/HRV/calendar data, "stress" is inferred from the absence
  of a slingshot, an insulin-effect ratio that is not ≈0, and learned time patterns.
- **Lipodystrophy cannot be confirmed from CGM alone.** Without injection-site data it is a
  recurrence-weighted suspicion, labelled "Possible lipodystrophy / injection site issue".
- **No ground truth** for stress, site issue, lipodystrophy or dawn in the AZT1D/HUPA datasets; those
  categories are validated synthetically only.

## Taxonomy

| Category | Direction | Meaning |
|---|---|---|
| `UNLOGGED_FOOD` | RISE | Rise with COB ≈ 0 and insufficient IOB ("Unaccounted carbs") |
| `UNDER_ESTIMATED_FOOD` | RISE | Rise beyond what logged carbs explain |
| `STRESS` | RISE | Rise/plateau under significant IOB; insulin partly worked or glucose exceeded the no-insulin curve |
| `ABSORPTION_DELAY` | RISE | Rise/plateau under significant IOB followed by a delayed slingshot drop |
| `LIPODYSTROPHY` | RISE | Rise/plateau under significant IOB; insulin had ≈ no effect across its full action |
| `DAWN_PHENOMENON` | RISE | Gentle early-morning rise with no meal/insulin impact |
| `ANAEROBIC_ACTIVITY` | RISE | Short spike (logged activity, or followed by a drop) |
| `UNLOGGED_INSULIN` | FALL | Drop without logged insulin that continues for hours |
| `UNDER_ESTIMATED_INSULIN` | FALL | Drop beyond what logged insulin explains |
| `AEROBIC_ACTIVITY` | FALL | Drop with logged activity, or an unexplained drop that stops within 90 min |

## 1. Data model

### 1.1 Migrations

The repo rule (`glucose-monitor-be/CLAUDE.md`) forbids `ALTER TABLE` in a new migration: the file that owns
a table is edited in place, and a new `V{n}` file is only for a brand-new table. So:

- `V9__unlogged_event_flags.sql` is renamed to **`V9__glycemic_events.sql`** and edited in place to create
  `glycemic_events` directly in its final shape (constraints/indexes named `*_glycemic_*`, category check
  over the ten categories, and the columns below).
- **`V12__glycemic_event_history.sql`** creates the new `glycemic_event_history` table (1.2).
- **Existing databases need a one-time manual reset** because V9's checksum and description change. Flags are
  regenerable detector output, so they are dropped, not migrated. Only the last scan window can be
  re-detected, and any old user confirmations are lost:
  `DROP TABLE IF EXISTS unlogged_event_flags CASCADE; DELETE FROM flyway_schema_history WHERE version = '9';`
  then boot once with `SPRING_FLYWAY_OUT_OF_ORDER=true`. Run by the user on dev pg14 and on any deployed DB;
  never by automation.
- Add columns:

| Column | Type | Notes |
|---|---|---|
| `onset_at` | `TIMESTAMPTZ NOT NULL` | Inflection point; marker position. |
| `phase` | `VARCHAR(12) NOT NULL DEFAULT 'FINAL'` | `PROVISIONAL` \| `FINAL`. |
| `resolves_at` | `TIMESTAMPTZ NULL` | End of the tail watch; non-null only while provisional. |
| `linked_event_id` | `UUID NULL` | Self-FK, `ON DELETE SET NULL`. Child → parent. |
| `detector_category` | `VARCHAR(28) NOT NULL` | What the engine concluded. Never overwritten by the user. |
| `user_label` | `VARCHAR(28) NULL` | Category chosen by the user, when it differs from the detector's. |
| `confidence` | `DOUBLE PRECISION NULL` | 0–1, for the effective category. |
| `suppressed` | `BOOLEAN NOT NULL DEFAULT FALSE` | Learner says "not a real event": store, draw faintly, never prompt. |
| `features` | `JSONB NULL` | Feature vector, see 1.3. |

- `category` holds the **effective** category: `user_label` if set, else the detector/resolver result.
- States are unchanged: `OPEN` → `CONFIRMED` | `DISMISSED`. A relabel is `CONFIRMED` with `user_label`.
- Index: `(user_id, onset_at)` for chart range queries; `(user_id, phase)` for the resolver.

Local wall-time convention is unchanged: `onset_at`, `window_*`, `resolves_at` are the user's wall time
(same as notes), as the detector already writes them.

### 1.2 `glycemic_event_history` (append-only)

| Column | Type |
|---|---|
| `id` | `UUID PK` |
| `event_id` | `UUID NOT NULL` FK → `glycemic_events(id) ON DELETE CASCADE` |
| `user_id` | `UUID NOT NULL` |
| `at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` |
| `kind` | `DETECTED` \| `UPDATED` \| `RESOLVED` \| `CONFIRMED` \| `RELABELLED` \| `DISMISSED` |
| `category_before` / `category_after` | `VARCHAR(28) NULL` |
| `confidence` | `DOUBLE PRECISION NULL` |
| `actor` | `SYSTEM` \| `USER` |
| `reason` | `VARCHAR(24) NULL` — e.g. `rule:R4`, `learner`, `tail:slingshot`, `tail:no-effect` |

Rows are never updated or deleted by application code. Every state or category change on an event writes
exactly one row in the same transaction.

### 1.3 Feature vector (`features`, JSONB, `"v": 1`)

| Key | Unit | Meaning |
|---|---|---|
| `onsetHour` | 0–23.99 | Local hour of onset |
| `weekend` | bool | Onset on Sat/Sun |
| `durationMin` | min | Run duration |
| `meanResidual`, `peakResidual` | mmol/L | Over the run |
| `residualSlope` | mmol/L/min | Least-squares slope of residual over the run |
| `actualSlope`, `predictedSlope` | mmol/L/min | Least-squares slope over the first 30 min after onset. R3 uses `actualSlope`, not `residualSlope`: a step jump then plateau has a near-zero residual slope and must not read as a gradual dawn rise. |
| `glucoseAtOnset` | mmol/L | |
| `iobAtOnset` | U | Rapid IOB from `activeCobIobInputs` semantics |
| `expectedDrop60` | mmol/L | See 2.3 |
| `cobAtOnset` | g | |
| `minSinceCarbs`, `minSinceBolus` | min | Null if none in the 8h lookback |
| `activityLogged` | bool | Activity note within ±30 min of onset |
| `insulinEffectRatio` | – | Set by the resolver (4.2); null otherwise |
| `tailMinResidual`, `minToTailDrop` | mmol/L, min | Set by the resolver when a linked drop exists |
| `recentNoEffectCount14d` | count | `LIPODYSTROPHY` resolutions in the prior 14 days |

JSONB rather than columns: only the learner reads these and the set will grow without migrations.
Unknown keys are ignored by readers; `v` is bumped on a semantic change.

### 1.4 Calibration interaction

`DigitalTwinCalibrationService` keeps down-weighting `OPEN`/`CONFIRMED` windows, **except
`DAWN_PHENOMENON`**, which is a systematic pattern that `ResidualBiasModel` should learn, not ignore.
Linked children are down-weighted with their parent. `DISMISSED` is not down-weighted (unchanged).

### 1.5 Renames

`UnloggedEventFlag` → `GlycemicEvent`, `UnloggedEventFlagRepository` → `GlycemicEventRepository`,
`UnloggedEventFlagDTO` → `GlycemicEventDTO`, `UnloggedEventController` → `GlycemicEventController`,
`UnloggedEventScanScheduler` → `GlycemicEventScanScheduler`, `ConfirmUnloggedEventRequest` →
`ConfirmGlycemicEventRequest`. Feature flag `unlogged-event-detection-enabled` →
`glycemic-event-detection-enabled` (env `APP_FEATURES_GLYCEMIC_EVENT_DETECTION_ENABLED`, default true).
Config prefix `app.unlogged-events.*` → `app.glycemic-events.*`.

## 2. Detection pipeline

`UnloggedEventDetectionService` is split into focused units (each < 500 lines, each independently
testable). `GlycemicEventScanService` orchestrates; the scheduler calls it.

```
scan (every 10 min, per real user)
  ResidualScanner  → runs[]
  for each run: OnsetLocator → FeatureExtractor → EventClassifier → EventLabelLearner → merge/persist
  TailResolver     → resolve/link provisional events
```

Scan cadence: `app.glycemic-events.scan-interval-ms` default **600000** (was 1200000).

### 2.1 `ResidualScanner`

Keeps today's residual computation: raw Hovorka prediction (`PredictionResidualProvider.NONE`, no twin
overlay) anchored at the window start, notes → carbs/insulin/long-acting, `RescueCarbProfile` marking,
activity-aware via `NotesActivityProvider`, user-zone clock, nearest-point alignment, robust σ
(1.4826·MAD, floor 0.3). Changes:

- `window-minutes` default **240** (was 180).
- Residuals are smoothed with a 3-point running median before run detection. The raw residuals are
  kept for feature extraction.
- Return **every** sustained same-sign run with duration ≥ `persistence-minutes` (45) and
  |mean| ≥ `sigma-multiple` (2.0) × σ, not only the strongest.
- A run is split at any CGM gap > `max-gap-minutes` (15).
- < `min-readings` (24) aligned points → no runs.

### 2.2 `OnsetLocator`

From the run's first point, walk backwards over smoothed residuals to the last point with
|residual| < 0.5σ. That point's time is `onset_at`. Bounded to `onset-max-backtrack-minutes` (60) before
the run start; if no such point exists within the bound (or the window start is reached), onset = the
earliest point examined.

### 2.3 `FeatureExtractor`

Builds the 1.3 vector at `onset_at` from the scan's already-loaded notes and readings (no extra DB reads).

`expectedDrop60 = ISF_model × (IOB(onset) − IOB(onset + 60 min))`, where IOB uses the user's rapid-insulin
action curve (`RapidInsulinIobParameters`) over logged boluses and `ISF_model` is the sensitivity the
model uses for this user (`HovorkaParameters`-derived, mmol/L per U). **Significant IOB** ⇔
`expectedDrop60 ≥ significant-iob-drop-mmol` (1.5).

**COB ≈ 0** ⇔ `cobAtOnset < 5 g` and no carbs logged in `[onset − match-lead-minutes (45), windowEnd]`.

### 2.4 Merge and persist

A run overlapping an `OPEN` event of the same direction updates it (today's dedupe); otherwise a new
event is created. A run already claimed as a linked child (4.1/4.3) is not re-created. Every create or
update writes a history row (`DETECTED` / `UPDATED`).

### 2.5 Errors

Per-user exceptions are caught and counted (unchanged `ScanSummary`); one user never stops the batch. An
ODE failure skips the user for that pass. Provisional events whose user answered (`CONFIRMED` /
`DISMISSED`) are never touched by the resolver.

## 3. Classification

### 3.1 `EventClassifier` — ordered rule matrix (first match wins)

**Rise**

| # | Condition | Category | Phase | Base conf. |
|---|---|---|---|---|
| R1 | Carbs logged in match window, or `cobAtOnset ≥ 5 g` (an earlier meal still absorbing) | `UNDER_ESTIMATED_FOOD` | FINAL | 0.8 |
| R2 | `activityLogged` | `ANAEROBIC_ACTIVITY` | FINAL | 0.8 |
| R3 | `onsetHour ∈ [3, 8)` ∧ `0 < actualSlope ≤ 0.03` ∧ COB ≈ 0 ∧ ¬significant IOB | `DAWN_PHENOMENON` | FINAL | 0.6 |
| R4 | COB ≈ 0 ∧ significant IOB | `STRESS` (displayed "Resistance?") | PROVISIONAL, `resolves_at = onset + DIA` | 0.5 |
| R5 | COB ≈ 0 (IOB insufficient) | `UNLOGGED_FOOD` | FINAL* | 0.6 |

**Fall**

| # | Condition | Category | Phase | Base conf. |
|---|---|---|---|---|
| F1 | Rapid insulin logged in match window, or significant IOB (an earlier bolus still acting) | `UNDER_ESTIMATED_INSULIN` | FINAL | 0.8 |
| F2 | Run claimed as a linked child by the resolver | (parent's) | – | – |
| F3 | `activityLogged` | `AEROBIC_ACTIVITY` | FINAL | 0.8 |
| F4 | otherwise | `UNLOGGED_INSULIN` (displayed "Unexplained drop") | PROVISIONAL, `resolves_at = onset + 120 min` | 0.5 |

\* R5 events can still be relabelled `ANAEROBIC_ACTIVITY` by 4.3.

DIA = the user's rapid-insulin duration of action (default 6 h). Final confidence =
`base × clamp(sqrt(sigmaMultiple / 4), 0.5, 1.0)`. All thresholds and priors are `@Value` config under
`app.glycemic-events.*`.

The user's original list suggested IOB = 0 spikes may be stress; the later explicit rule routes them to
`UNLOGGED_FOOD` (R5). User relabels teach the learner otherwise.

### 3.2 `EventLabelLearner`

- **Training set:** the user's `CONFIRMED` events (label = `user_label ?: detector_category`) and
  `DISMISSED` events (class `NOISE`), same direction as the candidate. Loaded once per user and cached
  60 s (same pattern as `DigitalTwinService`).
- **Features:** `sin/cos(onsetHour)`, `weekend`, `residualSlope`, `peakResidual`, `durationMin`,
  `expectedDrop60`, `cobAtOnset`, `minSinceCarbs`, `minSinceBolus` (null → 480), `glucoseAtOnset`;
  standardised by the user's training-set median/MAD.
- **Model:** k-NN, k = 5, inverse-distance weighted → `p_knn(category)`.
- **Blend:** `p = (n0·p_rule + n·p_knn) / (n0 + n)`, `n0 = 10`, `n` = training-set size in this
  direction. `p_rule` puts the rule's confidence on its category and spreads the rest evenly over the
  direction's other categories and `NOISE`. With `n = 0` the output equals the rule output.
- **Override:** if `argmax p` ≠ rule category and `p ≥ 0.6`, the learner's category is used (history
  `reason = learner`). If `argmax p = NOISE` and `p ≥ 0.6`, the event is stored with `suppressed = true`.
- **Guardrails:** never crosses direction; never applies to a resolver decision from tail evidence
  (4.1–4.3); never changes phase. For a `PROVISIONAL` event the learner sets only `confidence` and
  `suppressed` — its category is left to the resolver, so the tail evidence decides.

## 4. Tail resolution — `TailResolver`

Runs after classification on each scan, over the user's `OPEN ∧ PROVISIONAL` events.

### 4.1 Resistance (R4) events

1. **Slingshot.** A FALL run in this scan with onset in `[parent.onset + peakTime, parent.resolves_at]`,
   no bolus logged between parent onset and the fall onset, not already claimed → parent becomes
   `ABSORPTION_DELAY` / `FINAL`; the fall is stored as a child (`linked_event_id = parent`, same category,
   never prompted, mirrors parent state). `peakTime` = the rapid-insulin peak from
   `RapidInsulinIobParameters`.
2. **At `resolves_at` with no slingshot:** run one counterfactual raw prediction from the parent's onset
   with the parent-window boluses removed (`G_noIns`), and compute over `[onset, resolves_at]`
   (mean over aligned points):
   `E = (G_noIns − G_actual) / (G_noIns − G_pred)` (denominator floored at 0.5 mmol/L; if the floor
   is hit, E is not computed and the event resolves to `STRESS`).
   - `0 ≤ E < 0.25` → `LIPODYSTROPHY`, confidence `min(0.9, 0.4 + 0.1 × recentNoEffectCount14d)`.
   - otherwise (E ≥ 0.25, or E < 0) → `STRESS`.
3. Resolution writes `RESOLVED` history with `reason` `tail:slingshot` / `tail:no-effect` / `tail:stress`
   and stores `insulinEffectRatio` / tail features.

### 4.2 Unexplained drops (F4)

At `resolves_at` (onset + 120 min): if the fall run ended within 90 min of onset (actual slope recovered
to ≥ predicted slope) → `AEROBIC_ACTIVITY`; else → `UNLOGGED_INSULIN`. Both `FINAL`.

### 4.3 Unlogged anaerobic activity

A FINAL `UNLOGGED_FOOD` event with `durationMin ≤ 75`, followed by a FALL run starting ≤ 30 min after
its window end with no insulin logged in between → relabelled `ANAEROBIC_ACTIVITY`; the fall becomes its
linked child. Applies only while the event is `OPEN` and unanswered.

### 4.4 User precedence

An event the user has confirmed, relabelled or dismissed is never resolved or relabelled by the system.
Confirming a provisional event makes it `FINAL`.

## 5. API — `/api/glycemic-events`

All endpoints are scoped to the authenticated user; 404 when the feature flag is off; another user's
event id → 404.

| Method | Path | Behaviour |
|---|---|---|
| `GET` | `?from=&to=&state=` | Events whose `[onset_at, window_end]` intersects the range. `from`/`to` required, local wall time, range ≤ 31 days, else 422. |
| `GET` | `/prompt` | The one event to prompt now, or 204. Selection: `OPEN`, not suppressed, not a child, `confidence ≥ 0.5`, onset within the last 6 h; highest confidence, then latest onset. |
| `POST` | `/{id}/confirm` | Body `{ category?, carbs?, insulin? }` — see 5.1. |
| `POST` | `/{id}/dismiss` | `DISMISSED`; history `DISMISSED`. |
| `GET` | `/{id}/history` | History rows, oldest first. |
| `GET` | `/insights?days=30` | `days ∈ [1, 90]`. Per-category counts, per-category hour-of-day histogram (24 bins), `noEffectCount14d`, per-category agreement rate (confirmed without relabel ÷ answered). |

### 5.1 Confirm rules

- `category` present → must match the event's direction, else 422. If it differs from the current
  effective category → `user_label` set, history `RELABELLED`; else history `CONFIRMED`.
- Provisional event without `category` → 422.
- `carbs` allowed only if the resulting category is `UNLOGGED_FOOD` / `UNDER_ESTIMATED_FOOD`;
  `insulin` only for `UNLOGGED_INSULIN` / `UNDER_ESTIMATED_INSULIN`; otherwise 422. Caps unchanged
  (≤ 300 g, ≤ 50 U, ≥ 0). Backfill writes a note at `onset_at` (was `window_start`).
- Already resolved by the user → 409.
- Result: `CONFIRMED`, `phase = FINAL`, `resolves_at = null`; children follow the parent's state.

### 5.2 `GlycemicEventDTO`

`id, category, detectorCategory, userLabel, displayLabel, direction, phase, state, onsetAt,
windowStart, windowEnd, resolvesAt, confidence, suppressed, linkedEventId, meanResidualMmol,
sigmaMultiple, promptOptions[]`.

All `LocalDateTime` fields serialise with `@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")` (the note-DTO convention the iOS decoder parses; `LocalDateTime.now()` otherwise emits nanoseconds it rejects).

`displayLabel` and `promptOptions` are server-generated (so wording and choices change without an app
release). `promptOptions` is an ordered list of categories valid for the event's direction:

| Event | `displayLabel` | `promptOptions` |
|---|---|---|
| Provisional R4 | "Resistance?" | `STRESS, ABSORPTION_DELAY, LIPODYSTROPHY, UNLOGGED_FOOD` |
| Provisional F4 | "Unexplained drop" | `UNLOGGED_INSULIN, AEROBIC_ACTIVITY` |
| `UNLOGGED_FOOD` | "Unaccounted carbs" | `UNLOGGED_FOOD, STRESS, ANAEROBIC_ACTIVITY` |
| `LIPODYSTROPHY` | "Possible lipodystrophy / injection site issue" | `LIPODYSTROPHY, ABSORPTION_DELAY, STRESS` |
| other FINAL | human-readable category | effective category first, then the rest of its direction |

Wording never gives dosing advice. The lipodystrophy insight text may say "consider checking your
injection sites".

## 6. iOS

### 6.1 Chart layer (`GlycemicEventMarkers.swift`, hooked into `GlucoseHistoryChart`)

- `AppState` fetches events for the chart's visible range on each refresh, alongside notes and hypo events.
- Per event: `RuleMark` at `onsetAt` (solid when `FINAL`, dashed when `PROVISIONAL`) and a translucent
  `RectangleMark` band `onsetAt…windowEnd`.
- Colour families: rise food/dawn/anaerobic = coral; fall categories = teal; `STRESS`,
  `ABSORPTION_DELAY`, `LIPODYSTROPHY` = purple.
- Label pills (server `displayLabel`) in the `chartOverlay`, on a row below the carb-chip row;
  provisional pills are dashed. Pills within 32 px merge into a count pill ("2 events").
- Linked children: band only, no pill, dotted connector to the parent. A child carries its parent's category (e.g. `ABSORPTION_DELAY`) with its own `direction = FALL`; children are never prompted or relabelled, so the mismatch is harmless. Suppressed: faint band, no pill.
  Dismissed: not drawn.
- Tapping a band or pill opens `GlycemicEventDetailSheet`: label, history timeline, plain-language key
  features, and the same answer buttons.

### 6.2 Prompt (`GlycemicEventPromptCard.swift`)

- Inline card under the chart (not a modal). `AppState.refreshGlycemicPrompt()` calls `/prompt` on
  refresh, mirroring `refreshHypoEvents` (keep state on failure).
- Buttons from `promptOptions`, plus **Not sure** (device-local snooze of this event id; stays `OPEN`)
  and **Nothing** (dismiss).
- Food/insulin options expand an optional amount field with presets; empty amount confirms without backfill.
- Controls are held while a request is in flight and released on failure (the `HypoPromptView` lessons).
- Hidden while `openHypoEvent` is set; hypo always wins.

### 6.3 History (`GlycemicEventsView.swift`)

From the dashboard secondary menu: insights header (`/insights`) and a day-grouped, category-filterable
event list; rows open the detail sheet (answer/relabel old events).

### 6.4 Plumbing

`BackendAPI.swift`: DTO + six calls. Unknown future categories decode to a generic event (never crash).
New Swift files are added to the app target in `project.pbxproj` (not the widget).

## 7. Testing

### 7.1 Unit (pure, mock-first)

- `ResidualScanner`: all qualifying runs returned; median smoothing prevents a one-point split; gap > 15
  min splits; < 24 points → empty.
- `OnsetLocator`: backtrack to last near-zero point; 60-min cap; window-start edge.
- `FeatureExtractor`: `expectedDrop60` vs. a hand-computed IOB curve; circular hour; null
  minutes-since.
- `EventClassifier`: one test per row R1–R5, F1–F4; ordering (R1 beats R3, R2 beats R4); boundaries
  (`expectedDrop60 = 1.5`, slope = 0.03, hour 03:00 and 08:00).
- `TailResolver`: slingshot → `ABSORPTION_DELAY`; bolus in tail blocks linking; E < 0.25 →
  `LIPODYSTROPHY`; E ≥ 0.25 and E < 0 → `STRESS`; denominator floor → `STRESS`; F4 stop ≤ 90 min →
  `AEROBIC`, else `UNLOGGED_INSULIN`; anaerobic relabel + child; user answer freezes resolution;
  lipodystrophy confidence grows with recurrence.
- `EventLabelLearner`: n = 0 equals rule output; blend at n = 10; override only ≥ 0.6; never crosses
  direction; never overrides tail evidence; dismissal cluster → `suppressed`.

### 7.2 Integration / E2E (Testcontainers)

- The 17 `UnloggedEventE2ETest` cases and `UnloggedEventFeatureFlagOffE2ETest` are renamed and must pass
  unchanged in behaviour (refactor regression guard).
- New synthetic scenarios through a real scan: withheld meal → `UNLOGGED_FOOD` at correct onset and
  returned by `/prompt`; bolus + flat → provisional, then (a) injected delayed drop →
  `ABSORPTION_DELAY` + child and **no** standalone `UNLOGGED_INSULIN`, (b) flat to DIA →
  `LIPODYSTROPHY`, (c) partial effect → `STRESS`; gentle 04:00–07:00 rise → `DAWN_PHENOMENON` and not
  down-weighted in calibration.
- API: 422 wrong direction / missing category on provisional / backfill mismatch / range > 31 d; 409
  resolved; 404 foreign event; one history row per change.
- Schema: a clean DB migrates through V12; every category, phase and state value round-trips through the check constraints.

### 7.3 Offline validation (data-gated, report-only)

`GlycemicEventValidationTest` (`-Dazt1d.dir`, `-Dhupa.dir`): hide a random logged meal / bolus and
report per-category precision, recall and median onset error; report false positives per day on
untouched data; on HUPA, check `AEROBIC_ACTIVITY` against step-count activity windows. Report-only,
except a hard gate: **mean prompts per day ≤ 1.0** on untouched data.

### 7.4 iOS (XCTest)

DTO decoding incl. unknown category; marker layout (32 px grouping, no collision with carb chips,
childless pill); `AppState` prompt (hypo precedence, snooze, failed confirm releases controls,
double-tap confirms once). Simulator build per the repo notes. Pre-existing failures (`HypoPromptTests`
×3, unsigned Keychain test) are noted, not attributed.

## 8. Configuration (`app.glycemic-events.*`)

| Key | Default |
|---|---|
| `scan-interval-ms` | 600000 |
| `window-minutes` | 240 |
| `min-readings` | 24 |
| `sigma-multiple` | 2.0 |
| `persistence-minutes` | 45 |
| `max-gap-minutes` | 15 |
| `onset-max-backtrack-minutes` | 60 |
| `match-lead-minutes` | 45 |
| `significant-iob-drop-mmol` | 1.5 |
| `dawn-start-hour` / `dawn-end-hour` | 3 / 8 |
| `dawn-max-residual-slope` | 0.03 |
| `no-effect-ratio` | 0.25 |
| `aerobic-max-minutes` | 90 |
| `anaerobic-max-rise-minutes` / `anaerobic-max-gap-minutes` | 75 / 30 |
| `prompt-min-confidence` / `prompt-max-age-hours` | 0.5 / 6 |
| `learner-k` / `learner-prior-weight` / `learner-override` | 5 / 10 / 0.6 |
| `backfill-max-carbs` / `backfill-max-insulin` | 300 / 50 |

## Future enablers (out of scope)

Injection-site logging (decisive for lipodystrophy); HR/HRV input (stress, exercise); APNs push; labels
feeding the prediction model (e.g. dawn term, stress-window exclusion); a trained classifier replacing
the k-NN learner behind the same interface; retiring the ROC "unlogged meal" alert in
`GlucoseAnomalyDetector`.
