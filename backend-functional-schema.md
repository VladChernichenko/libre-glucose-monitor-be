# Backend functional schema

· Source: `glucose-monitor-be` (Spring Boot, package `che.glucosemonitorbe`)  
· Persistence: Postgres + Flyway V1–V11  
· Encoding: UTF-8

## Mental model

The backend is an event-driven physiology engine:

- **CGM** is truth
- **Notes** are interventions (meals, insulin, basal, activity)
- **Hovorka** is the forward ODE model (OpenAPS exponential path is the fallback)
- **Digital twin** is a thin personalization layer (scales + hourly residual bias + uncertainty)

Feature flags can turn each learning / activity / detection path inert without deleting data.

## 1. Layers

Requests never jump from controllers into the ODE. Application services own transactions, feature gates, and which notes/CGM window are loaded; `hovorka/*` stays pure-ish math.

| Layer | Package | Responsibility |
|-------|---------|----------------|
| API | `controller/` | REST endpoints, auth principal, DTO in/out. Thin — no physiology math. |
| Application | `service/` | Orchestrates use cases: notes, dashboard calc, twin calibrate, Libre/NS sync, experiments, AI. |
| Physiology | `hovorka/` | Hovorka ODE, gut models, basal/EGP, activity modulation, RK4 solver. |
| Learning | `hovorka/learning/` | Digital twin LM fit, residual bias grid, prediction uncertainty bands, replay engine. |
| Persistence | `entity/` + `domain/` + `repository/` | JPA entities and Spring Data repos over Postgres (Flyway). Optional Mongo for OFF cache. |
| Integrations | `service/libre`, `nightscout/`, `ai/`, `storage/` | LibreLinkUp, Nightscout, Ollama/Qwen, YOLO, LogMeal, OFF, S3/MinIO photos. |
| Platform | `config/`, `security/`, `scheduler/`, `circuitbreaker/` | JWT, rate limits, feature flags, cron jobs, CORS, caches, resilience. |

```
Controller → FeatureToggleService → Application service
    → COB / IOB helpers
    → hovorka/* (+ learning/*)
    → Repository / external clients
```

## 2. Domain map

| Domain | API | Owns | Why it exists |
|--------|-----|------|---------------|
| Auth & users | `/api/auth`, `/api/users` | Login/register/refresh/logout, JWT blacklist, `/me` | Every authenticated path resolves the user before touching notes/CGM. |
| Notes | `/api/notes` | Meals, boluses, long-acting, activity; photos; nutrition JSON | Primary event log. Feeds COB/IOB, Hovorka gut, activity `a(t)`, twin replay. |
| Dashboard calc | `/api/glucose-calculations` | Headline COB/IOB + forecast path for the home screen | Single shared `activeCobIobInputs()` also used by Experiments readiness. |
| Meal predict | `/api/predict` | What-if meal + insulin + pre-bolus search (always Hovorka) | Separate from dashboard; optimizes timing/dose for a planned meal. |
| CGM ingest | `/api/libre`, `/api/nightscout`, data-source-config | Pull/store CGM into `cgm_readings`; sync state / backoff | Ground truth for forecasts, twin fit, unlogged detector, verification. |
| Settings & insulin | `/api/user-settings`, insulin-catalog, insulin-preferences | CR, ISF, carb half-life, meal-window ISF, rapid/basal picks | Population params before twin scales; basal experiment results land here. |
| Digital twin | `/api/digital-twin` | Nightly + on-demand calibrate; apply scales/residuals/σ | Personalizes Hovorka without re-fitting the whole ODE structure. |
| Unlogged events | `/api/unlogged-events` | Scan unexplained CGM moves; confirm/dismiss; optional note backfill | Catches missing insulin/food; activity-aware so workouts are not false positives. |
| Experiments | `/api/experiments` (+ verification) | `BASAL_CHECK`, `CARB_FACTOR`, `ISF_ONE_UNIT`; real-meal verification loop | Protocol measurements update settings / EGP estimation with clean background. |
| ISF windows | `/api/isf/meal-windows` | Observational ISF by meal window + morning suggestion cadence | Time-of-day ISF without a full twin retrain. |
| Nutrition & AI | `/api/nutrition`, `/api/ai-insights` | Barcode/OFF, LogMeal, YOLO/AR analyze; LLM retrospective insights | Enrich notes (absorption) and explain history; not on the hot ODE path. |

## 3. Prediction stack

### Live dashboard path

`GlucoseCalculationsController` → `GlucoseCalculationsService.buildPredictionPath`

