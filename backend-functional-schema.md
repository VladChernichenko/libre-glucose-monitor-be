# Backend functional schema

- Source: `glucose-monitor-be` — Spring Boot 3.5.5 / Java 21, package `che.glucosemonitorbe`
- Persistence: PostgreSQL + Flyway `V1`–`V10`; optional MongoDB (Open Food Facts cache)
- Build: Gradle; 366 main classes, 105 test classes; JaCoCo 80 % line-coverage gate on the physiology + dosing classes
- Auth: stateless JWT (HS512), BCrypt passwords, per-IP auth rate limiting

## Mental model

The backend is an **event-driven physiology engine** wrapped in a REST API:

- **CGM readings are ground truth** — pulled from Nightscout or LibreLinkUp into one shared cache.
- **Notes are interventions** — meals, boluses, long-acting basal, activity.
- **Hovorka (2004) + Dalla Man gut** is the forward ODE model; an OpenAPS-style exponential path is the fallback.
- **The digital twin** is a thin per-user personalisation layer over that ODE (parameter scales + hourly residual bias + uncertainty band), fitted nightly and applied **only to predictions, never to dosing**.
- **Feature flags** can make every learning / activity / detection path inert without deleting data.

A hard invariant runs through the whole codebase: *display paths may substitute population defaults; dosing paths refuse instead of guessing.*

---

## 1. Layered architecture

Requests never jump from a controller into the ODE. Application services own transactions, feature gates, and which notes/CGM window is loaded; `hovorka/*` stays pure math.

| Layer | Package | Responsibility |
|---|---|---|
| API | `controller/` (26) | REST endpoints, principal resolution, DTO in/out. No physiology math. |
| Application | `service/` (27 + subpackages) | Use-case orchestration, transactions, feature gates |
| Physiology | `hovorka/` (14) | Hovorka ODE, gut models, basal/EGP, activity modulation, RK4 solver |
| Learning | `hovorka/learning/` (12) | LM parameter fit, residual grid, uncertainty band, replay engine |
| Persistence | `entity/`, `domain/`, `repository/` (20 repos) | JPA over Postgres; Spring Data Mongo for OFF |
| Integrations | `service/libre/`, `nightscout/`, `ai/`, `service/nutrition/`, `storage/` | LibreLinkUp, Nightscout, Ollama/Qwen, YOLO, LogMeal, OFF, S3/MinIO |
| Platform | `config/`, `security/`, `scheduler/`, `circuitbreaker/`, `exception/` | JWT, rate limit, CORS, caches, cron jobs, resilience, error mapping |

```
Controller
  → FeatureToggleService (gate)
    → Application service (transaction, window selection)
      → CarbsOnBoardService / InsulinCalculatorService
      → HovorkaGlucosePredictionService
           → HovorkaParameterService ← DigitalTwinService (scales)
           → HovorkaOdeSolver + DallaManGutModel + BasalInsulinResolver
           → NotesActivityProvider → ActivityModulation
           → PredictionResidualProvider (twin residual + σ)
      → Repositories → Postgres | external clients
```

---

## 2. Complete API surface

All endpoints require a JWT bearer token except `/api/auth/**`, `/api/features/**`, `/api/version/**`, `/actuator/health/**`, `/actuator/info`, `/error`, `/health`.

### Identity & platform

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/auth/login` | Authenticate, issue access + refresh tokens (rate limited) |
| POST | `/api/auth/register` | Create account (rate limited) |
| POST | `/api/auth/refresh` | Exchange refresh token for a new access token |
| POST | `/api/auth/logout` · `/logout-current` · `/logout-all` | Revoke token(s) via durable blacklist |
| GET | `/api/auth/test` | Auth smoke test |
| GET | `/api/users/me` | Current user profile |
| GET | `/api/version/` · `/compatibility-matrix` · `/health` | Version + client-compatibility metadata |
| POST | `/api/version/check-compatibility` | Validate a client version against the matrix |
| GET | `/api/features/status` · `/check/{feature}`; POST `/toggle/{feature}` | Feature-flag inspection/toggle |
| GET | `/api/circuit-breaker/stats` · `/stats/{svc}` · `/health`; POST `/reset/{svc}` · `/reset-all` | External-call resilience state |
| GET | `/api/test/health` · `/status` | Liveness helpers |

### Notes (the event log)

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/notes` · `/range` · `/today` · `/summary` · `/{id}` | Read notes by window/id; aggregate summary |
| POST | `/api/notes` | Create meal / bolus / basal / activity note |
| PUT · DELETE | `/api/notes/{id}` | Update / delete |
| POST · GET | `/api/notes/{id}/photo` | Upload / fetch meal photo (S3-compatible, multipart) |
| GET | `/api/notes/health` | Module health |

