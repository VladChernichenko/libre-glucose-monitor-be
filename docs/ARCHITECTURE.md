# Backend Architecture

## Overview

Spring Boot REST API (Java 21) serving iOS and Watch clients for a personal glucose monitoring assistant. PostgreSQL for persistence, Ollama for local LLM inference, LibreView and Nightscout for CGM data, and Logmeal for food recognition.

---

## Functional Schema

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              iOS / watchOS Clients                              │
└───────────────────────────────────────┬─────────────────────────────────────────┘
                                        │ HTTPS / REST + JWT
                                        ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                            Spring Boot API (Port 8080)                          │
│                                                                                 │
│  ┌──────────┐  ┌──────────────┐  ┌─────────────┐  ┌───────────┐  ┌──────────┐ │
│  │   Auth   │  │   Glucose    │  │  Nutrition  │  │    AI     │  │ Monitor  │ │
│  │/api/auth │  │/api/glucose- │  │/api/nutri-  │  │/api/ai-   │  │/api/cob  │ │
│  │/api/users│  │ calculations │  │  tion       │  │ insights  │  │/api/cob- │ │
│  │          │  │/api/cob      │  │/api/notes   │  │           │  │ settings │ │
│  │          │  │/api/nightsc- │  │             │  │           │  │/api/ins- │ │
│  │          │  │  out         │  │             │  │           │  │  ulin    │ │
│  │          │  │/api/libre    │  │             │  │           │  │          │ │
│  └────┬─────┘  └──────┬───────┘  └──────┬──────┘  └─────┬─────┘  └──────────┘ │
│       │               │                 │               │                       │
│  ┌────▼───────────────▼─────────────────▼───────────────▼──────────────────┐   │
│  │                        Service Layer                                     │   │
│  │                                                                          │   │
│  │  AuthService         GlucoseCalculationsService    NutritionVisionSvc   │   │
│  │  JwtService          CarbsOnBoardService           NutritionEnrichSvc   │   │
│  │  UserService         InsulinCalculatorService      LogMealService        │   │
│  │                      NightScoutIntegration         GlycemicPatternSvc   │   │
│  │                      LibreLinkUpService            AIInsightsService     │   │
│  │                      NightscoutChartDataService    LlmGatewayService     │   │
│  │                      DataSourceConfigService       RAGRetrieverService   │   │
│  │                                                                          │   │
│  └────────────────────────────────┬─────────────────────────────────────────┘   │
│                                   │                                             │
│  ┌────────────────────────────────▼─────────────────────────────────────────┐   │
│  │                     Repository / JPA Layer                               │   │
│  │  UserRepository  NoteRepository  COBSettingsRepository                   │   │
│  │  GlucoseReadingRepository  InsulinCatalogRepository                      │   │
│  │  NightscoutChartDataRepository  UserDataSourceConfigRepository           │   │
│  │  UserGlucoseSyncStateRepository  GlycemicResponsePatternRepository       │   │
│  │  ClinicalKnowledgeChunkRepository  AIAnalysisTraceRepository             │   │
│  └────────────────────────────────┬─────────────────────────────────────────┘   │
│                                   │                                             │
│  ┌──────────────┐  ┌──────────────▼───────────┐  ┌───────────────────────────┐ │
│  │  Scheduler   │  │       PostgreSQL          │  │  Caffeine Cache (in-proc) │ │
│  │  (5 min)     │  │  (20 tables, Flyway V20)  │  │  nightscoutCredentials    │ │
│  │  Nightscout  │  │                           │  │  nightscoutEntries        │ │
│  │  Glucose     │  │                           │  │  nutritionApiResponses    │ │
│  │  Sync        │  │                           │  │  cgmReadings              │ │
│  └──────────────┘  └───────────────────────────┘  │  llmResponses            │ │
│                                                    └───────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────────┘
         │                   │                    │                   │
         ▼                   ▼                    ▼                   ▼
  ┌─────────────┐   ┌──────────────┐   ┌──────────────────┐  ┌──────────────────┐
  │   Ollama    │   │  LibreView   │   │    Nightscout    │  │    Logmeal       │
  │ (localhost  │   │ EU/US/AU/AS  │   │  (user-config-   │  │  api.logmeal.com │
  │  :11434)    │   │   /AE        │   │   ured URL)      │  │  /v2             │
  │             │   │  REST API    │   │  REST API +      │  │  /segmentation   │
  │  text gen   │   │  Auth +      │   │  Circuit Breaker │  │  /nutritional-   │
  │  vision     │   │  Glucose     │   │                  │  │   Info           │
  └─────────────┘   └──────────────┘   └──────────────────┘  └──────────────────┘
