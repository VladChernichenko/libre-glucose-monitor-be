# AI assistant: chat, meal prep and retrieval — design

Date: 2026-10-30. Repos: `glucose-monitor-be` (all logic) and `glucose-monitor-iphone` (chat sheet).
Depends on: `2026-10-30-dashboard-watch-strip-design.md` (per-user `target_glucose_mmol`, stale-data rule).

## 1. Intent

For two weeks (Oct 8–24) Vlad ran his diabetes through a Claude chat, the "Glucose Twin": he wrote
whatever was happening ("3u", a plate photo with a scale reading, "hot shower", "walking"), and the
chat logged it, read Libre, and answered from experience: "same shape as last night's rebound",
"third afternoon in a row climbing", "last time you walked within 2 h of 3 U you went to 70". Every
forecast was scored against what Libre then showed, and a hit percentage told him how far to trust it.

This design brings that into the app. The backend AI today is a one-shot "retrospective" button
(`/api/ai-insights/retrospective`) with a tag lookup over 11 hand-written tips. It becomes a
conversation that remembers.

**The moment that matters most** (Vlad's words): *"prepare me for this bowl of soup with bread."* The
system works out the nutrition, finds similar past meals, and offers a dose and a pre-bolus pause,
with the forecast for that choice. That is the ideal UX; everything else supports it.

## 2. Goals

1. A chat that is always present on the dashboard (a bottom sheet that peeks, drag up for the full,
   scrollable history going back months).
2. **Meal prep:** photo and/or text → fat, protein, fiber and carbs (as ranges) → dose and pause from the
   math model → forecast curve → similar past meals with how they ended → a short explanation.
3. **History check:** when the user's own similar past meals point to a dose more than 20% different
   from the model's, a banner shows both and asks the user to choose.
4. **Honest scoring:** every logged dose/meal keeps the forecast that was made *at that moment*; it is
   later checked against the CGM and a hit percentage (Twin rule) shows in the chat header.
5. **Instant quick logs:** `5u`, `5u 50g`, `5u 50 carbs`, `15g correction`, `18u T` are parsed without
   any AI call and logged with undo.
6. **Questions** ("why did I go high 3 h after pork?") are answered from two sources, the user's own past
   episodes first and a library of real clinical passages second, with tappable citations.
7. The assistant gets better over time because history grows and every episode carries its outcome.
   No retraining.

## 3. Non-goals

- Fine-tuning or training any language model.
- Stored "lessons" or rules the assistant writes for itself (user: noisy, not wanted).
- Push notifications and scheduled follow-up checks by the assistant (push waits for the paid
  developer account). In-app advice that appears on the chat peek (§6.13) is in scope.
- Tresiba (basal) dose advice. Low alarms. The assistant changing any setting.
- Changing how the forecast model works.
- Scaling beyond the current single-instance, small-user deployment.

## 4. Anti-goals: it failed if…

- **The AI invents a number.** Dose, pause, IOB, COB, forecast, history dose and hit % come only from
  backend code. The one exception, as today, is food estimation from a photo or description (vision /
  nutrition services); it is shown as a range and is editable before anything is dosed.
- **It plans from stale data.** No plan, dose or banner when the newest CGM reading is older than 15
  min or LibreLinkUp is unavailable (same rule and code as the watch strip).
- **It disagrees with the dashboard or the watch strip.** COB/IOB, forecast, target and hypo thresholds
  come from the existing single sources.
- **History overdoses.** The banner's higher dose skips the low check, the max-bolus refusal, or the
  below-3.9 refusal.
- **Quick logs wait on the AI.**
- **The banner nags.** It shows only on a real, well-supported difference (§6.6).
- **The hit % flatters.** Re-running old forecasts with today's tuned settings is not the score.

## 5. Decisions made with the user

| Topic | Decision |
|---|---|
| Where the chat lives | Option B: dashboard stays home; chat is a bottom sheet that always peeks; drag up for full history |
| Chat history | Kept and scrollable (months) |
| Numbers | Calculations and forecasts come from the existing math model; the AI analyses past situations and explains |
| Macros | Fat, protein and fiber count, not only carbs (the model already uses all four) |
| Mismatch with history | If history and model differ by more than 20%, show a banner with confirmation; the chosen dose is re-checked for lows |
| Forecast scoring | Compare predictions with real data; show a hit percentage like the Twin |
| Lessons | Not stored |
| Sources | Both the user's own history and clinical sources in this design |
| Tools | pgvector in the existing Postgres; current LLM gateway |
| Embeddings | OpenAI API (`text-embedding-3-small`) for now |
| Provider picker | Dropped: the Auto / Qwen / Ollama picker goes away; the provider is server configuration |
| Pre-bolus pause | One forecast-based pause (the `/api/predict` search), run on the dashboard forecast, used by chat and everywhere else; the GI rule of thumb is retired (§6.12) |
| Experiments tab | Removed entirely, iOS and backend: guided experiments, verification, carb-ratio suggestion (§6.12) |
| Meal planning | Only in the chat; the photo/AR scan moves from the Notes tab into the chat |
| "What caused this?" prompts | Move from dashboard cards into the chat; the low (rescue carbs) prompt stays on the dashboard |
| Falling glucose | Don't wait for a low: advise fast carbs in advance, sized by the speed of the drop (§6.13) |
| Screens | All 12 mockups approved (meal prep, countdown, banner, low re-check, falling carbs, question, prompts, hit %, stale, Notes) |
| Quick logs | `5u`, `5u 50g`, `5u 50 carbs`, `15g correction` (rule-of-15 fast carbs) and similar, without AI |

## 6. How it works

### 6.1 One message, three paths (my call)

Every chat message goes to `POST /api/assistant/messages` (text, optional photo, client time). The
backend routes it:

1. **Quick-log parser** (no AI). Matches the shorthand grammar (§6.3). On a match: create the note(s),
   freeze the forecast (§6.5), reply with a one-line confirmation and the updated state ("Logged 5 U +
   50 g. IOB 5.3 U, lowest 5.8 at +3 h"), plus an Undo action. Target: under 1 s.
2. **Meal prep** when there is a photo, or the LLM classifies the text as "about to eat" (§6.4).
3. **Everything else** → question / free-text event, answered by the LLM with retrieval (§6.8). A
   free-text event the LLM recognises ("hot shower", "coffee with milk", "pen felt weak") is proposed as
   a note with context tags and saved only when the user taps Confirm.

Classification and food-item extraction use one LLM call returning JSON (`intent`, food items with
optional weights, event tags). The chat grows out of the existing `AiInsightService` /
`LlmGatewayService.generateStreamingMarkdown` path (it already takes a follow-up question and
conversation turns and streams), not a parallel stack; the retrospective endpoint and `AIInsightsSheet`
are retired once the chat ships (§6.12). Replies stream as NDJSON events, as the existing retrospective stream
does: `plan` (structured card), `episodes`, `banner`, `token` (AI text), `done`.

### 6.2 Storage

`notes` stays the only log of what happened, so everything logged by chat appears on the dashboard and
in the forecast. Schema rules from `glucose-monitor-be/CLAUDE.md`: columns on existing tables are added
in the owning migration (V1); brand-new tables get a new file. All V1 edits need `flyway repair` on the
dev DB (memory: native Homebrew Postgres).

**`notes` (edit V1), new columns:**
- `carbs_low`, `carbs_high DOUBLE PRECISION` (estimate range; `carbs` stays the value used).
- `context_tags JSONB` (e.g. `["hot_shower","caffeine","alcohol","sex","stress","illness",
  "pen_suspect"]`; the tag vocabulary is a Java enum, unknown tags are dropped).
- `chat_message_id UUID` (the message that created it; nullable, no FK, so notes can outlive chat
  cleanup).
- Fat/protein/fiber stay in the existing `nutrition_profile` JSON; fingerstick stays `glucose_value`;
  activity and site stay as they are.

**New files from `V14` on** (V13 is the experiments/verification drop, §6.12):
- `chat_message` (id, user_id, created_at, role USER|ASSISTANT, text, photo_key, intent, payload JSONB
  for the structured plan/episodes/banner/citations, note_ids JSONB, model_id, latency_ms). Index
  `(user_id, created_at DESC)` for cursor paging.
- `forecast_snapshot` (id, user_id, note_id, made_at, engine, model_version, start_glucose, points JSONB
  every 5 min to +240, then per check minute 60/120/240: actual, hit; `complete` flag). One per anchor
  note (rapid dose, meal ≥ 10 g, fast sugar).
- `meal_episode` (§6.7) with a `vector` embedding column.

**`clinical_knowledge_chunk` (edit V1):** add `embedding vector(1536)` (matches `text-embedding-3-small`;
`meal_episode.embedding` uses the same dimension), `citation TEXT`, `chunk_index
INT`, `source_doc_id VARCHAR(128)`. `CREATE EXTENSION IF NOT EXISTS vector` goes into V1 next to
`pgcrypto`. Requires pgvector on every database: `brew install pgvector` for the dev Postgres 15, a
pgvector-enabled image for docker. Flag this in the plan's first step.

### 6.3 Quick-log grammar

Case-insensitive, Russian and English unit words, comma or space separated. Initial set:

| Input | Result |
|---|---|
| `5u`, `5 u`, `5ед` | rapid dose 5 U now |
| `5u 50g`, `5u 50 carbs`, `5u 50г` | rapid dose + meal 50 g carbs, same time |
| `50g`, `50 carbs` | meal 50 g, no dose |
| `15g correction`, `15g fast`, `15g hypo` | fast-sugar hypo treatment; if a `hypo_events` row is open it is logged through `HypoEventService` against that event (same path as `HypoPromptView`), otherwise as a `hypo_treatment` note, so one low never gets two rescue entries |
| `18u T`, `18u tresiba` | long-acting note (`Note.TYPE_LONG_ACTING`) |
| `... at 14:30`, `... 20 min ago` | backdated |

Doses above the user's max bolus or carbs above 300 g are rejected with a message, not logged. Undo
deletes the created notes and their snapshots. The builder may extend the grammar; anything that does
not match falls through to the LLM.

### 6.4 Meal prep

Input: photo and/or text, optional weights ("bread 40 g").

1. **Nutrition** via the existing `service/nutrition` (vision for photos, enrichment / OpenFoodFacts for
   named items) and the existing iOS scanner: the chat's camera button opens the same capture flow as
   today's `FoodScanSheet` (camera, library, AR volume scan) and sends its result into the chat:
   carbs, fat, protein, fiber, GI. The matched population pattern from `GlycemicPatternMatchingService`
   (Double Wave, Slow Climb, …) is shown on the card as a label only. `NutritionSnapshot` holds one value per macro plus one
   `confidence`; my call: mid = that value, low/high = mid × (1 ∓ w) with w = clamp(0.5 × (1 −
   confidence), 0.10, 0.40). The user can edit the numbers on the card; editing re-runs steps 2–5.
2. **Model dose** via `InsulinCalculatorService.calculateRecommendedInsulin` with mid carbs, the reading,
   `target_glucose_mmol` and IOB from `GlucoseCalculationsService.activeCobIobInputs`. Every existing
   refusal (below 3.9, max bolus, invalid settings) is shown as a refusal, not hidden. The calculator
   doses from carbs; fat, protein and fiber act through the forecast (a fatty meal shows its late rise,
   and the reply says so). My call: no fat/protein units added to the dose here; the history check
   (§6.6) captures meals where that under-doses.
3. **Pause** (reuse, user's decision): the forecast-based search already in `GlucosePredictService`
   (advisory branch: candidates 0–30 min, all four macros plus the protein tail, hypo-weighted cost) is
   the single pause engine. It is moved onto the dashboard forecast (`GlucoseCalculationsService` /
   Hovorka with `prospectiveNotes`: dose at now, meal at now + pause via negative `minutesAgo`), so
   the pause, the card's curve and the dashboard come from one computation. Keep its live-timer branch
   (`PreBolusResolver`): with a pre-bolus already in flight, no new pause is suggested. Whether
   `/api/predict` stays as a thin endpoint over the moved logic or is deleted is the builder's call (no
   client uses it today). Extra rule on top of its cost: never pick a pause whose forecast minimum is
   under 4.5 mmol/L; if all are, offer no pause (eat now) and say why.
4. **Forecast** for the chosen dose and pause: 4 h curve with the likely-range cone, peak, minimum,
   landing, drawn on the card.
5. **Similar meals** (§6.7) and the **history check** (§6.6).
6. **Explanation** by the LLM from a facts payload (§6.8): one to three short sentences. Example: "Bread
   is most of the carbs. 15 min lets the insulin start before it lands. Your last two soup-and-bread
   lunches fit the model's dose."
7. Card actions: **Took it** / **Edit** / **Not eating**. My call on the pause, because the dashboard
   only reads notes up to "now" (`GlucoseCalculationsService` note window), so a future-dated meal note
   would be invisible:
   - **Took it** saves the dose note now and starts the pause countdown on the card (reuse the
     pre-bolus live timer from `2026-07-31-prebolus-live-timer-design.md` where it fits). The planned
     meal is kept on the chat message, not in `notes`.
   - At the end of the pause the card shows **Eating now**; tapping it saves the meal note at that
     moment with the planned macros (editable). No meal note exists until then, so a skipped or late
     meal never leaves a phantom meal in COB.
   - If the meal is not confirmed within pause + 45 min, the card turns amber ("meal not logged; insulin
     is active") the next time the chat is open. No push (non-goal).
   - The forecast snapshot is frozen when the meal note is saved (dose + meal as saved). If the meal is
     never logged, the dose gets its own snapshot as a plain dose anchor.

### 6.5 Frozen forecasts and the hit percentage

- When an anchor note is saved, the backend stores in `forecast_snapshot` the forecast as of the save:
  the same dashboard forecast, with the notes as saved. Engine and model/twin version are recorded. The
  hook must cover every place notes are created: `NotesService` (create/update), chat/quick log,
  `HypoEventService` rescue carbs, `GlycemicEventService`.
- My calls on edge cases, all in the direction of not flattering the score:
  - a note whose timestamp is more than 10 min before the save (backdated) gets no snapshot and is not
    scored (no forecast was made at its time);
  - editing the dose, carbs or time within 15 min of the original save re-freezes; later edits mark the
    snapshot `excluded` (kept for inspection, not counted);
  - deleting the note deletes its snapshot.
- A scheduled job (my call: every 15 min) fills `actual` and `hit` for checks at +60, +120 and +240
  once CGM data exists, using `EmpiricalTwinEngine.isHit` (within 20 mg/dL, or 20% above 100 mg/dL).
  Gaps leave a check empty, not a miss.
- **Hit %** = hits ÷ filled checks at +60 and +120 over the last 14 days (the Twin's confidence
  definition); +240 is shown per meal but not in the headline. Shown in the chat header as "Twin 74%",
  tap for the list of recent anchors with forecast vs actual (reuse `AnchorForecastDTO` shape, but from
  snapshots).
- `forecast_snapshot` is the only user-facing prediction-vs-actual record. `TwinForecastView` switches
  to it (anchors from snapshots). The re-computed `TwinForecastHistoryService` stays only as an
  internal model-tuning tool (no UI). `verification_events` / `verification_summary` are removed with
  the Experiments tab (§6.12); the nightly `DigitalTwinCalibrationService` is unchanged.
- Snapshots start from the day this ships; no backfill (a backfill could only be re-computed, the
  thing this avoids).

### 6.6 History dose and the 20% banner

Computed by backend code, never the LLM.

- **Qualifying episodes:** the top similar meals (§6.7) that have complete CGM for 4 h after, no other
  rapid dose or carbs logged in the 4 h after (or the 30 min before, except the meal's own dose), a
  logged rapid dose, and a reading at the start.
- **Per episode:** first the whole dose that would have landed on target,
  `ideal_i = dose_i + (G_i(+240) − target) / ISF_i`; if the episode went under 3.9, use
  `dose_i − (target − nadir_i) / ISF_i` instead when smaller (the more conservative). Then remove that
  episode's own correction and IOB so only the meal part is left:
  `meal_i = ideal_i − (G_i(0) − target) / ISF_i + IOB_i(0)`, and
  `units_per_gram_i = meal_i / carbs_i` (episodes with `meal_i ≤ 0` are dropped as unreliable).
  `ISF_i` = `UserSettingsDTO.getEffectiveIsf(time of episode)`, the same ISF the calculator and the
  Hovorka forecast price doses with (the twin's `isfScale` does not affect dose action today, and this
  design does not change that).
- **History dose** = median(`units_per_gram_i`) × current carbs + today's correction − today's IOB,
  exactly the terms the calculator applies, rounded to 0.5 U. So a past meal that started high cannot
  raise today's dose through its correction.
- **Banner** when all hold: at least 3 qualifying episodes; |history − model| ÷ model > 20%; absolute
  difference ≥ 0.5 U (my call: avoids 1.0 vs 1.3 U banners).
- **Banner content:** both doses, the episodes behind it ("last 4 soup-and-bread lunches: with ~4 U you
  peaked 215–240 and landed ~170"), both forecast curves, buttons **Use 5 U** / **Keep 4 U**. Neither is
  preselected.
- **Safety on choosing the history dose:** run the forecast for it; if the minimum is under 3.9 within
  4 h, show a red warning and require a second tap; if under 4.5, show an amber note. Max-bolus and
  below-3.9 refusals apply to the history dose exactly as to the model dose. A lower history dose needs
  no extra check.
- **Relation to settings suggestions:** after §6.12 the only settings-change suggestion left is the
  dashboard's per-meal-window ISF banner (`IsfMealWindowSuggestionService`). The history banner never
  changes settings. If an ISF suggestion for the current meal window is pending, the history banner
  says so in one line ("an ISF change for lunch is also suggested on the dashboard"), so the two never
  silently disagree.
- Behind a global feature toggle (`FeatureToggleService` has only global flags), default on. My call:
  the other dev-DB users are AZT1D seed data, so per-user toggling is not needed now. My call: the plan includes an
  offline backtest over past meals (history dose vs model dose, which landed closer to target) and
  reports it, so the toggle decision is informed.

### 6.7 Episodes and similarity search

A **meal episode** is derived from each meal note ≥ 10 g carbs once its 4 h window is complete (job, plus
a one-off backfill over all history). Stored in `meal_episode`:

- **Situation:** local time and weekday, macros (carbs, fat, protein, fiber, GI), food text (meal name,
  comment, detailed input, recognised items), dose and pause taken, start glucose and trend, IOB, COB,
  activity within 2 h before/after (type, minutes), site, context tags.
- **Outcome:** peak and time, nadir and time, G at +60/+120/+240, time under 3.9, time over 10.0, hit
  flags if a snapshot exists, `contaminated` flag (other doses/food inside the window).
- **Embedding** of a short text rendering of the food and context ("lunch, chicken soup with potato, rye
  bread 40 g, after a walk").

**Search** for a new situation: filter to the same user, then score
`0.4 × text similarity (pgvector cosine) + 0.4 × macro distance (normalised carbs/fat/protein/fiber) +
0.2 × situation distance (time-of-day band, start glucose, IOB, activity)`. Return the top 5 for
display; the history check uses the top 10 qualifying. Weights are my starting values; the builder
tunes them on the backtest and records the final ones.

Questions ("why do I go high after pork?") use the same table and also non-meal anchors (corrections,
activity, lows from `hypo_events`) via text embedding plus structured filters the LLM extracts (time
range, food words, event kind).

### 6.8 The LLM's job and its guard

- **Input:** a facts payload (JSON) with the current state, the plan (if any), similar episodes with
  outcomes, banner data, retrieved clinical passages with citation ids, and the last ~10 chat turns.
  Never the full history.
- **Output:** short text in the user's language (Russian or English, matching the message). It may
  explain, compare, and quote numbers **from the payload only**.
- **Guard** (extends `SafetyAndScoringService`): every number in the reply must match a number in the
  payload (allowing rounding and mmol↔mg/dL conversion). On failure regenerate once, then fall back to a
  plain template built from the payload. Imperative dosing phrases not tied to the plan stay stripped.
  The plan card and banner are rendered from structured data, so a bad AI sentence can never change
  the numbers on them.
- `ai_analysis_trace` keeps recording model, latency, evidence ids per reply.

### 6.9 Clinical library

- Replace the 11 hand-written rows with chunked passages from ADA Standards of Care and ISPAD guideline
  sections relevant to MDI, plus selected PubMed abstracts (exercise and insulin, fat/protein dosing,
  alcohol, pre-bolus timing, hypo treatment), each with title, source, year, URL/DOI and a display
  citation. Keep the existing GENERAL safety disclaimer row.
- A script in `scripts/` (my call) fetches, chunks (~300–500 words), embeds and upserts, idempotent by
  `source_doc_id + chunk_index`. Run by hand; no live fetching per question. Licence check per source:
  where full text cannot be stored, store the abstract or a short summary with the link.
- Retrieval: embed the question (plus a rendering of the current situation), cosine top-k with k = 4,
  only `active` rows. Citations appear as tappable chips under the reply.
- Clinical passages never set a number; the guard treats their numbers as quotable background only
  when the reply attributes them to the source.

### 6.10 Models and embeddings (user's decisions)

- Chat: the existing `LlmGatewayService` (Ollama / OpenAI-compatible). The provider is chosen only by
  server configuration; the new assistant endpoint takes no provider/model override, and the iOS
  Auto / Qwen / Ollama picker is removed (user's decision).
- Embeddings: a new `EmbeddingGateway` interface with one implementation calling the OpenAI
  `/v1/embeddings` API, model `text-embedding-3-small`, 1536 dimensions (user's decision), configured in
  `application.yml` with its own API key property (env var, never in source). Embedding text sent to
  OpenAI is limited to food/context renderings and questions, no user identifiers. Changing model later
  means a V1 edit and re-embedding (script flag). If the embedding call fails, retrieval falls back to
  structured scoring only (macro + situation distance) and the reply still works.
- Vision for meal photos: existing `NutritionVisionService` / YOLO path.

### 6.11 iOS chat sheet

- A custom bottom overlay on the Dashboard tab (not a system `.sheet`: the app targets iOS 16.0, an
  always-open system sheet would block the ~10 sheets `DashboardView` already presents, including the
  hypo-event sheet, and touching the dashboard behind a peek needs iOS 16.4). Always visible as a peek
  (input bar + last message line), dragged up for the full chat; existing sheets present above it. It replaces the `AIInsightsSheet` entry point; "how was my day"
  becomes an ordinary question. The provider picker is not carried over.
- **Glycemic event prompts in the chat** (user's decision): instead of `GlycemicEventPromptCard` on the
  dashboard, each prompt the engine selects appears as a quiet assistant message ("Unexplained rise
  14:10–15:00, +3.2. Anything to log?") with the same answer buttons (category, carbs, insulin, Not
  sure, Nothing), calling the existing `GlycemicEventService` confirm/dismiss endpoints. No pop-up;
  the peek line shows it. A free-text answer goes through the normal chat path and, if it logs a note,
  confirms the event. The prompt-selection and per-day gate logic is unchanged. The low prompt
  (`HypoPromptView`) stays on the dashboard.
- Header in the sheet: current reading, IOB/COB (from the same dashboard calculation) and hit %.
- Message list pages backwards with a cursor (`GET /api/assistant/messages?before=…&limit=50`), keeps
  position, and renders: user text/photo, assistant text (streamed), plan card with forecast curve,
  episode cards, banner, citation chips, Undo and Confirm actions.
- Input: text field, camera/photo picker. Glucose shown in the user's unit; server values are mmol/L.
- New Swift files must be added to `GlucoseMonitor.xcodeproj/project.pbxproj` by hand.

### 6.13 Fast carbs in advance (user's decision: no waiting for lows)

Computed by backend code on the dashboard forecast; the LLM is not involved.

- **Where it lives:** a `carbAdvice` object on `GlucoseCalculationsResponse` (the response the app
  already fetches every refresh, where the watch strip's `watch` object also goes), so the dashboard,
  the watch strip and the chat show one computation. The chat renders it as a pinned red card (mockup
  screen 7) and the peek line shows its headline. **The watch strip's URGENT_LOW carb suggestion must
  use this same computation instead of its own `carb_ratio` formula** (see the note to the watch-strip
  work), so the two never disagree.
- **Trigger** (my starting values, tunable constants): fresh CGM (15-min rule) and either
  - the forecast minimum within the next 60 min is under 4.5 mmol/L, or
  - the trend is falling at ≥ 0.1 mmol/L/min (≈ 1.8 mg/dL/min) and the forecast minimum within 90 min is
    under 5.0.
  The forecast already includes trend (`currentTrendMmolPerMin`), IOB, COB and activity.
- **Grams:** re-run the forecast with a prospective fast-sugar note (rescue profile,
  `hypo_treatment`) taken now, for 5, 10, 15 … 40 g; pick the smallest amount whose forecast minimum over
  the next 2 h is ≥ 5.0 mmol/L (90 mg/dL). If 40 g is not enough, advise 40 g and "recheck in 10 min".
  A faster drop or more insulin on board gives a lower forecast and so more grams. No `rescue_carbs_g`
  floor here (early advice may be 5–10 g); the floor stays for an actual low (`HypoPromptView`).
- **Content:** headline ("Falling fast — take 15 g now"), forecast minimum and time without carbs,
  minimum with the advised grams, both curves, recheck time (now + 15 min), buttons **Took 15 g** (logs
  through the quick-log fast-sugar path, attaching to an open hypo event if any) and **Took other…**.
- **No stacking:** within 15 min after any logged fast carbs (or rescue) no new grams are advised; the
  card says "the 15 g is still landing, recheck at 19:20" instead. After 15 min the advice re-evaluates
  with those carbs in COB.
- **No flicker:** once shown, the advice stays until two consecutive new CGM readings no longer meet
  the trigger (same hold rule as the watch strip, counted per stored reading).
- **Under 3.9 now:** the existing low flow (`HypoPromptView`) takes over; `carbAdvice` is not shown on
  top of it.

### 6.12 Removals and moves (user's decisions)

| What | Action |
|---|---|
| Experiments tab (iOS `Experiments/*`: guided ISF / carb-ratio / basal experiments, alarms, background check, verification views, ISF meal-window chart, past results) | Delete the tab and all its files (and their `project.pbxproj` entries) |
| Backend: `ExperimentService`, `ExperimentController`, `VerificationService`, `VerificationController`, related DTOs/entities/repositories/schedulers, the verification enqueue hook in note saving | Delete |
| Tables `experiments`, `experiment_readings` (V5), `verification_events`, `verification_summary` (V6) | Remove from V5/V6 per the schema rules, plus an explicit `DROP TABLE IF EXISTS` for databases that already have them (my call: in its own new migration, `V13__drop_experiments_and_verification.sql`, since removals ship first; the chat tables then start at V14; editing an applied migration never drops anything). Removing experiments is forecast-neutral: `HovorkaParameterService.estimateEgpNet` only lowers egpNet when a basal check averaged under 4.5 mmol/L, and the only one in the dev DB averaged 5.49, and `flyway repair` on the dev DB |
| Pre-bolus rule of thumb (`GlycemicPatternMatchingService.computePreBolusPause`, `NutritionSnapshot.preBolusPauseMinutes`, the editor's GI-based reason texts) | Retire; the forecast-based pause replaces it. The GI/strategy wording may survive as the reason line next to the forecast pause |
| Photo/AR scan entry in the Notes tab (`FoodScanSheet` path) | Move into the chat; the Notes tab keeps manual entry and editing |
| `AIInsightsSheet`, provider picker, `/api/ai-insights/retrospective*` | Retire once the chat ships |
| `GlycemicEventPromptCard` on the dashboard | Move into the chat (§6.11) |
| Kept as is | Dashboard ISF meal-window suggestion banner, `HypoPromptView`, nightly twin calibration, injection-site picker, `PreBolusTimer` |

Before deleting, the builder checks every caller (e.g. `ExperimentService` uses `InsulinCalculatorService`
and COB/IOB inputs; `ContextAggregatorService` may read experiment or verification data) and that no
other screen links into the Experiments views.

## 7. Constraints from the existing code

- Backend glucose is mmol/L end to end; mg/dL is display only.
- COB/IOB only from `GlucoseCalculationsService.activeCobIobInputs` (persisted notes); prospective notes
  are forecast overlays only.
- Hypo thresholds only from `service/observer/HypoThresholds`.
- `user_settings.carb_ratio` is a glucose-rise coefficient (mmol/L per 10 g), not g/U; the calculator's
  `resolveGramsPerUnit` is the g/U source.
- One correction target everywhere: `target_glucose_mmol` from the watch-strip design (replaces
  `CORRECTION_TARGET_MMOl = 6.5` in `ContextAggregatorService`). Neither that setting nor the 15-min
  freshness check exists yet; if the watch strip is not built first, phase 1 builds both, as specified
  there, and the watch strip reuses them.
- Unlogged food and insulin are common (memory: data-integrity-gaps): the `contaminated` flag and the
  "at least 3 episodes" rule limit the damage; nothing tries to infer unlogged doses.
- Keep files under 500 lines; mock-first unit tests.

## 8. Phases (each shippable)

0. **Removals** (independent, can go first): Experiments tab and backend, verification, V5/V6 tables.
1. **Chat foundation:** `chat_message`, endpoints, iOS sheet with paging and photo input, quick-log
   parser with undo, questions answered with current context (existing tag RAG still behind it).
   Glycemic event prompts move into the chat; retire `AIInsightsSheet`.
2. **Frozen forecasts + hit %:** `forecast_snapshot`, save hook on every anchor note, check job, header %
   and anchor list; `TwinForecastView` reads snapshots.
2b. **Fast carbs in advance** (§6.13): `carbAdvice` on the calculations response, chat card and peek.
3. **Meal prep:** scanner moved into the chat, nutrition card with editable ranges, model dose, the
   forecast pause moved onto the dashboard forecast (rule of thumb retired), forecast curve, Took it /
   Eating now.
4. **Episodes + similarity:** pgvector, `meal_episode`, backfill, search, episode cards, LLM facts
   payload with episodes, number guard.
5. **History dose + banner:** computation, banner, low re-check, toggle, backtest report.
6. **Clinical library:** ingestion script, embeddings, citations, retire the tag lookup.

## 9. Testing

- Parser: table-driven tests for every grammar row, Russian/English units, rejections, backdating, undo.
- Pause search, history dose (per-episode formula, nadir rule, scaling, rounding, ≥3 episodes, 20% and
  0.5 U thresholds), low re-check, stale-data refusal: mock-first unit tests.
- Snapshot job: hit/miss/gap cases, 14-day window, +240 excluded from headline.
- Similarity: fixture episodes where the expected nearest neighbours are known.
- Number guard: replies with an invented number are regenerated / replaced by the template.
- E2E (prediction-e2e-tests skill style): meal prep through `POST /api/assistant/messages` returns a
  plan whose dose equals the calculator's and whose curve equals the dashboard forecast for the same
  prospective notes.
- iOS: decoding of every NDJSON event type, paging keeps scroll position, unit formatting.

## 9b. Screens

The approved mockups (12 screens with example content) are in
`docs/superpowers/specs/assets/2026-10-30-assistant-screens.html`. Layout and content follow them;
exact styling is the builder's.

## 10. Left to the builder

Endpoint and DTO names; NDJSON event field names; exact card layout within the mockups (meal-prep
screen, banner); snapshot job interval; similarity weight tuning (record final values); chunk size;
grammar extensions; how context tags are shown on the dashboard (if at all).