### Calculation & prediction

| Method | Path | Purpose |
|---|---|---|
| GET · POST | `/api/glucose-calculations/` | Dashboard: headline COB/IOB + 2 h/4 h/8 h forecast + full prediction path |
| GET | `/api/glucose-calculations/status` | Module status |
| POST | `/api/predict` | Meal what-if: pre-bolus timing optimiser, always Hovorka |
| POST | `/api/cob/calculate` · `/status` · `/timeline` | Carbs-on-board decay |
| POST | `/api/insulin/calculate` · `/active-insulin`; GET `/status` | Bolus recommendation + IOB curve |
| GET | `/api/insulin-catalog` | Rapid / long-acting insulin PK catalogue |

### CGM data sources

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/nightscout/entries` · `/entries/current` · `/entries/date-range` · `/chart-data` · `/health` | Nightscout pull + stored chart data |
| DELETE | `/api/nightscout/chart-data` | Purge cached chart data |
| POST | `/api/libre/auth/login` · `/sync-now` | LibreLinkUp auth; on-demand sync |
| GET | `/api/libre/connections` · `/{patientId}/graph` · `/current` · `/history` · `/sensor` | LibreLinkUp reads |
| POST/GET/DELETE | `/api/user/data-source-config/**` | CRUD + activate/deactivate/test per-source credentials |

### Personalisation & learning

| Method | Path | Purpose |
|---|---|---|
| GET · POST · PUT · DELETE | `/api/user-settings` (+ `/exists`) | CR, ISF, half-life, weight, per-window ISF, timezone |
| GET · PUT | `/api/user/insulin-preferences` | Chosen rapid + long-acting insulin, basal injection time |
| GET | `/api/digital-twin`; POST `/recalibrate` | Twin status; force a re-fit |
| GET | `/api/isf/meal-windows` · `/suggestion`; POST `/recompute` · `/suggestion/accept` · `/suggestion/dismiss` | Observational per-window ISF + morning suggestion banner |
| GET | `/api/unlogged-events`; POST `/{id}/confirm` · `/{id}/dismiss` | Unexplained-glucose flags |
| GET/POST | `/api/experiments/**` (`/available`, `/check-background`, `/{id}/reading`, `/complete`, `/abandon`) | ISF/CR/basal determination protocols |
| GET | `/api/experiments/verification/summary` · `/events`; POST `/accept-suggestion` | Real-meal accuracy loop |

### Nutrition & AI

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/nutrition/analyze` · `/analyze-ar` · `/analyze-image` | Text / AR / photo meal analysis |
| GET | `/api/nutrition/product/{barcode}` · `/search` | Open Food Facts lookup |
| POST | `/api/ai-insights/retrospective` · `/retrospective/stream` (NDJSON) | LLM retrospective analysis, streaming |

---

## 3. Database schema (Flyway is the source of truth)

| Migration | Tables |
|---|---|
| `V1__baseline_schema` | `users`, `user_settings`, `notes`, `cgm_readings`, `user_data_source_config`, `insulin_catalog`, `user_insulin_preferences`, `user_glucose_sync_state`, `clinical_knowledge_chunk`, `ai_analysis_trace`, `glycemic_response_patterns`, `token_blacklist` |
| `V2`–`V4` | Seed data: insulin catalog, glycemic response patterns, clinical knowledge |
| `V5__experiments` | `experiments`, `experiment_readings` |
| `V6__verification_events` | `verification_events`, `verification_summary` |
| `V7__isf_meal_window_snapshots` | `isf_meal_window_snapshots` |
| `V8__user_digital_twin` | `user_digital_twin` |
| `V9__unlogged_event_flags` | `unlogged_event_flags` |
| `V10__isf_meal_window_suggestions` | `isf_meal_window_suggestions` |

Conventions: UUID PKs via `gen_random_uuid()`; all timestamps `TIMESTAMPTZ` (UTC); every user-scoped table `FK users(id) ON DELETE CASCADE`; `CREATE … IF NOT EXISTS` throughout; `ddl-auto: validate` so Hibernate never writes DDL.

### Key tables

**`user_settings`** — the single source of truth for dosing parameters. `carb_ratio` (mmol/L rise per 10 g), `isf` (mmol/L per U), `carb_half_life` (min), `max_cob_duration`, `body_weight_kg`, four optional per-window ISF overrides (`isf_breakfast|lunch|dinner|night`), plus `timezone` (IANA, authoritative) and `utc_offset_minutes` (fallback). CHECK constraints enforce positivity on every rate.

**`notes`** — the intervention log. `carbs`, `insulin`, `meal` label, `glucose_value`, `nutrition_profile` (JSON), `absorption_mode`, `type` (`normal` / `activity`), `photo_key`, and activity fields (`activity_type`, `intensity`, `duration_min`). Indexed on `(user_id, timestamp)`.

**`cgm_readings`** — one shared cache for both sources, discriminated by `data_source ∈ {NIGHTSCOUT, LIBRE_LINK_UP}`. Two partial unique indexes handle dedup: by `(user, source, external_id)` when the upstream supplies an id, else by `(user, source, date_timestamp)`.

**`user_digital_twin`** — one row per user: `isf_scale`, `ag_scale`, `tmax_g_scale` and `egp_scale` (the last two reserved), a 24-value comma-separated `residual_grid`, a 4-value `uncertainty_sd_grid`, the `applied` gate flag, and fit diagnostics (`mae_baseline`, `mae_calibrated`, `improvement_pct`, `train_samples`, `val_samples`, `confidence`, `status`, `fitted_at`).

**`user_data_source_config`** — per-user Nightscout/Libre credentials, encrypted at rest (AES-256-GCM, `enc:v1:` prefix, `EncryptedStringConverter`). A partial unique index enforces at most one active config per `(user, source)`.

---

## 4. The prediction stack

### 4.1 Dashboard path — `GlucoseCalculationsService`

1. Resolve "now" from the **client's** `ClientTimeInfo`, not server time. If the client's zone/offset differs from what's stored, persist it (write-on-change only, so the 30 s dashboard poll stays read-only).
2. `activeCobIobInputs(userId, now)` — the **single shared** builder for an ~8 h note window → carb entries (nutrition-aware) + rapid insulin doses (long-acting excluded from bolus IOB) + settings + rapid IOB params. `ExperimentService.checkBackground` calls the exact same method, so the dashboard and Experiments tab can never disagree on active COB/IOB.
3. Headline COB/IOB is computed from **persisted notes only**. Prospective "what-if" notes are an overlay that shapes the prediction path but never the headline numbers.
4. Build the prediction path:
   - **Hovorka branch** (flag on): `HovorkaGlucosePredictionService.buildPredictionPath`, with a 36 h long-acting window and an `ActivityProvider` from recent activity notes.
   - **OpenAPS fallback**: per-step Δ from COB/IOB deltas, with per-step ISF resolved for that step's meal window, plus a momentum blend `e^(−t/30)` anchoring short horizons to measured CGM velocity.
5. Emission: 5-min steps to 240 min, 10-min steps out to 480 min (the extended tail is used for HFHP / Dual-Wave meals).
6. Headline `twoHourPrediction`, `fourHourPrediction`, `eightHourPrediction` and the trend chip are all read **off the same path** the chart renders — a deliberate fix so the headline can never contradict the curve.

### 4.2 The ODE

State: `Q1`, `Q2` (glucose compartments), `Qsto1`, `Qsto2`, `Qgut` (Dalla Man 3-compartment gut), `Inc` (GLP-1 activation), `x3` (EGP suppression), `ProtFatGut`. Integrated with RK4 at 1-minute resolution.

```
G      = Q1 / VG
F01_c  = f01 × min(1, G/4.5)
kempt  = K_MIN + (K_MAX−K_MIN)/2 × {tanh[α(Qsto−b·D)] − tanh[c(Qsto−d·D)] + 2}
Ra     = F × K_ABS × Qgut                       (F = 0.90 bioavailability, applied once)
dQ1/dt = −F01_c − k12·Q1 + k21·Q2 + Ra + EGP(t) − insulinEffect − renalClearance
EGP(t) = egp0 × max(0, 1 − x3(t))
```

Population constants (Hovorka 2004): `k12 = k21 = 0.066 /min`, `F01 = 0.0097 mmol/kg/min`, `EGP0 = 0.0161 mmol/kg/min`, `VG = 0.16 L/kg`, default weight 70 kg. `tMaxG = carbHalfLife / 1.68`.

| Signal | How it enters the model |
|---|---|
| Carbs | Dalla Man gut chain → appearance rate `Ra(t)`; GI scales `k_abs/k_gri/k_max/k_min` by `clamp(GI/100, 0.3, 1.5)` |
| Rapid insulin | OpenAPS exponential IOB **activity rate** → `insulinEffect = ISF × 2·VG × rate`. Deliberately bypasses the S1→S2→I_plasma chain to preserve exact IOB pharmacokinetics. |
| Long-acting | `BasalInsulinResolver` (28 h DIA, wane from 20 h) → `x3` suppression of EGP over a 36 h lookback |
| Protein/fat | Drive `ProtFatGut` → GLP-1 `Inc ∈ [0,1]` → ileal brake `Φ = 1/(1+Inc)`, halving gastric emptying at most |
| Activity | `a(t) ∈ [0,1]` → insulin-sensitivity gain `(1 + 1.0·a_sens)` with a 120-min post-exercise tail, plus insulin-independent clearance `0.005·a_inst /min` |
| Renal | `FR = 0.003 × (Q1 − 9.0×VG)` above 9 mmol/L |
| Digital twin | Multiplies `isf`/`aG`; adds the hourly residual (ramped in linearly over the first 30 min from the anchor); widens the band by σ |

### 4.3 Meal what-if path — `POST /api/predict`

Always Hovorka. Loads 8 h of history, overrides `tMaxG` via `MacroNutrientGastricModel` (Elashoff + fiber viscosity), then branches:

- **Live-timer branch** — a logged pre-bolus is already in flight (`PreBolusResolver`, 2 h max age). The dose is already in history and isn't re-added; no optimisation runs; the response reports `observedPreBolusMinutes`.
- **Advisory branch** — simulates candidate pauses `{0, 5, 10, 15, 20, 25, 30}` min, each over `horizon + pause`. Score = trapezoidal time-weighted mean of hypo-weighted squared deviation from 5.5 mmol/L, with a 50× quadratic penalty below 3.9 and a 2× weight on the low side of target. The winning pause is returned as `preBolusMinutes` together with **that candidate's own curve**.

Protein gluconeogenesis is added as a secondary slow-carb entry at +120 min (0.40 g carb-equiv per g protein, ≥ 2 g threshold).

---

## 5. Digital twin (learning loop)

Nightly at 03:15 (`DigitalTwinCalibrationScheduler`), or on demand via `POST /api/digital-twin/recalibrate`, or as a one-shot backfill under the `recalibrate-cli` profile.

**Inputs:** ~30 days of CGM + notes; users with < 200 CGM readings are skipped. 80 % train / 20 % temporal holdout. Seed (AZT1D dataset) users are excluded. Open unlogged-event flags down-weight the affected windows.

**`PredictionReplayEngine`** — open-loop replay: at each anchor `t0` (30-min stride, max 250 anchors), feed the predictor the inputs it would have had plus the events that actually occurred, then compare to the real CGM trace at 30/60/90/120 min. Per-anchor input assembly happens once in the constructor; each optimiser iteration re-runs only the ODE. Replay uses the **raw** model (`PredictionResidualProvider.NONE`) so it measures physiology, not the correction it's about to fit.

**`DigitalTwinCalibrator`** — two-stage robust fit:

| Stage | Fits | Method |
|---|---|---|
| 1 · BASAL_CHECK | `egpScale` on fasting anchors only (≥ 20 required) | LM, sensitivity held neutral |
| 2 · Sensitivity/meal | `isfScale`, `agScale` over all anchors, EGP fixed | Levenberg-Marquardt (Apache Commons Math) wrapped in IRLS with Huber weights (δ = 1.345, 4 passes) |
| 3 · Residual | `ResidualBiasModel` — 24 hour-of-day additive corrections | Empirical-Bayes shrinkage (k = 12 per hour, k = 30 global), clamped ±2.5 mmol/L |
| 4 · Uncertainty | `PredictionUncertaintyModel` — σ at 30/60/90/120 min | Variance shrinkage (k = 20), floor 0.3, cap 6.0, forced monotone, √t extrapolation beyond 120 min |

Tikhonov ridge (λ = 0.05) pulls every scale toward 1.0; hard clamps keep parameters physiological; LM's `parameterValidator` projects each trial vector back into bounds.

**Apply gate:** `applied = true` only if out-of-sample MAE beats the uncalibrated baseline by ≥ 2 % with ≥ 30 validation samples. Otherwise the row is stored with `applied = false` for observability and predictions are unchanged.

**Read side:** `DigitalTwinService` caches the resolved twin for 60 s (invalidated on recalibration) so the per-point residual lookup never hits the DB on the hot path. Users without an applied twin still get the **population-prior** uncertainty band (0.8 / 1.4 / 1.9 / 2.3 mmol/L).

---

## 6. Unlogged-event detection

`UnloggedEventScanScheduler` every 20 min → `UnloggedEventDetectionService.scanAllRealUsers()`:

1. Run the **raw** Hovorka model over a 180-min CGM window (8 h warm-up lookback).
2. Find a sustained same-sign residual exceeding an adaptive robust-σ threshold.
3. Classify: `UNLOGGED_FOOD` / `UNDER_ESTIMATED_FOOD` / `UNLOGGED_INSULIN` / `UNDER_ESTIMATED_INSULIN`, with `direction ∈ {RISE, FALL}`.
4. Persist an `unlogged_event_flags` row in state `OPEN`, deduped against any existing open flag for the window.
5. The user confirms (optionally backfilling a note) or dismisses via `/api/unlogged-events/{id}/…`.

Notes are matched on the **user's own clock** (recent fix), and activity notes reduce residuals through the ODE rather than being mistaken for unlogged insulin.

---

## 7. Experiments and the verification loop

### Protocols

| Type | Purpose | Min elapsed before a result is accepted |
|---|---|---|
| `BASAL_CHECK` | Fasting flatness → EGP₀ estimate | 180 min (target 4–6 h) |
| `CARB_FACTOR` | Carb-ratio identification | 60 min |
| `ISF_ONE_UNIT` | Sensitivity from a 1 U correction | 180 min (target 4–5 h) |

Readiness ("clean background") requires COB < 5 g **and** IOB < 0.3 U, computed from the shared dashboard inputs. If not clean, the service forward-samples the decay curves to estimate minutes until it will be. Lifecycle: `PENDING → IN_PROGRESS → COMPLETED | ABANDONED`, with timestamped `experiment_readings` along the way.

### Real-meal verification

`VerificationScheduler` every 15 min → `VerificationService.evaluatePending()`:

- Every created note is enqueued as a `PENDING` verification event (fire-and-forget; a failure never blocks the note save).
- After the 2 h window elapses, fetch the actual CGM value, compute `predicted_delta` vs `actual_delta`, error and relative error.
- Refresh a rolling 7-event summary per user: `mean_error`, `consistency_score`, `suggested_carb_ratio`.
- A suggestion surfaces only when mean error > 0.5 mmol/L, consistency ≥ 0.60, and mean predicted rise ≥ 0.5 mmol/L.
- **Accepting one suggestion may move `carb_ratio` by at most ±25 %**, re-bounded against the carb ratio in force at apply time. `boundedCarbRatioStep` is total for non-finite input (returns the input unchanged rather than letting `Math.round(NaN) == 0` collapse the value).

### Observational ISF by meal window

`IsfMealWindowScheduler` daily at 02:00 UTC, plus an on-bolus refresh from the note-creation path. For each bolus B: window `[t_B, t_B + DIA]`, subtract the expected carb rise, attribute the residual drop to insulin, weight 1.0 for correction boluses and 0.4 for meal-attached ones, bucket into BREAKFAST (05–11) / LUNCH (11–16) / DINNER (16–22) / NIGHT (22–05). 14-day lookback; buckets below 7.0 weighted samples return `null` so the UI can show a "run an ISF experiment" CTA. Per-event estimates outside 0.3–10.0 mmol/L/U are dropped as noise.

`IsfMealWindowSuggestionService` gates the morning banner: twin fitted, ≥ 2 windows with data, ≥ 0.15 mmol/L/U material change, and no accept/dismiss in the last 3 days.

---

## 8. CGM ingestion

| | Nightscout | LibreLinkUp |
|---|---|---|
| Scheduler | `NightscoutGlucoseSyncScheduler` | `LibreLinkUpGlucoseSyncScheduler` |
| Cadence | fixed delay 300 s (15 s initial) | fixed delay 300 s (20 s initial) |
| Concurrency | 8-thread bounded pool, 4-min tick budget | 8-thread pool, 240 s budget, per-user `ReentrantLock` |
| Adaptive backoff | `user_glucose_sync_state`: 5-min fast / 60-min slow interval driven by `consecutive_no_change_count` | same state table; `sync-now` forces past the backoff |
| Safety | `NightscoutUrlValidator` SSRF guard (scheme, credentials-in-URL, private/link-local/loopback/metadata after DNS resolve) — enforced both on save and before each fetch | `LibreLinkUpRegionResolver` probes EU → FR → US → AP → JP → AE on 403/430 (never on 429) |

Both funnel into `CgmReadingService.storeChartData(userId, entries, dataSource)`, which normalises to `NightscoutEntryDto`, dedupes within the batch and against the DB, and inserts only new points under `READ_COMMITTED`.

LibreLinkUp specifics: transport in `LibreLinkUpClient` (client version `4.16.0`, gzip/BOM-aware parsing), per-user session state (token, resolved base URL, SHA-256 account-id header, locale) in an in-memory `LibreLinkUpSessionStore` — per-instance, not shared across replicas.

---

## 9. Nutrition pipeline

`NutritionEnrichmentService` (text) → food-token extraction → GI/fat/protein/fiber estimation → fat/protein dampening (`fat×0.30 + protein×0.20`, capped at 20 GI units, floor 15) → `NutritionSnapshot`.

`GlycemicPatternMatchingService` matches the snapshot against `glycemic_response_patterns` in two specificity passes:

- Pass 1 (fat/protein-constrained, duration DESC): Double Wave (8 h) → Flat Plateau (5 h) → Moderate FPU (4 h) → Light FPU (3 h). Fiber-barrier patterns are always checked here first.
- Pass 2 (GI-only): Slow Climb (3.5 h) → Fast Spike (2.5 h).

The match sets `bolusStrategy ∈ {Normal, Extended, Dual Wave}` and `suggestedDurationHours`, which feeds back into COB duration and the 4 h vs 8 h path length.

Vision/lookup providers, all optional and feature-gated: **LogMeal** (segmentation → nutritional info), **YOLO** local microservice (port 8001, confidence 0.35), **Ollama/Qwen vision** (structured-JSON prompt), **Open Food Facts** (barcode + text search, 7-day Caffeine cache, GI estimated from category taxonomy).

---

## 10. AI insights

`AiInsightService` runs a four-stage pipeline: `ContextAggregatorService` (CGM window, notes, COB/IOB, 2 h prediction, pre-bolus pause) → `RagRetrieverService` (tags the context — HYPO/HYPER/RISING/FALLING/POST_MEAL/IOB/COB/PRE_BOLUS/CORRECTION/PREDICTION_2H — and retrieves ≤ 8 `clinical_knowledge_chunk` rows) → `LlmGatewayService` (Ollama primary, Qwen/OpenAI-compatible secondary, with a static markdown fallback when both are unavailable) → `SafetyAndScoringService` (deterministic patterns/recommendations, confidence scoring, mandatory "educational support only, not a dosing instruction" disclaimer).

Every run is recorded in `ai_analysis_trace` with a SHA-256 context hash, model id, evidence chunk ids, confidence and latency. Streaming is served as NDJSON.

---

## 11. Alerting (observer)

`GlucoseAnomalyDetector` runs on the CGM sync cadence (5 min, `app.observer.interval-ms`). Rate of change is a least-squares slope over the last ~3 readings (~15 min).

| Scenario | Trigger |
|---|---|
| Predicted hypo | path point < 3.9 mmol/L within 60 min |
| Rapid drop | ROC < −0.07 mmol/L/min for ≥ 2 consecutive readings |
| Unlogged meal | ROC > +0.10, COB = 0, no note in 45 min |
| Predicted hyper | path > 12 mmol/L within 2 h and IOB < 0.5 U |
| Over-injection | fires **immediately at note save**, not on the scan |

`GlucoseAlertEvaluator` is pure (no I/O); `GlucoseAlertService` is fully `@Async` so neither the note save nor the sync cycle blocks. Cooldown state is in-memory (resets on restart, by design). **Push delivery is still a stub** — alerts are logged at WARN pending APNs wiring.

---

## 12. Safety envelopes

Dosing is the one path that refuses rather than degrades. `InsulinCalculatorService`:

| Guard | Rule |
|---|---|
| `SETTINGS_INVALID` | Missing/non-finite/non-positive ISF or carb ratio — never substituted with a default |
| `INSULIN_PARAMS_INCONSISTENT` | Derived `gramsPerUnit = 10·ISF/CR` outside 3–30 g/U (a refusal boundary, not a clamp) |
| `GLUCOSE_BELOW_SAFE_THRESHOLD` | Current glucose < 3.9 mmol/L (ADA/ATTD Level 1) — treat the low, don't bolus |
| `GLUCOSE_IMPLAUSIBLE_UNIT` | Value outside physiological mmol/L range (likely mg/dL) — enforced at `InsulinCalculatorController` before the service is called |
| `DOSE_EXCEEDS_MAX_BOLUS` | Final dose > 0.3 U/kg, checked **after** meal + correction − IOB, i.e. on the number a patient would act on |

`DosingRefusedException` carries a patient-facing message and a separate internal `detail` that is logged but never returned. Prediction glucose is clamped to [1, 25] mmol/L everywhere.

Deriving `gramsPerUnit` from ISF and CR (rather than storing it) means it inherits the twin's calibration of both — and it is why a single accepted verification suggestion is bounded to ±25 %.

---

## 13. Security & platform

- **JWT** HS512, 1 h access / 365 d refresh (mobile-friendly), 30 s clock skew. `SecretsStartupValidator` **refuses to boot under `prod`** if the secret is missing, < 64 bytes, or matches a known dev marker — and if the credential-encryption key is still the dev fallback.
- **Token revocation** — `token_blacklist` stores SHA-256 hashes (never raw tokens), durable across restarts and shared across instances, pruned hourly. A `LOGOUT_ALL_DEVICES:` sentinel handles global logout.
- **Rate limiting** — `AuthRateLimitFilter` (highest precedence) counts only *failed* attempts on `/api/auth/login|register`: 10 per 300 s per IP → HTTP 429. Per-instance (Caffeine); distributed limiting would need Redis.
- **Credential encryption** — AES-256-GCM (12-byte IV, 128-bit tag) via a JPA `AttributeConverter`, so Nightscout/Libre secrets are encrypted at rest transparently.
- **Correlation IDs** — `X-Correlation-ID` in/out, pushed to MDC and rendered in every log line alongside `userId`.
- **Actuator** — only `health` and `info` exposed by default (prod-aligned); metrics/caches/loggers require authentication and explicit opt-in. Swagger is authenticated and disabled entirely under `prod`.
- **Circuit breakers** — `CircuitBreakerManager` per external service, CLOSED/OPEN/HALF_OPEN with failure threshold, timeout and half-open call budget; inspectable and resettable over `/api/circuit-breaker/**`.
- **Caching** — Caffeine: `nightscoutCredentials` (5 min), `nightscoutEntries` (30 s), `nutritionApiResponses` (7 d), `cgmReadings`, `llmResponses`, `cobSettings`.

---

## 14. Scheduled work

| Job | Cadence | What it does |
|---|---|---|
| `NightscoutGlucoseSyncScheduler` | 300 s fixed delay | Pull NS entries → `cgm_readings`, adaptive backoff |
| `LibreLinkUpGlucoseSyncScheduler` | 300 s fixed delay | Same for LibreLinkUp, per-user locks |
| `UnloggedEventScanScheduler` | 1200 s fixed delay | Residual scan → `unlogged_event_flags` |
| `VerificationScheduler` | 900 s fixed delay | Evaluate elapsed verification events, refresh summaries |
| `IsfMealWindowScheduler` | cron `0 0 2 * * *` UTC | Recompute observational per-window ISF for all users |
| `DigitalTwinCalibrationScheduler` | cron `0 15 3 * * *` | Nightly twin re-fit for all real users |
| `DigitalTwinBackfillRunner` | one-shot, `recalibrate-cli` profile | Recalibrate everyone now, then exit (web server disabled so it can run beside a live instance) |

---

## 15. Feature flags (`app.features.*`)

| Flag | YAML default | Java default | Role |
|---|---|---|---|
| `hovorka-model-enabled` | true | false | ODE forecast vs OpenAPS exponential fallback |
| `digital-twin-enabled` | true | false | Apply twin scales + residual/σ on the live path |
| `unlogged-event-detection-enabled` | true | false | Residual scan + scheduler |
| `activity-logging-enabled` | true | false | Consume activity notes as `a(t)` |
| `nutrition-aware-prediction-enabled` | true | false | Nutrition profile drives absorption/gut params |
| `glucose-calculations-enabled` | true | false | Gate the dashboard calc API |
| `insulin-calculator-enabled` | true | false | Gate the IOB/bolus API |
| `carbs-on-board-enabled` | true | false | Gate the COB API |
| `experiments-enabled` | — | true | Gate experiment protocols |
| `food-photo-analysis-enabled` | false | false | YOLO/photo meal pipeline |
| `ar-spatial-enabled` | false | false | AR nutrition path |
| `backend-mode-enabled` | true | false | Global frontend↔backend switch |

Migration percentages (`*-migration-percent`, all at 100) exist for staged client rollout.

---

## 16. Critical flows end to end

**A · Dashboard refresh** — `GET|POST /api/glucose-calculations` → resolve client time (persist zone on change) → `activeCobIobInputs` (8 h notes) → headline COB/IOB → prospective overlay → Hovorka (activity + twin) or OpenAPS → path + 2/4/8 h headlines + trend + factors, all read off one curve.

**B · Note create** — `POST /api/notes` → validate (incl. activity fields) → client nutrition profile *or* server-side enrichment (client macros are never overwritten on update) → save → async over-injection check → verification enqueue → on-bolus ISF window refresh. Every downstream consumer picks the note up from its DB window.

**C · Twin calibration** — cron/API → 30 d CGM + notes → activity-aware replay with unlogged-flag down-weighting → 2-stage IRLS-Huber LM → residual grid + σ → temporal-holdout scoring → persist → invalidate cache → live path applies only if the gate passed.

**D · Unlogged scan** — cron → raw Hovorka over 180 min → sustained residual vs robust σ → classify → OPEN flag → user confirm/dismiss.

**E · Verification titration** — note saved → PENDING event → 2 h later CGM fetched, error computed → rolling 7-event summary → gated suggestion → user accepts → carb ratio moves at most ±25 %.

**F · CGM sync** — scheduler → per-user config → SSRF-validated fetch / region-probing Libre auth → normalise → dedupe → insert → update sync state (fast/slow backoff) → anomaly detector evaluates on the same cadence.

---

## 17. Known gaps and sharp edges

- **Push notifications are a stub.** `GlucoseAlertService.deliverAlert` only logs; APNs is Phase 2. Alert cooldown state is in-memory and resets on restart.
- **Per-instance state.** LibreLinkUp sessions, alert cooldowns and the auth rate limiter are all in-memory — correct for one instance, not for a horizontally-scaled deployment. The token blacklist is the one that was already moved to the DB.
- **Reserved twin parameters.** `tmax_g_scale` and `egp_scale` are persisted but not wired into the live ODE (the residual layer covers the drift they'd model).
- **Activity is partly modelled.** Intensity maps to `a(t)`; activity *type* is stored for analytics only, and the per-user activity gain is specced but never fitted (always 1.0).
- **Absorption is slowed through more than one channel.** GI scaling, `DallaManGutModel.caloricScale`, the GLP-1 ileal brake and `MacroNutrientGastricModel`'s `tMaxG` all damp fat/protein meals — documented in the code as a known compounding.
- **Two prediction pipelines coexist.** `/api/glucose-calculations` can fall back to the OpenAPS exponential path; `/api/predict` is always Hovorka. Their parameters were audited (`docs/superpowers/specs/2026-08-18-parameter-audit-findings.md`) but they remain separate code paths.
- **`ContextAggregatorService` loads a user's entire CGM history** and filters in memory rather than pushing the window into the query.
- **Root-level clutter** — `cgm_readings_*.csv`, `fix_*.sql`, `cors-*.html`, `README.m` sit in the repo root against the project's own file-organisation rules.

## Related docs

- `docs/ARCHITECTURE.md` — deployment-oriented overview
- `docs/DIGITAL_TWIN_ML.md` — twin deep dive
- `docs/superpowers/specs/2026-08-18-parameter-audit-findings.md` — parameter audit across both pipelines
- `docs/superpowers/specs/2026-08-08-dual-computation-findings.md` — dashboard/experiments COB-IOB unification
