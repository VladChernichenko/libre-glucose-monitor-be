# glucose-monitor-be

Backend for the Libre Glucose Monitor application — a Spring Boot REST API that aggregates CGM (Continuous Glucose Monitor) data from Nightscout and Abbott LibreLinkUp, calculates insulin/carb metrics, and provides AI-driven retrospective analysis of glucose patterns.

---

## Table of Contents

- [Tech Stack](#tech-stack)
- [Architecture Overview](#architecture-overview)
- [Project Structure](#project-structure)
- [Domain Model](#domain-model)
- [API Reference](#api-reference)
- [Authentication](#authentication)
- [External Integrations](#external-integrations)
- [Configuration & Environment Variables](#configuration--environment-variables)
- [Database](#database)
- [Running Locally](#running-locally)
- [Deployment](#deployment)

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.5.5 |
| Security | Spring Security + JWT (JJWT 0.12.3, HS512) |
| Persistence | Spring Data JPA / Hibernate, PostgreSQL |
| Migrations | Flyway |
| HTTP clients | Spring Cloud OpenFeign 4.1.3, `java.net.http.HttpClient` |
| Mapping | MapStruct 1.6.3 |
| Build | Gradle |
| Deployment | Render.com (web service + managed PostgreSQL), Docker |

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────────┐
│                         Frontend (React)                          │
└───────────────────────────────┬──────────────────────────────────┘
                                │ HTTPS / JWT
┌───────────────────────────────▼──────────────────────────────────┐
│                    Spring Boot REST API                           │
│                                                                   │
│  Controllers → Services → Repositories → PostgreSQL               │
│                                                                   │
│  ┌────────────┐  ┌──────────────┐  ┌─────────────────────────┐  │
│  │ CGM Sources│  │  AI Pipeline │  │  Glucose Calculations    │  │
│  │ Nightscout │  │  RAG + LLM   │  │  COB / IOB / Prediction  │  │
│  │ LibreLinkUp│  │  (Ollama)    │  │  Nutrition-Aware         │  │
│  └────────────┘  └──────────────┘  └─────────────────────────┘  │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ Background Scheduler: Nightscout sync every 5 min           │ │
│  └─────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
          │               │                │
    Nightscout      LibreLinkUp        Spoonacular
    (self-hosted)   (Abbott cloud)     (nutrition API)
```

### Key Architectural Patterns

**Feature Toggles** — Each major feature (`insulin-calculator`, `carbs-on-board`, `glucose-calculations`, etc.) has a global on/off switch and a percentage-based migration rollout. User assignment is deterministic (username hash), so the same user always gets the same experience. When a feature is toggled off for a user, the frontend falls back to its own local logic.

**Custom Circuit Breaker** — A hand-rolled three-state circuit breaker (CLOSED → OPEN → HALF_OPEN) wraps all external calls (Nightscout, LibreLinkUp, Spoonacular, AI). Defaults: 5 failures to open, 60 s recovery timeout. Stats and manual reset are exposed via `/api/circuit-breaker`.

**AI Pipeline (RAG + LLM)** — Retrospective analysis follows a structured pipeline:
1. `ContextAggregatorService` — assembles glucose history, notes, COB/IOB/prediction context
2. `RagRetrieverService` — fetches relevant `ClinicalKnowledgeChunk` rows by condition tags
3. `LlmGatewayService` — builds prompt, calls Ollama (streaming NDJSON or batch), falls back to rules-only on failure
4. `SafetyAndScoringService` — applies confidence scoring and safety guardrails
5. `AiAnalysisTraceService` — persists trace to `ai_analysis_trace`

**Adaptive Background Sync** — `NightscoutGlucoseSyncScheduler` polls each active Nightscout user's CGM data. If new data was found, next poll is in 5 min; if no change, it backs off to 60 min. State is tracked per user in `user_glucose_sync_state`.

**Dual Data Source** — Users can configure either Nightscout or LibreLinkUp (or both) as their CGM source. Credentials are stored per-user in `user_data_source_config` and can be independently activated/deactivated.

---

## Project Structure

```
src/main/java/che/glucosemonitorbe/
├── ai/                  # AI insight pipeline
│   ├── AiInsightsController.java
│   ├── ContextAggregatorService.java
│   ├── LlmGatewayService.java
│   ├── RagRetrieverService.java
│   ├── SafetyAndScoringService.java
│   └── AiAnalysisTraceService.java
├── circuitbreaker/      # Custom circuit breaker
├── config/              # Spring beans (CORS, Security, Scheduling, Feature flags, Nutrition, Version)
├── controller/          # REST controllers (17 total)
├── domain/              # JPA entities: User, NightscoutChartData, UserDataSourceConfig, ...
├── dto/                 # Request/response DTOs
├── entity/              # JPA entities: Note, InsulinCatalog, COBSettings, UserInsulinPreferences
├── exception/           # Global exception handler + custom exceptions
├── mapper/              # MapStruct mappers
├── nightscout/          # Nightscout API client and integration
├── repository/          # Spring Data JPA repositories
├── scheduler/           # NightscoutGlucoseSyncScheduler
├── security/            # JWT filter, token provider, custom UserDetails
└── service/             # Business logic
    └── nutrition/       # Spoonacular nutrition enrichment sub-package
```

---

## Domain Model

### Entity Relationships

```
users (1) ──────< user_data_source_config
users (1) ──────< cob_settings             (CASCADE DELETE)
users (1) ──────< notes                    (CASCADE DELETE)
users (1) ──────< user_insulin_preferences (CASCADE DELETE)
users (1) ──────< user_glucose_sync_state
users (1) ──────< nightscout_chart_data

user_insulin_preferences >────── insulin_catalog (rapid)
user_insulin_preferences >────── insulin_catalog (long-acting)
```

### Core Entities

| Entity | Table | Purpose |
|---|---|---|
| `User` | `users` | Application user; implements Spring `UserDetails`. Roles: `USER`, `ADMIN`. |
| `NightscoutChartData` | `nightscout_chart_data` | Stored CGM readings synced from Nightscout (sgv in mg/dL, timestamp, trend/direction). |
| `UserDataSourceConfig` | `user_data_source_config` | Per-user CGM source config (Nightscout URL/secret/token or LibreLinkUp credentials). |
| `UserGlucoseSyncState` | `user_glucose_sync_state` | Adaptive sync state: last sync time, backoff counter, next poll time. |
| `Note` | `notes` | User diary entry — carbs, insulin dose, meal, comment, glucose value, and optional nutrition profile (JSON). |
| `InsulinCatalog` | `insulin_catalog` | Reference data for insulin types (RAPID / LONG_ACTING) with PK/PD parameters. Seeded with FIASP, Apidra, Tresiba, Lantus. |
| `COBSettings` | `cob_settings` | Per-user carb-on-board settings (carb ratio, ISF, carb half-life). |
| `UserInsulinPreferences` | `user_insulin_preferences` | User's chosen rapid and long-acting insulin from the catalog. |
| `ClinicalKnowledgeChunk` | `clinical_knowledge_chunk` | RAG knowledge base: clinical guidelines tagged by condition, insulin type, risk class. |
| `AiAnalysisTrace` | `ai_analysis_trace` | Audit log of AI analysis runs (model, latency, confidence, context hash). |

---

## API Reference

All endpoints require `Authorization: Bearer <access_token>` unless marked **public**.

### Auth — `/api/auth` (public)

| Method | Path | Description |
|---|---|---|
| POST | `/api/auth/register` | Register a new user → `AuthResponse` |
| POST | `/api/auth/login` | Authenticate → `AuthResponse { accessToken, refreshToken, tokenType, expiresIn }` |
| POST | `/api/auth/refresh` | Exchange refresh token for new token pair |
| POST | `/api/auth/logout` | Blacklist access token (+ optional refresh token) |
| POST | `/api/auth/logout-current` | Logout using the Authorization header token |
| POST | `/api/auth/logout-all` | Logout all devices (stub) |
| GET | `/api/auth/test` | Liveness probe |

### User — `/api/users`

| Method | Path | Description |
|---|---|---|
| GET | `/api/users/me` | Current user profile → `UserResponse` |

### Nightscout — `/api/nightscout`

| Method | Path | Params | Description |
|---|---|---|---|
| GET | `/api/nightscout/entries` | `count`, `useStored`; headers `X-Timezone-Offset`, `X-Timezone` | Fetch or serve stored glucose entries |
| GET | `/api/nightscout/entries/current` | Same timezone headers | Latest single entry |
| GET | `/api/nightscout/entries/date-range` | `startDate`, `endDate`, `useStored` | Date-range query |
| GET | `/api/nightscout/chart-data` | `count` | Stored chart entries |
| DELETE | `/api/nightscout/chart-data` | — | Clear stored chart data |
| GET | `/api/nightscout/health` | — | Proxy health check |

### LibreLinkUp — `/api/libre`

| Method | Path | Description |
|---|---|---|
| POST | `/api/libre/auth/login` | Authenticate with LibreView |
| GET | `/api/libre/connections` | List patient connections |
| GET | `/api/libre/connections/{patientId}/graph` | Glucose graph (`days` param) |
| GET | `/api/libre/connections/{patientId}/current` | Current reading |
| GET | `/api/libre/connections/{patientId}/history` | Historical data (`days`, `startDate`, `endDate`) |
| GET | `/api/libre/connections/{patientId}/raw` | Raw unprocessed response |
| GET | `/api/libre/profile` | LibreView user profile |

### Glucose Calculations — `/api/glucose-calculations`

| Method | Path | Description |
|---|---|---|
| GET | `/api/glucose-calculations/` | COB + IOB + 2-hour prediction |
| POST | `/api/glucose-calculations/` | Same via `GlucoseCalculationsRequest` body |
| GET | `/api/glucose-calculations/status` | Feature flag status |

### Insulin Calculator — `/api/insulin`

| Method | Path | Description |
|---|---|---|
| POST | `/api/insulin/calculate` | Insulin dose recommendation → `InsulinCalculationResponse` |
| GET | `/api/insulin/status` | Feature flag status |
| POST | `/api/insulin/active-insulin` | Active insulin stub |

### Carbs-on-Board — `/api/cob`

| Method | Path |
|---|---|
| POST | `/api/cob/calculate` |
| POST | `/api/cob/status` |
| POST | `/api/cob/timeline` |

### COB Settings — `/api/cob-settings`

| Method | Path |
|---|---|
| GET | `/api/cob-settings` |
| POST | `/api/cob-settings` |
| PUT | `/api/cob-settings` |
| DELETE | `/api/cob-settings` |
| GET | `/api/cob-settings/exists` |

### Notes — `/api/notes`

| Method | Path | Description |
|---|---|---|
| GET | `/api/notes` | All notes for current user |
| GET | `/api/notes/range` | Notes in date range (`startDate`, `endDate`) |
| GET | `/api/notes/{id}` | Single note |
| POST | `/api/notes` | Create note → 201 |
| PUT | `/api/notes/{id}` | Update note |
| DELETE | `/api/notes/{id}` | Delete note → 204 |
| GET | `/api/notes/summary` | Aggregate summary |
| GET | `/api/notes/today` | Today's notes |
| GET | `/api/notes/health` | Health probe |

### Nutrition — `/api/nutrition`

| Method | Path | Description |
|---|---|---|
| POST | `/api/nutrition/analyze` | Parse ingredient text via Spoonacular → `NutritionSnapshot` |

### Data Source Config — `/api/user/data-source-config`

| Method | Path |
|---|---|
| POST | `/api/user/data-source-config` |
| GET | `/api/user/data-source-config` |
| GET | `/api/user/data-source-config/active/{dataSource}` |
| GET | `/api/user/data-source-config/status` |
| POST | `/api/user/data-source-config/{configId}/activate` |
| POST | `/api/user/data-source-config/{configId}/deactivate` |
| DELETE | `/api/user/data-source-config/{configId}` |
| POST/GET | `/api/user/data-source-config/test-nightscout` |
| POST | `/api/user/data-source-config/{configId}/test` |
| POST | `/api/user/data-source-config/{configId}/last-used` |

### Insulin Preferences — `/api/user/insulin-preferences`

| Method | Path |
|---|---|
| GET | `/api/user/insulin-preferences` |
| PUT | `/api/user/insulin-preferences` |

### Insulin Catalog — `/api/insulin-catalog` (read-only)

| Method | Path | Params |
|---|---|---|
| GET | `/api/insulin-catalog` | `?category=RAPID\|LONG_ACTING` (optional) |

### AI Insights — `/api/ai-insights`

| Method | Path | Description |
|---|---|---|
| POST | `/api/ai-insights/retrospective` | Synchronous AI analysis. Body: `{ windowHours }` → `AiAnalysisResponse` |
| POST | `/api/ai-insights/retrospective/stream` | Streaming NDJSON. Emits `{type:"token", token:"..."}` per chunk, then `{type:"done", promptTokens, completionTokens}`. Supports `followUpQuestion`, `conversationTurns`, `model`, `numCtx` override. |

### Feature Toggles — `/api/features` (public)

| Method | Path |
|---|---|
| GET | `/api/features/status` |
| POST | `/api/features/toggle/{feature}` |
| GET | `/api/features/check/{feature}` |

### Version — `/api/version` (public)

| Method | Path |
|---|---|
| GET | `/api/version/` |
| POST | `/api/version/check-compatibility` |
| GET | `/api/version/compatibility-matrix` |
| GET | `/api/version/health` |

### Circuit Breaker — `/api/circuit-breaker`

| Method | Path |
|---|---|
| GET | `/api/circuit-breaker/stats` |
| GET | `/api/circuit-breaker/stats/{serviceName}` |
| POST | `/api/circuit-breaker/reset/{serviceName}` |
| POST | `/api/circuit-breaker/reset-all` |
| GET | `/api/circuit-breaker/health` |

---

## Authentication

Stateless JWT authentication using HMAC-SHA512.

| Token | Lifetime | Notes |
|---|---|---|
| Access token | 1 hour | Sent as `Authorization: Bearer <token>` |
| Refresh token | 24 hours | Has additional claim `"type":"refresh"` |

**Flow:**
1. `POST /api/auth/login` → `AuthResponse { accessToken, refreshToken }`
2. Include `Authorization: Bearer <accessToken>` on all protected requests
3. When expired, call `POST /api/auth/refresh` with the refresh token → new pair
4. Call `POST /api/auth/logout` to blacklist tokens server-side

**Token blacklist:** In-memory `ConcurrentHashMap`, cleaned hourly. Not distributed — the blacklist is lost on server restart. Plan accordingly for multi-instance deployments.

**Public endpoints** (no JWT required):
- `/api/auth/**`, `/api/public/**`, `/api/features/**`, `/api/version/**`
- `/actuator/**`, `/error`, `/health`

---

## External Integrations

### Nightscout
Open-source CGM data aggregator, typically self-hosted by the user.

- **Auth:** `api-secret` header (SHA-1 hash) and/or `Authorization: Bearer <token>`
- **Endpoint called:** `GET {nightscoutUrl}/api/v2/entries.json?count=N`
- **Credentials:** stored per-user in `user_data_source_config`
- **Sync:** background scheduler polls every 5 minutes (adaptive backoff to 60 min on no change)

### Abbott LibreLinkUp
Abbott's cloud platform for Libre CGM readers.

- **Regional fallback:** automatically tries EU → US → AP → JP → AE hosts on 403/430 errors; never retries on 429
- **Auth token:** held in-memory (not persisted to DB)
- **Default host:** `https://api-eu.libreview.io` (override with `LIBRE_API_BASE_URL`)
- **Endpoints called:** `/llu/auth/login`, `/llu/connections`, `/llu/connections/{id}/graph`, `/user/profile`

### Ollama (LLM)
Powers the AI retrospective analysis.

- Both streaming and batch modes are supported
- Falls back to a rules-only response if Ollama is unreachable
- Supports conversation history (up to 12 turns) and per-request model/context-window override
- Default model: `glm-5:cloud` (configurable via `OLLAMA_MODEL`)

### Spoonacular
Nutrition data for meal analysis.

- Used by `POST /api/nutrition/analyze` to look up carbohydrates, fiber, protein, and fat per ingredient
- Requires `NUTRITION_SPOONACULAR_API_KEY`
- Configurable timeout (`NUTRITION_SPOONACULAR_TIMEOUT_MS`, default 3000 ms)

---

## Configuration & Environment Variables

| Variable | Default | Required | Description |
|---|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/glucose_monitor` | Yes (prod) | JDBC connection URL |
| `DB_USERNAME` | `glucose_monitor_user` | Yes (prod) | Database username |
| `DB_PASSWORD` | `glucose` | Yes (prod) | Database password |
| `JWT_SECRET` | insecure dev default | **Yes** | HS512 signing key — must be changed in production |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,...` | Yes (prod) | Comma-separated list of allowed frontend origins |
| `OLLAMA_ENABLED` | `true` | No | Enable/disable LLM |
| `OLLAMA_URL` | `https://ollama.com/api/generate` | No | Ollama API endpoint |
| `OLLAMA_MODEL` | `glm-5:cloud` | No | Model to use |
| `OLLAMA_API_KEY` | _(empty)_ | No | Bearer auth for Ollama (if required) |
| `OLLAMA_NUM_CTX` | `8192` | No | LLM context window size |
| `LIBRE_API_BASE_URL` | `https://api-eu.libreview.io` | No | LibreLinkUp base URL |
| `NUTRITION_SPOONACULAR_ENABLED` | `true` | No | Enable Spoonacular |
| `NUTRITION_SPOONACULAR_API_KEY` | _(empty)_ | Yes (if enabled) | Spoonacular API key |
| `NUTRITION_SPOONACULAR_BASE_URL` | `https://api.spoonacular.com` | No | Spoonacular base URL |
| `NUTRITION_SPOONACULAR_TIMEOUT_MS` | `3000` | No | Request timeout (ms) |
| `APP_FEATURES_NUTRITION_AWARE_PREDICTION_ENABLED` | `true` | No | Feature flag for nutrition-aware prediction |
| `NIGHTSCOUT_URL` | _(empty)_ | No | Global fallback Nightscout URL |
| `NIGHTSCOUT_API_SECRET` | _(empty)_ | No | Global fallback Nightscout secret |
| `NIGHTSCOUT_API_TOKEN` | _(empty)_ | No | Global fallback Nightscout token |
| `PORT` | `8080` | No | Server port (Render sets this automatically) |
| `TZ` | `Europe/London` | No | Jackson/JVM default timezone |
| `BUILD_NUMBER`, `GIT_COMMIT`, `BUILD_TIME` | dev defaults | No | Version metadata (set by CI) |

**Spring profiles:**
- _(none)_ — local development, H2 or local PostgreSQL
- `prod` — Render.com, Neon PostgreSQL
- `docker` — Docker Compose with service name `postgres`

---

## Database

**Engine:** PostgreSQL (Neon serverless in production)

**Migration tool:** Flyway (`classpath:db/migration`, `baseline-on-migrate: true`)

**DDL mode:** `validate` — all schema changes go through Flyway migrations, Hibernate never auto-creates tables.

**Connection pool:** HikariCP, `maximumPoolSize: 10`

### Migration History

| Version | Description |
|---|---|
| V1 | Initial schema: `users`, `carbs_entries`, `glucose_readings`, `insulin_doses`, `user_configurations`, `cob_settings`, `notes` |
| V2 | Add `nightscout_chart_data` |
| V3 | Add Nightscout config fields |
| V5 | Create `user_data_source_config` |
| V6 | Remove row limit on `nightscout_chart_data` (unlimited history) |
| V7 | `insulin_catalog` + `user_insulin_preferences`; seed FIASP, Apidra, Tresiba, Lantus |
| V8 | `user_glucose_sync_state` (adaptive sync tracking) |
| V9 | `clinical_knowledge_chunk` with initial 5 knowledge chunks |
| V10 | `ai_analysis_trace` (AI audit log) |
| V11–V12 | Mock notes seed data |
| V13–V14 | Clinical knowledge source metadata + additional entries |
| V15–V16 | `notes.nutrition_profile` JSON column |
| V17 | Fix Apidra code spelling in catalog |
| V18 | Migrate `insulin_catalog` to UUID primary key |
| V19 | Migrate `user_insulin_preferences` and `user_glucose_sync_state` to UUID PKs |

---

## Running Locally

### Prerequisites

- Java 21
- PostgreSQL 14+ (or Docker)
- Gradle (wrapper included)

### With Docker Compose

```bash
# Start PostgreSQL
docker compose up -d postgres

# Run the app with the docker profile
./gradlew bootRun --args='--spring.profiles.active=docker'
```

### Without Docker

```bash
# Create the database
createdb glucose_monitor
createuser glucose_monitor_user
psql -c "GRANT ALL ON DATABASE glucose_monitor TO glucose_monitor_user;"

# Run the app
./gradlew bootRun
```

### Minimum environment for local dev

```bash
export DATABASE_URL=jdbc:postgresql://localhost:5432/glucose_monitor
export DB_USERNAME=glucose_monitor_user
export DB_PASSWORD=glucose
export JWT_SECRET=your-local-dev-secret-at-least-64-chars-long
```

The app will start on `http://localhost:8080`. Flyway runs migrations automatically on startup.

---

## Deployment

### Render.com (production)

Defined in `render.yaml`:
- **Web service** — Java environment, `prod` Spring profile
- **PostgreSQL** — managed Render database

Required secrets to configure in the Render dashboard:
- `JWT_SECRET`
- `NUTRITION_SPOONACULAR_API_KEY`
- `CORS_ALLOWED_ORIGINS`
- `OLLAMA_URL` and `OLLAMA_API_KEY` (if using a hosted Ollama instance)

### Docker

```bash
# Build
docker build -t glucose-monitor-be .

# Run
docker run -p 8080:8080 \
  -e DATABASE_URL=jdbc:postgresql://host:5432/glucose_monitor \
  -e DB_USERNAME=... \
  -e DB_PASSWORD=... \
  -e JWT_SECRET=... \
  glucose-monitor-be
```

The Dockerfile uses a multi-stage build (`eclipse-temurin:21` builder → `eclipse-temurin:21-jre` runtime), runs as a non-root user (`appuser`, UID 1001), and exposes a health check via `wget /actuator/health`.