```

---

## REST Endpoints

| Group | Endpoints | Purpose |
|-------|-----------|---------|
| **Auth** | `POST /api/auth/login` `POST /api/auth/register` `POST /api/auth/refresh` `POST /api/auth/logout` `DELETE /api/auth/sessions` | JWT-based auth, multi-device session management |
| **User** | `GET/PUT /api/users/profile` `GET/PUT /api/user/insulin-preferences` `GET/PUT /api/user/data-source-config` | User profile and preferences |
| **Glucose** | `POST/GET /api/glucose-calculations` | COB/IOB/prediction path calculations |
| **COB** | `GET /api/cob` `GET /api/cob/timeline` `GET/PUT /api/cob-settings` | Carbs-on-board with GI/GL-aware absorption |
| **Nightscout** | `GET /api/nightscout/entries` `GET /api/nightscout/current` `GET /api/nightscout/chart` `GET /api/nightscout/health` | Nightscout proxy endpoints |
| **Libre** | `POST /api/libre/auth` `GET /api/libre/connections` `GET /api/libre/graph` `GET /api/libre/profile` | LibreView CGM data |
| **Insulin** | `POST /api/insulin/calculate` `GET /api/insulin/active` `GET /api/insulin-catalog` | Dose calculator, IOB curve |
| **Notes** | `GET/POST /api/notes` `PUT/DELETE /api/notes/{id}` `POST /api/notes/{id}/photo` `GET /api/notes/range` `GET /api/notes/summary` | Meal/glucose notes with photo upload |
| **Nutrition** | `POST /api/nutrition/analyze` `POST /api/nutrition/analyze-image` | Text-based and photo-based food analysis |
| **AI Insights** | `POST /api/ai-insights/analyze` (JSON + NDJSON streaming) | LLM retrospective glucose analysis |
| **Features** | `GET /api/features` `GET /api/features/{key}` `POST /api/features/{key}/toggle` | Runtime feature flags |
| **Circuit Breaker** | `GET /api/circuit-breaker/stats` `POST /api/circuit-breaker/{name}/reset` | Resilience monitoring |
| **Version** | `GET /api/version` `GET /api/version/check` | Client compatibility |

---

## External Integrations

### Ollama (Local LLM)
- **URL:** `http://localhost:11434/api/generate`
- **Used by:** `LlmGatewayService`, `NutritionVisionService`
- **Models:** configurable (e.g. `llama3.2`, `llava` for vision)
- **Calls:**
  - AI retrospective analysis — text prompt with glucose/meal/insulin history
  - Food photo analysis — base64 image + structured extraction prompt
- **Caching:** LLM responses cached in Caffeine (5 min TTL)
- **No circuit breaker** — failures propagate as 503

### LibreView (CGM Data)
- **Base URL:** `https://api-eu.libreview.io` (+ AU / US / AS / AE fallbacks)
- **Used by:** `LibreLinkUpService`
- **Auth:** POST login with LibreView credentials → token stored per user
- **Calls:**
  - `POST /llu/auth/login` — authenticate
  - `GET /llu/connections` — list linked patients
  - `GET /llu/connections/{id}/graph` — glucose graph (CGM readings)
  - `GET /llu/connections/{id}/logbook` — history
- **Fallback:** tries 5 regional hosts in order on failure

### Nightscout (CGM / Insulin / Carbs)
- **URL:** user-configured per account (stored encrypted in `user_data_source_config`)
- **Used by:** `NightScoutIntegration`, `NightscoutChartDataService`
- **Circuit breaker:** `CircuitBreakerManager` ("nightscout" breaker, 5 failures → open, 1 s reset)
- **Calls:**
  - `GET /api/v1/entries.json` — SGV entries
  - `GET /api/v1/devicestatus.json` — pump/loop status
  - `GET /api/v1/profile.json` — basal rates, ISF, CR
- **Sync:** `NightscoutGlucoseSyncScheduler` polls every 5 min, stores in `nightscout_chart_data`

### Logmeal (Food Recognition)
- **URL:** `https://api.logmeal.com/v2`
- **Used by:** `LogMealService`
- **Auth:** API key via `Authorization: Bearer` header
- **Calls (2-step):**
  1. `POST /image/segmentation/complete/v1.0` — upload JPEG, get `imageId` + food list
  2. `POST /recipe/nutritional_info` — send `imageId`, get macros (`totalNutrients` USDA codes: `CHOCDF`, `FIBTG`, `PROCNT`, `FAT`)