1. Load notes in an ~8h window; build carbs + rapid insulin entries (long-acting excluded from bolus IOB).
2. Compute headline COB/IOB.
3. If `hovorka-model-enabled` → `HovorkaGlucosePredictionService` (RK4 ODE, gut, basal EGP, optional `NotesActivityProvider` → `ActivityModulation`, twin residual/σ bands).
4. Else → OpenAPS-style exponential Δ-glucose from COB/IOB only.

### What each signal does in the ODE

| Signal | Effect |
|--------|--------|
| Carbs | Gut compartments → appearance rate `Ra(t)` |
| Rapid insulin | OpenAPS activity rate → insulin effect on Q/X |
| Long-acting | `BasalInsulinResolver` modulates EGP (36h lookback) |
| Activity | `a(t)` ∈ [0,1]: sensitivity↑ + independent uptake + 120m post-exercise tail |
| Twin | Multiplies `isf` / `ag` / `egp`; adds hourly residual; widens bands by σ |

### Activity in the model (current)

- Intensity maps `LOW` / `MODERATE` / `HIGH` / `VERY_HARD` → `0.25` / `0.5` / `0.75` / `1.0` over `[timestamp, timestamp + duration)`.
- Type (walk/run/…) is stored for UX/analytics only; it does **not** change the ODE.
- Per-user activity gain is specced but **not fitted** — gain is always `1.0`.
- When `activity-logging-enabled` is off, notes may still be stored but `a(t)` is inert (`ActivityProvider.NONE`).

### Meal what-if path

`POST /api/predict` → `GlucosePredictService` (always Hovorka; pre-bolus candidates; FPU / macro gut params).

## 4. Digital twin / learning

Nightly `DigitalTwinCalibrationScheduler` (or `POST /api/digital-twin/recalibrate`) loads ~30d CGM + notes, builds `PredictionReplayEngine` (optionally activity-aware), then `DigitalTwinCalibrator`:

| Stage | What is fitted |
|-------|----------------|
| 1 — EGP | `egpScale` on fasting anchors only (BASAL_CHECK-friendly) |
| 2 — Sensitivity / meal | `isfScale` + `agScale` via IRLS + Huber LM; ridge toward 1.0 |
| 3 — Residual + σ | `ResidualBiasModel` (24h grid), then `PredictionUncertaintyModel` at 30/60/90/120 min |

**Apply gate:** twin is persisted with `applied=true` only if out-of-sample MAE beats the uncalibrated baseline.

- Live path uses `DigitalTwinService` + `DigitalTwinResidualProvider`.
- Calibration and unlogged detection use raw `PredictionResidualProvider.NONE` so they measure physiology, not the correction.

## 5. Unlogged events

`UnloggedEventScanScheduler` (~20 min) runs `UnloggedEventDetectionService`:

1. Raw Hovorka over a recent CGM window (activity-aware when the flag is on).
2. Look for sustained same-sign residual vs robust σ.
3. Classify as unlogged / under-estimated food or insulin; persist `UnloggedEventFlag`.
4. Matching logged carbs/insulin changes the category; activity notes reduce residuals via the ODE rather than counting as matching insulin.
5. User can confirm (optional backfill note) or dismiss via API.

## 6. Experiments & verification

| Experiment | Purpose |
|------------|---------|
| `BASAL_CHECK` | Overnight flatness / EGP-oriented |
| `CARB_FACTOR` | Carb ratio identification |
| `ISF_ONE_UNIT` | Sensitivity from a unit correction |

Clean background: COB < 5 g and IOB < 0.3 U via shared dashboard inputs.

**Verification:** after real meals, `VerificationService` compares predicted vs actual CGM and maintains summaries / suggestions — a closed loop separate from the twin LM fit.

## 7. Persistence

| Entity | Purpose |
|--------|---------|
| `User` | Identity; Spring Security principal |
| `UserSettings` | CR, ISF, half-life, weight, meal-window ISF overrides |
| `Note` | Meal / bolus / long-acting / activity + nutrition + photo |
| `CgmReading` | Shared CGM cache (`NIGHTSCOUT` \| `LIBRE_LINK_UP`) |
| `UserDataSourceConfig` | Per-user NS/Libre credentials and active source |
| `UserGlucoseSyncState` | Poll pacing, last-seen, backoff |
| `InsulinCatalog` | Rapid/long-acting PK parameters |
| `UserInsulinPreferences` | User's chosen insulins + basal injection time |
| `UserDigitalTwin` | Learned scales, residual grid, uncertainty knots |
| `UnloggedEventFlag` | Open/confirmed/dismissed unexplained windows |
| `Experiment` + `ExperimentReading` | Protocol runs and timed samples |
| `VerificationEvent` / `VerificationSummary` | Real-meal predicted vs actual accuracy |
| `IsfMealWindowSnapshot` / Suggestion | Observational ISF + suggestion cadence |
| `GlycemicResponsePattern` | Macro → absorption / bolus strategy templates |
| `ClinicalKnowledgeChunk` | RAG snippets for AI insights |
| `AiAnalysisTrace` | Audit trail for LLM outputs |
| `RevokedToken` | Logout / blacklist durability |