- **No circuit breaker** — failures fall back to keyword-based GI estimation

---

## Database Schema (PostgreSQL, Flyway V1–V20)

```
users ──────────────────────────────────────────────────────────────────┐
  │                                                                     │
  ├── notes (user_id FK)                                                │
  │     └── nutrition_profile JSONB (NutritionSnapshot)                │
  │                                                                     │
  ├── glucose_readings (user_id FK)                                     │
  ├── carbs_entries (user_id FK)                                        │
  ├── insulin_doses (user_id FK)                                        │
  │                                                                     │
  ├── nightscout_chart_data (user_id FK, indexed by entry_date)         │
  ├── user_glucose_sync_state (user_id FK, 1:1)                        │
  │                                                                     │
  ├── user_configurations (user_id FK, 1:1)                            │
  ├── cob_settings (user_id FK, 1:1)                                   │
  ├── user_insulin_preferences (user_id FK, 1:1)                       │
  ├── user_data_source_config (user_id FK, 1:1, encrypted credentials) │
  │                                                                     │
  ├── ai_analysis_trace (user_id FK)                                   │
  └── (global) insulin_catalog                                          │
       └── user_insulin_preferences.insulin_id FK                       │
  (global) clinical_knowledge_chunk ──────────────────────────────────┘
  (global) glycemic_response_patterns
```

---

## Key Data Flows

### CGM Reading (Libre path)
```
iOS → GET /api/libre/graph → LibreLinkUpService → LibreView API (EU/fallback)
                           ← glucose readings ← ← ← ← ← ← ← ← ← ← ← ←
     ← GlucoseChartPoint[]
```

### Glucose Calculations
```
iOS → POST /api/glucose-calculations {currentGlucose, timestamp}
        → GlucoseCalculationsService
            → CarbsOnBoardService (reads notes, applies GI/GL pattern duration)
            → InsulinCalculatorService (reads insulin_doses, IOB curve)
            → LlmGatewayService (optional prediction enhancement)
        ← GlucoseCalculationsResponse {COB, IOB, predictionPath, 2h/4h forecast}
```

### Food Photo → Nutrition
```
iOS → POST /api/nutrition/analyze-image (multipart JPEG)
        → NutritionVisionService
            → LogMealService → Logmeal API (segmentation → nutritionalInfo)
            → GlycemicPatternMatchingService (match DB pattern → bolus strategy, duration)
        ← NutritionSnapshot {carbs, GI, GL, bolusStrategy, suggestedDurationHours}
```

### AI Retrospective Analysis
```
iOS → POST /api/ai-insights/analyze {timeRange}
        → AIInsightsService
            → RAGRetrieverService (query clinical_knowledge_chunk)
            → ContextAggregatorService (fetch notes, readings, insulin history)
            → LlmGatewayService → Ollama /api/generate
        ← AIInsightsResponse (JSON or NDJSON stream)
```

### Nightscout Background Sync
```
NightscoutGlucoseSyncScheduler (every 5 min)
    → NightScoutIntegration → Nightscout /api/v1/entries.json [circuit breaker]
    → NightscoutChartDataService.storeChartData() [async thread pool, 8–32 threads]
    → nightscout_chart_data table
    → user_glucose_sync_state (update last_sync, backoff)
```

---

## Cross-Cutting Concerns

| Concern | Implementation |
|---------|---------------|
| **Authentication** | JWT (access 1h / refresh 365d), `JwtAuthFilter` on all `/api/**` except auth |
| **Correlation** | `CorrelationIdFilter` injects `X-Correlation-ID` on every request |
| **Caching** | Caffeine in-process cache, 5 groups, 5 min TTL, max 50k entries |
| **Resilience** | `CircuitBreakerManager` wraps Nightscout calls (5 failures → open, 1 s reset) |
| **Async** | `chartPersistExecutor` (8–32 threads, queue 1000) for Nightscout chart storage |
| **Feature flags** | DB-backed flags checked at runtime; toggle via `/api/features/{key}` |
| **Error handling** | `GlobalExceptionHandler` maps domain exceptions to HTTP status codes |
| **CORS** | Configured in `CorsConfig`, permissive in dev, restrictive in prod |
| **DB migrations** | Flyway, V1–V20, applied at startup |