**Flyway themes:** V1 baseline → V2–V4 seeds → V5 experiments → V6 verification → V7 ISF windows → V8 twin → V9 unlogged flags → V10 activity columns → V11 ISF suggestions.

## 8. Feature flags

Bound via `FeatureToggleConfig` (`app.features.*`). YAML is the deployed source of truth when present; Java defaults for twin/unlogged/activity/hovorka are off if YAML is absent.

| Flag | Default | Role |
|------|---------|------|
| `hovorka-model-enabled` | true (YAML) | Use ODE forecast vs OpenAPS exponential fallback |
| `digital-twin-enabled` | true (YAML) | Apply twin scales + residual/σ on live path |
| `unlogged-event-detection-enabled` | true (YAML) | Scheduler + residual scan |
| `activity-logging-enabled` | true (YAML) | Consume activity notes as `a(t)` in ODE/detector/twin replay |
| `nutrition-aware-prediction-enabled` | true | Nutrition profile drives absorption / gut params |
| `glucose-calculations-enabled` | true | Gate dashboard calc API |
| `insulin-calculator-enabled` | true | Gate IOB calculator API |
| `carbs-on-board-enabled` | true | Gate COB API |
| `experiments-enabled` | true (Java) | Gate experiment protocols |
| `food-photo-analysis-enabled` | false | YOLO / photo meal pipeline |
| `ar-spatial-enabled` | false | AR nutrition analyze path |

## 9. External systems

| System | Used for |
|--------|----------|
| LibreLinkUp | CGM graph/history sync into `cgm_readings` |
| Nightscout | Alternate CGM source + chart APIs |
| Postgres + Flyway | Primary durable store |
| Mongo (optional) | Open Food Facts product cache |
| Ollama / Qwen | AI retrospective insights + RAG |
| YOLO service | Food photo / AR vision (feature-gated) |
| LogMeal / OFF | Nutrition enrichment for notes |
| S3 / MinIO | Note photo blobs |

## 10. Critical request flows

### A. Dashboard refresh

Client → `GET`/`POST /api/glucose-calculations` → FeatureToggle → load settings + recent notes + CGM context → COB + IOB → Hovorka (activity + twin) or OpenAPS → JSON path for chart + factors.

### B. Note create

`POST /api/notes` → validate (incl. activity fields) → client nutrition profile or `NutritionEnrichmentService` → save → async over-injection observer → verification enqueue. Next calc/scan picks the note up from the DB window.

### C. Twin calibrate

Cron or `POST /recalibrate` → 30d CGM/notes → activity-aware replay → LM fit → persist `UserDigitalTwin` → invalidate cache → live path applies if gate passed.

### D. Unlogged scan

Cron → `scanUser` → raw Hovorka + activity → residual vs σ → OPEN flag → user confirm/dismiss via API.

## 11. Component dependency sketch

```
Controllers
  → FeatureToggleService
       → Application services (Notes, GlucoseCalculations, Twin, Unlogged, Experiments…)
            → CarbsOnBoardService / InsulinCalculatorService
            → HovorkaGlucosePredictionService
            →    → HovorkaParameterService ← DigitalTwinService
            →    → HovorkaOdeSolver + Gut + BasalInsulinResolver
            →    → NotesActivityProvider → ActivityModulation
            → hovorka.learning (Calibrator, Replay, ResidualBias, Uncertainty)
            → Repositories → Postgres
            → Libre / Nightscout / LLM / YOLO / S3
```

## Related docs

- [architecture-overview.md](./architecture-overview.md)
- [ARCHITECTURE.md](./ARCHITECTURE.md)
- [data-models.md](./data-models.md)
- [service-specifications.md](./service-specifications.md)
- Backend twin deep-dive: `../glucose-monitor-be/docs/DIGITAL_TWIN_ML.md`
