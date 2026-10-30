# AI Assistant: Chat, Meal Prep and Retrieval — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the one-shot AI "retrospective" with an always-present dashboard chat that logs shorthand instantly, prepares meals (nutrition → model dose + forecast pause + curve → similar past meals → 20% history banner), scores frozen forecasts honestly, and answers questions from the user's own episodes and a cited clinical library, while removing the Experiments tab and duplicate pause/verification code.

**Architecture:** A new bounded context `che.glucosemonitorbe.assistant` (chat, quick log, meal plan, snapshots, episodes, retrieval) sits on top of the existing single sources: `NotesService`, `GlucoseCalculationsService` (Hovorka dashboard forecast with `prospectiveNotes`), `InsulinCalculatorService`, `HypoEventService`, `GlycemicEventService`, and the existing `ai` LLM gateway. All numbers come from backend code; the LLM only classifies, extracts food items and writes text guarded by a number check. iOS gets a custom chat overlay on the Dashboard tab that consumes an NDJSON stream.

**Tech Stack:** Spring Boot 3 / Java 21, Gradle, PostgreSQL 15 (dev, Homebrew) + pgvector, Flyway, Testcontainers, JUnit 5 + Mockito; OpenAI `text-embedding-3-small` (1536-d); SwiftUI (iOS 16.0 target), XCTest.

**Spec:** `glucose-monitor-be/docs/superpowers/specs/2026-10-30-ai-assistant-chat-and-retrieval-design.md` (read it with this plan; § numbers below refer to it). Dependency spec: `2026-10-30-dashboard-watch-strip-design.md`. Approved screens: `docs/superpowers/specs/assets/2026-10-30-assistant-screens.html` (screen numbers referenced in tasks).

## Global Constraints

- Backend glucose is mmol/L end to end; mg/dL only in iOS display (`AppState.preferredGlucoseUnit`).
- COB/IOB only from `GlucoseCalculationsService.activeCobIobInputs`; hypo thresholds only from `service/observer/HypoThresholds`; g/U only from `InsulinCalculatorService.resolveGramsPerUnit`; ISF only from `UserSettingsDTO.getEffectiveIsf(time)`.
- `user_settings.carb_ratio` is mmol/L rise per 10 g, not g/U.
- Correction target everywhere = `user_settings.target_glucose_mmol` (default 5.55, valid 4.4–10.0).
- No plan, dose, pause or banner when the newest stored CGM reading is older than 15 min or LibreLinkUp upstream is unavailable.
- Schema: columns on existing tables are added in the owning migration (V1); new tables only in new `V{n}__` files; every V1/V5/V6 edit needs `flyway repair` on the dev DB.
- The LLM never produces dose, pause, IOB, COB, forecast, history-dose or hit-% numbers. Food estimation (vision/nutrition services) is the only AI-produced number and is shown as an editable range.
- No provider/model override on the new assistant endpoint; the iOS Auto/Qwen/Ollama picker is removed.
- New Swift files must be added to `GlucoseMonitor.xcodeproj/project.pbxproj` by hand (PBXFileReference, group, PBXBuildFile, Sources phase).
- Keep files under 500 lines; mock-first unit tests (TDD London school) for new code.
- Secrets (OpenAI key) only via env vars in `application.yml`; never in source.

**Commands.** Backend: `cd glucose-monitor-be && ./gradlew test --tests '<FQN>'` (Docker must run for Testcontainers); full suite `./gradlew test`. iOS build: `cd glucose-monitor-iphone && xcodebuild -scheme GlucoseMonitor -project GlucoseMonitor.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 16,OS=18.3.1' -derivedDataPath /tmp/gm-derived CODE_SIGNING_ALLOWED=NO build > /tmp/gm-build.log 2>&1; echo $?` then `grep '\*\* BUILD SUCCEEDED \*\*' /tmp/gm-build.log`. iOS tests: same with `test -only-testing:GlucoseMonitorTests/<Class>`. Known pre-existing failures to ignore: `HypoPromptTests` (3), `BackendAPIBugTests.testJWTStorageUsesKeychain` under `CODE_SIGNING_ALLOWED=NO`. Never read `$?` after piping xcodebuild into `tail`.

## Review Focus

1. **Quick log while a low is open** — `15g correction` with an OPEN/CONFIRMED-unrecovered `hypo_events` row must attach to that event, not create a second rescue note. Test in Task 5.
2. **History dose on episodes that started high or with IOB** — must not double-count correction/IOB (overdose). Test in Task 21 (`startedHighEpisodeDoesNotRaiseDose`).
3. **Stale CGM during meal prep** — a photo sent when the newest reading is 20 min old returns nutrition only, no dose/pause/banner. Test in Task 14.
4. **Meal never eaten after "Took it"** — no meal note appears in COB; the dose gets its own snapshot; card goes amber after pause + 45 min. Tests in Tasks 10 and 15.
5. **Repeated carb advice while sugar is still landing** — a second "take 15 g" right after the first would stack into a rebound; must show "still landing" for 15 min. Test in Task 12b (`stillLandingWithin15MinOfFastCarbs`).
6. **LLM reply containing an invented number** — regenerated once, then replaced by the template. Test in Task 20.

---

## Phase 0 — Removals

### Task 1: Remove experiments and verification from the backend

**Files:**
- Delete: `service/ExperimentService.java`, `service/VerificationService.java`, `controller/ExperimentController.java`, `controller/VerificationController.java`, `scheduler/VerificationScheduler.java`, `entity/Experiment.java`, `entity/ExperimentReading.java`, `entity/VerificationEvent.java`, `entity/VerificationSummary.java`, `repository/ExperimentRepository.java`, `repository/VerificationEventRepository.java` (and any `VerificationSummaryRepository`, `ExperimentReadingRepository`), `dto/ExperimentResultDTO.java`, `dto/VerificationEventDTO.java`, `dto/VerificationSummaryDTO.java` and other DTOs used only by them; tests `service/VerificationServiceTest`, `service/ExperimentServiceMinimumDurationTest`, `service/ExperimentServiceBackgroundCheckTest`, `integration/ExperimentE2ETest`, `ExperimentFeatureFlagOffE2ETest`, `VerificationE2ETest`, `VerificationFeatureFlagOffE2ETest`; `scripts/invalidate_pre_tz_fix_verification_events.sql`.
- Modify: `service/NotesService.java:47,146-151` (remove `verificationService` field and enqueue block), `hovorka/HovorkaParameterService.java:85,159-201` (remove `experimentRepository`; `estimateEgpNet` → return `f01Abs`), `config/FeatureToggleConfig.java:16-17` and `service/FeatureToggleService.java:29` (remove `experiments-enabled`), `dto/UserSettingsDTO.java` (remove experiment references if only comments/fields for experiments), comments in `service/GlucoseCalculationsService.java:95-97,142,248`, `application.yml` (remove experiment/verification properties).
- Modify: `src/main/resources/db/migration/V5__experiments.sql`, `V6__verification_events.sql` → keep the files, replace contents with a one-line comment `-- Removed 2026-10-30: experiments dropped (see V13).`
- Create: `src/main/resources/db/migration/V13__drop_experiments_and_verification.sql` with `DROP TABLE IF EXISTS experiment_readings, experiments, verification_events, verification_summary CASCADE;`
- Test: `src/test/java/che/glucosemonitorbe/hovorka/HovorkaParameterServiceTest.java` (create or extend)

**Interfaces:** Produces: no experiment/verification beans; `NotesService` constructor without `VerificationService`.

- [ ] **Step 1:** `grep -rn "Experiment\|Verification" src/main src/test` and list every hit; each must be in the delete/modify lists above or added to them. Check `ai/ContextAggregatorService` and `glycemic/FeatureExtractor` for experiment/verification reads.
- [ ] **Step 2: Write test** `HovorkaParameterServiceTest.egpNetEqualsF01WithoutExperiments`: build params for a user with weight 70 → `params.egpNet() == params.f01()` (absolute, mmol/min).
- [ ] **Step 3:** Apply all deletions/modifications.
- [ ] **Step 4:** `./gradlew test` → all green (no compile errors, removed tests gone).
- [ ] **Step 5:** Dev DB: `./gradlew flywayRepair` (or the repair procedure in memory note *dev-db-is-native-pg14*), start the app once, confirm `\dt experiments` is gone in `glucose_monitor`.
- [ ] **Step 6: Commit** `refactor: remove experiments and verification (assistant design §6.12)`.

### Task 2: Remove the Experiments tab from iOS

**Files:**
- Delete: `GlucoseMonitor/Experiments/*` (all 14 files incl. `IsfMealWindowChart.swift`, `ExperimentAlarmManager.swift`) and their `project.pbxproj` entries.
- Modify: `GlucoseMonitor/ContentView.swift:25-27` (remove tab), `BackendAPI.swift` (remove experiment/verification calls and models), `AppState.swift` / `GlucoseMonitorApp.swift` (remove experiment alarm setup if any).
- Test: `GlucoseMonitorTests/*` — delete tests for removed types.

- [ ] **Step 1:** `grep -rn "Experiment\|Verification\|IsfMealWindowChart" GlucoseMonitor GlucoseMonitorTests GlucoseMonitorWidget Shared` → every hit removed or kept deliberately (the dashboard ISF suggestion banner in `ContentView.swift:144-160,334` stays).
- [ ] **Step 2:** Delete files and pbxproj entries; remove tab and API calls.
- [ ] **Step 3:** iOS build → `** BUILD SUCCEEDED **`; run `GlucoseMonitorTests` → only the known pre-existing failures.
- [ ] **Step 4: Commit** (iphone repo) `refactor: remove Experiments tab`.

---

## Phase 1 — Chat foundation

### Task 3: Settings, freshness and schema for chat

Skip any part already delivered by the watch-strip work (check V1 for `target_glucose_mmol` and for an existing freshness service first).

**Files:**
- Modify: `V1__baseline_schema.sql` — `user_settings`: `target_glucose_mmol DOUBLE PRECISION NOT NULL DEFAULT 5.55`, `rescue_carbs_g INTEGER NOT NULL DEFAULT 15`; `notes`: `carbs_low DOUBLE PRECISION`, `carbs_high DOUBLE PRECISION`, `context_tags JSONB`, `chat_message_id UUID`.
- Modify: `entity/UserSettings.java`, `dto/UserSettingsDTO.java` (+ validation 4.4–10.0 and 5–40), `entity/Note.java`, `dto/NoteDto.java`, `ai/ContextAggregatorService.java` (replace `CORRECTION_TARGET_MMOl` with the setting).
- Create: `V14__assistant_chat_message.sql` (table per spec §6.2: `chat_message(id UUID PK, user_id UUID NOT NULL FK users ON DELETE CASCADE, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), role VARCHAR(16) NOT NULL CHECK IN ('USER','ASSISTANT'), text TEXT, photo_key VARCHAR(500), intent VARCHAR(32), payload JSONB, note_ids JSONB, model_id VARCHAR(128), latency_ms BIGINT)`, index `(user_id, created_at DESC)`).
- Create: `assistant/chat/ChatMessage.java` (entity), `assistant/chat/ChatMessageRepository.java`, `assistant/common/ContextTag.java` (enum: `HOT_SHOWER, CAFFEINE, ALCOHOL, SEX, STRESS, ILLNESS, PEN_SUSPECT`), `assistant/common/CgmFreshnessService.java`.
- Test: `assistant/common/CgmFreshnessServiceTest.java`, `assistant/chat/ChatMessageRepositoryIT.java`.

**Interfaces:**
- Produces: `CgmFreshnessService.freshReading(UUID userId, LocalDateTime now): Optional<CgmReadingDTO>` (empty when newest `cgm_readings` row > 15 min old or `LibreLinkUpSyncService.upstreamStatus` not available); `ChatMessageRepository.findByUserIdAndCreatedAtBeforeOrderByCreatedAtDesc(UUID, Instant, Pageable)`.

- [ ] **Step 1: Tests:** `freshWhenReadingIs14MinOld` → present; `staleWhenReadingIs16MinOld` → empty; `staleWhenUpstreamDown` → empty. Repository IT: insert 60 messages, page of 50 before newest returns 50 in descending order.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement schema, entities, service.
- [ ] **Step 4:** Run tests + `ContextAggregatorServiceTest` → PASS; `flywayRepair` on dev DB.
- [ ] **Step 5: Commit** `feat(assistant): chat_message table, target setting, CGM freshness`.

### Task 4: Quick-log parser

**Files:** Create `assistant/quicklog/QuickLogParser.java`, `assistant/quicklog/QuickLogCommand.java`; Test `assistant/quicklog/QuickLogParserTest.java`.

**Interfaces:** Produces `QuickLogParser.parse(String text, LocalDateTime now): Optional<QuickLogCommand>`; `record QuickLogCommand(Kind kind, Double units, Double carbs, LocalDateTime at)` with `enum Kind { RAPID, RAPID_WITH_MEAL, MEAL, FAST_SUGAR, LONG_ACTING }`.

- [ ] **Step 1: Table test** `@ParameterizedTest parsesShorthand` with rows (now = 2026-10-30T12:00):
  `"5u"`→RAPID 5.0 @12:00; `"5 u"`, `"5ед"`→RAPID 5; `"5u 50g"`, `"5u 50 carbs"`, `"5u 50г"`→RAPID_WITH_MEAL 5/50; `"50g"`, `"50 carbs"`→MEAL 50; `"15g correction"`, `"15g fast"`, `"15g hypo"`→FAST_SUGAR 15; `"18u T"`, `"18u tresiba"`→LONG_ACTING 18; `"5u at 11:30"`→RAPID @11:30; `"5u 20 min ago"`→RAPID @11:40; `"2.5u"`→RAPID 2.5.
  `@ParameterizedTest returnsEmpty`: `"prepare me for soup"`, `"why am I high"`, `"5"`, `""`, `"u5"`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement (case-insensitive regex; comma or space separators; a future time → empty). **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(assistant): quick-log shorthand parser`.

### Task 5: Note-saving facade used by the assistant

**Files:** Create `assistant/quicklog/AssistantNoteWriter.java`; Test `assistant/quicklog/AssistantNoteWriterTest.java`.

**Interfaces:**
- Consumes: `NotesService.createNote/deleteNote`, `HypoEventService` (open-event lookup + rescue logging as `HypoPromptView` uses at `HypoEventService:296`), `UserSettingsService`.
- Produces: `AssistantNoteWriter.write(UUID userId, QuickLogCommand cmd, UUID chatMessageId): WriteResult` (`record WriteResult(List<UUID> noteIds, UUID hypoEventId)`); `undo(UUID userId, WriteResult r)`; throws `QuickLogRejectedException(String reason)`.

- [ ] **Step 1: Tests (mocks):** `fastSugarAttachesToOpenHypoEvent` (open event → `HypoEventService` rescue called, `NotesService.createNote` not called directly); `fastSugarWithoutOpenEventCreatesHypoTreatmentNote`; `rapidWithMealCreatesOneNoteWithBoth`; `longActingUsesTypeLongActing` (`Note.TYPE_LONG_ACTING`); `doseAboveMaxBolusRejected` (max bolus from `InsulinCalculatorService.maxBolusUnits` rule, expose a public accessor if private); `carbsAbove300Rejected`; `undoDeletesCreatedNotes`.
- [ ] **Step 2:** FAIL. **Step 3:** Implement; set `chat_message_id` on created notes. **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(assistant): note writer with hypo-event attach and undo`.

### Task 6: Assistant endpoint, routing and streaming

**Files:**
- Create: `assistant/chat/AssistantController.java`, `assistant/chat/AssistantService.java`, `assistant/chat/AssistantEvent.java` (sealed NDJSON event types), `assistant/chat/ChatHistoryService.java`.
- Modify: `ai/AiInsightService.java` / `ai/LlmGatewayService.java` — add an entry point that takes conversation turns from `chat_message` and has no provider/model override; keep existing methods until Task 8 retires them.
- Test: `assistant/chat/AssistantServiceTest.java`, `integration/AssistantChatE2ETest.java`.

**Interfaces:**
- Produces REST: `POST /api/assistant/messages` (multipart: `text`, optional `photo`, `clientTime`, `timezone`) → `application/x-ndjson` stream of `AssistantEvent`; `GET /api/assistant/messages?before=<ISO instant>&limit=50` → `List<ChatMessageDTO>`; `POST /api/assistant/messages/{id}/undo`.
- `sealed interface AssistantEvent permits Logged, Plan, Episodes, Banner, Token, Citations, Done, Error` — each a record with a `type` discriminator string (`"logged"`, `"plan"`, `"episodes"`, `"banner"`, `"token"`, `"citations"`, `"done"`, `"error"`). `Logged(List<UUID> noteIds, String summary, double iob, Double forecastMin)`.
- `AssistantService.handle(UUID userId, IncomingMessage msg, Consumer<AssistantEvent> sink)`; route order: `QuickLogParser` → (Task 15 adds meal prep) → question path.

- Question-path replies are in the language of the user's message (Russian or English); state this in the system prompt and pass the detected language.

- [ ] **Step 1: Tests:** `quickLogNeverCallsLlm` (verify zero interactions with `LlmGatewayService`, emits `Logged` then `Done`); `unparsedTextGoesToQuestionPath`; `userAndAssistantMessagesPersisted` (two `chat_message` rows, note_ids filled); E2E: POST `"5u 50g"` → stream contains `logged`, note exists with carbs 50 insulin 5; GET history returns both messages.
- [ ] **Step 2:** FAIL. **Step 3:** Implement; question path uses last 10 turns and current context. **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(assistant): chat endpoint with quick-log fast path and history`.

### Task 7: iOS chat overlay

**Files:**
- Create: `GlucoseMonitor/Chat/ChatOverlayView.swift` (peek + expanded, drag), `Chat/ChatViewModel.swift`, `Chat/ChatMessageViews.swift` (bubbles, Logged row with Undo), `GlucoseMonitor/BackendAPI+Assistant.swift` (multipart POST, NDJSON line decoding into `AssistantEvent` enum, history GET).
- Modify: `ContentView.swift` (`DashboardView` hosts overlay as a `ZStack` bottom layer, not `.sheet`; existing sheets present above it).
- Test: `GlucoseMonitorTests/AssistantEventDecodingTests.swift`, `ChatViewModelTests.swift`.

**Interfaces:** Consumes Task 6 REST. Produces `ChatViewModel.send(text:photo:)`, `loadOlder()`; header shows reading + IOB/COB from `appState.calculations` (hit % added in Task 12).

- [ ] **Step 1: Tests:** decode each event type from fixture NDJSON lines (unknown `type` → ignored, not crash); `loadOlderPrependsAndKeepsAnchor` (older page inserted before, anchor id unchanged); glucose shown in mg/dL when unit is mg/dL.
- [ ] **Step 2:** FAIL. **Step 3:** Implement; peek shows input bar + last message line; dragging up expands to full height; hidden while a dashboard sheet is presented is not required. **Step 4:** tests PASS, build SUCCEEDED.
- [ ] **Step 5: Commit** (iphone) `feat: dashboard chat overlay`.

### Task 8: Retire the old insights sheet; move glycemic prompts into chat

**Files:**
- Modify iOS: remove `AIInsightsSheet` (`DashboardExtras.swift:592-…`) and its entry point and the provider picker (`ai_provider` UserDefaults); remove `GlycemicEventPromptCard` from the dashboard and render the pending prompt in `ChatOverlayView` as a pinned assistant bubble with the same answer buttons, calling the existing `AppState+Glycemic` confirm/dismiss methods; the peek line shows the prompt text. After an answer, append an assistant confirmation bubble (client-side) and POST nothing new.
- Modify backend: delete `/api/ai-insights/retrospective*` from `AiInsightController` and the retrospective methods in `AiInsightService`/`LlmGatewayService` no longer called (keep what Task 6 uses); remove provider override plumbing.
- Test: iOS `ChatViewModelTests.pendingGlycemicPromptRenderedAsBubble`; backend: delete/adjust `AiInsightServiceTest`, `LlmGatewayServiceTest` cases for removed methods.

- [ ] **Step 1:** Write the iOS test (pending prompt from AppState appears as last bubble; answering calls confirm with category/carbs/insulin).
- [ ] **Step 2:** FAIL. **Step 3:** Implement removals and move. **Step 4:** both repos build and test green (`HypoPromptView` unchanged).
- [ ] **Step 5: Commit** both repos `refactor: glycemic prompts in chat; retire retrospective insights`.

---

## Phase 2 — Frozen forecasts and the hit percentage

### Task 9: forecast_snapshot table and freezing

**Files:**
- Create: `V15__forecast_snapshot.sql` (`forecast_snapshot(id UUID PK, user_id UUID NOT NULL FK, note_id UUID NOT NULL, made_at TIMESTAMPTZ NOT NULL, engine VARCHAR(32) NOT NULL, model_version VARCHAR(64), start_glucose DOUBLE PRECISION, points JSONB NOT NULL, check60_actual, check60_hit, check120_actual, check120_hit, check240_actual, check240_hit, complete BOOLEAN NOT NULL DEFAULT FALSE, excluded BOOLEAN NOT NULL DEFAULT FALSE)`, unique `(note_id)`, index `(user_id, made_at DESC)`).
- Create: `assistant/snapshot/ForecastSnapshot.java`, `ForecastSnapshotRepository.java`, `ForecastSnapshotService.java`.
- Modify: `NotesService.createNote/updateNote/deleteNote`, `HypoEventService` (rescue logging), `GlycemicEventService` (`:203` note creation) — call the service after commit (`TransactionSynchronization.afterCommit` or `@TransactionalEventListener`); failures logged, never block the save.
- Test: `assistant/snapshot/ForecastSnapshotServiceTest.java`.

**Interfaces:** Produces `ForecastSnapshotService.onNoteSaved(UUID userId, UUID noteId, LocalDateTime savedAt)`, `onNoteUpdated(UUID userId, UUID noteId, LocalDateTime originalSavedAt, LocalDateTime now)`, `onNoteDeleted(UUID noteId)`. Anchor = rapid dose > 0, or carbs ≥ 10, or fast sugar / hypo treatment. Points = the dashboard forecast from `GlucoseCalculationsService.calculateGlucoseData` at save time (5-min points to +240).

- [ ] **Step 1: Tests:** `anchorNoteGetsSnapshot`; `basalOnlyNoteGetsNone`; `backdatedMoreThan10MinGetsNone` (note time 11:45, saved 12:00); `editWithin15MinRefreezes`; `editAfter15MinMarksExcluded`; `deleteRemovesSnapshot`; `saveSucceedsWhenSnapshotThrows`.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS + existing `NotesService` tests PASS.
- [ ] **Step 5: Commit** `feat(assistant): freeze forecast snapshots on anchor notes`.

### Task 10: Check job and hit rate

**Files:** Create `assistant/snapshot/SnapshotCheckScheduler.java`, `assistant/snapshot/HitRateService.java`; Test `HitRateServiceTest.java`, `SnapshotCheckSchedulerTest.java`.

**Interfaces:** Produces `HitRateService.hitRate(UUID userId, LocalDateTime now): HitRate` (`record HitRate(int hits, int checks, Double percent)`; percent null when checks = 0); scheduler `@Scheduled(fixedDelay = 15 min)` fills checks with `EmpiricalTwinEngine.isHit(predicted, actual)`; CGM gap at a check minute (no reading within ±5 min) leaves it null; `complete` when +240 is filled or 6 h elapsed.

- [ ] **Step 1: Tests:** `fillsChecksWhenCgmExists`; `gapLeavesCheckNull`; `hitRateUses60And120Only` (a +240 miss does not change percent); `windowIs14Days` (snapshot made 15 days ago ignored); `excludedNotCounted`; `snapshotForDoseWhoseMealNeverLoggedIsScored` (Review Focus 4).
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(assistant): snapshot checks and 14-day hit rate`.

### Task 11: Hit-rate API and TwinForecast on snapshots

**Files:**
- Modify: `controller/TwinForecastController.java`, `service/TwinForecastHistoryService.java` → anchors and per-anchor forecast served from `forecast_snapshot` (same `AnchorForecastDTO` shape; `forecast` = frozen points, `checks` = stored checks). Keep the recompute logic as an internal method used only by tests/backtest (no endpoint).
- Create: `GET /api/assistant/hit-rate` in `AssistantController` → `HitRate`.
- Test: `integration/TwinForecastSnapshotE2ETest.java`.

- [ ] **Step 1: Test:** save a note, then advance CGM fixtures; GET `/api/twin/anchors/{noteId}` returns the frozen points even after user ISF is changed.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(assistant): twin forecast view reads frozen snapshots`.

### Task 12: iOS hit % header

**Files:** Modify `Chat/ChatOverlayView.swift` header ("Twin 74%", tap opens `TwinForecastView`), `BackendAPI+Assistant.swift` (`hitRate()`); `TwinForecastView.swift` unchanged apart from copy if needed. Test `ChatViewModelTests.hitRateHiddenWhenNoChecks`.

- [ ] Steps: test → FAIL → implement → build/test green → commit (iphone) `feat: hit rate in chat header`.

---

## Phase 2b — Fast carbs in advance

### Task 12b: Pre-emptive carb advice

**Files:**
- Create: `assistant/advice/CarbAdviceService.java`, `assistant/advice/CarbAdvice.java`, `assistant/advice/CarbAdviceHold.java` (per-user in-memory hold keyed by last stored reading time).
- Modify: `dto/GlucoseCalculationsResponse.java` (+ `carbAdvice`), `service/GlucoseCalculationsService.java` (compute after the forecast; skip for requests carrying `prospectiveNotes` so what-if calls never touch the hold).
- iOS: `Chat/CarbAdviceCardView.swift` (mockup screen 7: headline, min without/with carbs, both curves, recheck time, Took N g / Took other…), peek line headline in `ChatOverlayView`, decoding in `GlucoseMonitorAPI` response model (`Shared/` if the model lives there).
- Test: `assistant/advice/CarbAdviceServiceTest.java`, iOS `CarbAdviceDecodingTests.swift`.

**Interfaces:**
- Consumes: `CgmFreshnessService.freshReading`, the forecast points just computed, `GlucoseCalculationsService.calculateGlucoseData` with a prospective fast-sugar note (`hypo_treatment` rescue profile, `minutesAgo = 0`), `HypoThresholds`, open `hypo_events`.
- Produces: `CarbAdviceService.evaluate(UUID userId, LocalDateTime now, double currentGlucose, double trendMmolPerMin, List<PredictionPointDTO> forecast): Optional<CarbAdvice>`; `record CarbAdvice(State state, Integer grams, double minWithout, LocalDateTime minWithoutAt, Double minWith, List<PredictionPointDTO> curveWithout, List<PredictionPointDTO> curveWith, LocalDateTime recheckAt)`; `enum State { TAKE_NOW, STILL_LANDING }`. Constants: `TRIGGER_MIN_60 = 4.5`, `FALL_RATE = -0.1`, `TRIGGER_MIN_90 = 5.0`, `TARGET_MIN = 5.0`, grams candidates `5..40 step 5`, `LANDING_MINUTES = 15`.
- Later: the watch strip's URGENT_LOW carbs (if built after this) calls `CarbAdviceService` instead of its `carb_ratio` formula.

- [ ] **Step 1: Tests:** `triggersWhenForecastUnder4_5Within60`; `triggersOnFastFallWithForecastUnder5Within90` (trend −0.15, min 4.8 at +80); `noTriggerOnSlowFallStayingAbove5`; `picksSmallestGramsKeepingMinAbove5` (forecast mock: 5 g → 4.6, 10 g → 4.9, 15 g → 5.3 ⇒ 15); `capsAt40WithRecheck10Min`; `stillLandingWithin15MinOfFastCarbs` (no grams); `staleCgmNoAdvice`; `under3_9DefersToHypoFlow` (empty); `holdKeepsAdviceUntilTwoNewReadingsClear`; `prospectiveRequestDoesNotTouchHold`.
- [ ] **Step 2:** FAIL. **Step 3:** Implement; "Took N g" on iOS posts `"<N>g fast"` through the assistant endpoint (quick-log path, Task 5 attaches to an open hypo event). **Step 4:** backend PASS; iOS decoding test PASS, build SUCCEEDED.
- [ ] **Step 5: Commit** both repos `feat(assistant): fast carbs in advance on a falling forecast`.

---

## Phase 3 — Meal prep

### Task 13: Single forecast-based pause on the dashboard forecast

**Files:**
- Create: `assistant/mealplan/PreBolusPauseOptimizer.java`.
- Modify: `service/GlucosePredictService.java` — delegate its advisory branch to the optimizer or delete the service and `GlucosePredictController` (no client calls `/api/predict`; builder's call, record it in the commit message); `service/nutrition/GlycemicPatternMatchingService.java:57` — stop setting `preBolusPauseMinutes`; remove `NutritionSnapshot.preBolusPauseMinutes` (keep `bolusStrategy` for the label).
- Test: `assistant/mealplan/PreBolusPauseOptimizerTest.java`.

**Interfaces:**
- Consumes: `GlucoseCalculationsService.calculateGlucoseData(GlucoseCalculationsRequest)` with `prospectiveNotes` (dose `minutesAgo = 0`; meal `minutesAgo = -pause`, macros via `nutritionProfileJson`), `PreBolusResolver.resolve(...)`.
- Produces: `PreBolusPauseOptimizer.choose(UUID userId, LocalDateTime now, double currentGlucose, double doseUnits, MealMacros meal): PauseChoice`; `record MealMacros(double carbs, double fat, double protein, double fiber, Double gi)`; `record PauseChoice(Integer pauseMinutes, List<PredictionPointDTO> curve, double peak, double min, String reason)` — `pauseMinutes` null when a pre-bolus is in flight or every candidate's min < 4.5 (reason says which).
- Candidates `{0, 5, 10, 15, 20, 25, 30}`; cost = the existing `timeWeightedMeanCost` (move it with the loop); reject candidates whose min < 4.5 mmol/L; ties → shorter.

- [ ] **Step 1: Tests:** `prefersLongerPauseForFastCarbs` (fixture: 60 g, GI 75, glucose 7.0 → pause ≥ 15); `neverPicksPauseWithMinUnder4_5`; `allCandidatesLow_returnsNullWithReason`; `preBolusInFlight_returnsNull`; `curveEqualsDashboardForecastForSameProspectiveNotes` (compare to a direct `calculateGlucoseData` call).
- [ ] **Step 2:** FAIL. **Step 3:** Implement; delete moved code. **Step 4:** PASS + `./gradlew test` green.
- [ ] **Step 5: Commit** `refactor: one forecast-based pre-bolus pause on the dashboard forecast`.

### Task 14: Meal plan service

**Files:** Create `assistant/mealplan/MealPlanService.java`, `assistant/mealplan/MealPlan.java`, `assistant/mealplan/NutritionRange.java`; Test `MealPlanServiceTest.java`.

**Interfaces:**
- Consumes: `CgmFreshnessService`, `GlucoseCalculationsService.activeCobIobInputs`, `InsulinCalculatorService.calculateRecommendedInsulin` (target = `target_glucose_mmol`, `activeInsulin` = IOB), `PreBolusPauseOptimizer`, `GlycemicPatternMatchingService` (label).
- Produces: `MealPlanService.plan(UUID userId, NutritionSnapshot nutrition, LocalDateTime now): MealPlan`; `record MealPlan(NutritionRange carbs, NutritionRange fat, NutritionRange protein, NutritionRange fiber, Double gi, String patternLabel, Double doseUnits, String refusalCode, PauseChoice pause, boolean stale)`; `record NutritionRange(double low, double mid, double high)` with `NutritionRange.of(double mid, Double confidence)`: w = clamp(0.5 × (1 − confidence), 0.10, 0.40), low = mid × (1 − w), high = mid × (1 + w); null confidence → w = 0.40.

- [ ] **Step 1: Tests:** `rangeFromConfidence` (mid 40, conf 0.9 → 36/40/44; conf 0.2 → 24/40/56; conf null → 24/40/56); `doseFromCalculatorWithTargetSetting` (verify calculator called with target 5.55 and IOB); `refusalSurfaced` (glucose 3.7 → `refusalCode = GLUCOSE_BELOW_SAFE_THRESHOLD`, no pause); `staleReadingGivesNutritionOnly` (Review Focus 3: `stale = true`, dose/pause null); `doseRoundedTo0_5`.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(assistant): meal plan from nutrition, calculator and pause`.

### Task 15: Meal-prep intent, Took it / Eating now

**Files:**
- Create: `assistant/chat/IntentClassifier.java` (one LLM call, JSON: `{intent: MEAL_PREP|EVENT|QUESTION, items:[{name, grams?}], tags:[ContextTag]}`), `assistant/mealplan/MealActionService.java`.
- Modify: `AssistantService` route: photo or `MEAL_PREP` → nutrition (`NutritionVisionService` for photo, `NutritionEnrichmentService`/OpenFoodFacts for items) → `MealPlanService` → emit `Plan` → explanation tokens. `EVENT` → emit a confirm proposal (note with `context_tags`), saved on confirm.
- REST: `POST /api/assistant/plans/{messageId}/took` (body: `doseUnits`, `pauseMinutes`) → saves dose note now, stores `tookAt` + planned meal in the message payload; `POST /api/assistant/plans/{messageId}/eating` (body: macros, editable) → saves meal note now; `POST /api/assistant/plans/{messageId}/skip`; `POST /api/assistant/events/{messageId}/confirm`.
- Test: `assistant/mealplan/MealActionServiceTest.java`, `integration/AssistantMealPrepE2ETest.java`.

**Interfaces:** `Plan` event carries `MealPlan` + `messageId`; payload state machine `PLANNED → TOOK → EATEN | SKIPPED`; `MealActionService.overdue(ChatMessage, LocalDateTime now): boolean` = state TOOK and now > tookAt + pause + 45 min.

- [ ] **Step 1: Tests:** `tookSavesDoseOnlyNoMealNote` (Review Focus 4: COB unchanged); `eatingSavesMealNoteAtTapTime`; `overdueAfterPausePlus45`; `doubleTookRejected`; E2E: POST text "prepare me for chicken soup with 40 g rye bread" with mocked LLM/nutrition → stream has `plan` whose `doseUnits` equals `calculateRecommendedInsulin` for the same inputs and whose curve equals the dashboard forecast.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS.
- [ ] **Step 5: Commit** `feat(assistant): meal-prep intent and pause-aware logging`.

### Task 16: iOS plan card and scanner in chat

**Files:**
- Create: `Chat/MealPlanCardView.swift` (macros with ranges, editable; dose; pause; forecast curve with cone; pattern label; Took it / Edit / Not eating; countdown → Eating now; amber overdue state).
- Modify: `ChatOverlayView` camera button presents the existing scanner flow (extract capture part of `FoodScanSheet` in `NotesView.swift:976-1156` into `FoodCaptureFlow` reused by chat; result posted to the chat as a photo/nutrition message); remove the scan entry and the GI pre-bolus banner (`NotesView.swift:435-437,665-690`) from the Notes editor; reuse `PreBolusTimer` for the countdown.
- Test: `GlucoseMonitorTests/MealPlanCardTests.swift` (decoding, range display, amber state from `tookAt`).

- [ ] Steps: tests → FAIL → implement → build/test green → commit (iphone) `feat: meal plan card and scanner in chat`.

---

## Phase 4 — Episodes and similarity

### Task 17: pgvector and the embedding gateway

**Files:**
- Modify: `V1__baseline_schema.sql` (`CREATE EXTENSION IF NOT EXISTS vector;` after `pgcrypto`); all Testcontainers images `postgres:16-alpine` → `pgvector/pgvector:pg16` (27 test files; introduce `src/test/java/che/glucosemonitorbe/TestPostgres.java` with `public static final String IMAGE` and use it); `docker-compose.yml` and Render/Neon notes (`NEON.md`, `render.yaml`) — pgvector-enabled image/extension; `application.yml`: `app.ai.embeddings.url: ${OPENAI_EMBEDDINGS_URL:https://api.openai.com/v1}`, `model: ${OPENAI_EMBEDDINGS_MODEL:text-embedding-3-small}`, `dimensions: 1536`, `api-key: ${OPENAI_API_KEY:}`; `build.gradle` (`com.pgvector:pgvector` for JDBC type, if used).
- Create: `ai/embedding/EmbeddingGateway.java`, `ai/embedding/OpenAiEmbeddingGateway.java`.
- Test: `ai/embedding/OpenAiEmbeddingGatewayTest.java` (MockRestServiceServer).

**Interfaces:** Produces `EmbeddingGateway.embed(List<String> texts): List<float[]>` (throws `EmbeddingUnavailableException`); `int dimensions()`.

- [ ] **Step 1:** Dev DB: `brew install pgvector` and confirm `CREATE EXTENSION vector` works in `glucose_monitor` on Postgres 15 (if the formula targets another Postgres version, build pgvector from source against `pg_config` of postgresql@15). Record the outcome in the memory note *dev-db-is-native-pg14*.
- [ ] **Step 2: Tests:** request body has `model` and `input`; response vectors parsed in input order; 5xx → `EmbeddingUnavailableException`; missing API key → exception without a call.
- [ ] **Step 3:** FAIL → implement → PASS; `./gradlew test` green with the new image.
- [ ] **Step 4: Commit** `feat(ai): pgvector and OpenAI embedding gateway`.

### Task 18: Meal episodes

**Files:** Create `V16__meal_episode.sql`, `assistant/episode/MealEpisode.java`, `MealEpisodeRepository.java`, `MealEpisodeBuilder.java`, `MealEpisodeScheduler.java` (hourly: build episodes whose 4 h window closed; one-off backfill when the table is empty for a user); Test `MealEpisodeBuilderTest.java`.

Schema (`meal_episode`): id, user_id FK, note_id UNIQUE, meal_at, local_minute_of_day INT, weekday INT, carbs, fat, protein, fiber, gi, food_text TEXT, dose_units, pause_minutes, start_glucose, start_trend, iob, cob, activity_minutes_around INT, activity_type, site_zone, context_tags JSONB, peak, peak_minute, nadir, nadir_minute, g60, g120, g240, minutes_below_3_9, minutes_above_10, hit60, hit120, contaminated BOOLEAN, embedding vector(1536), created_at. Index `(user_id, meal_at)`; HNSW or IVFFlat index on embedding (`vector_cosine_ops`).

**Interfaces:** Produces `MealEpisodeBuilder.build(Note meal, List<Note> surrounding, List<CgmReading> cgm, UserSettingsDTO s): Optional<MealEpisode>` (empty when carbs < 10 or no start reading); `static String foodText(MealEpisode e)` e.g. `"lunch, chicken soup with potato, rye bread 40 g, after a walk"`.

- [ ] **Step 1: Tests:** `outcomeFieldsFromCgm` (fixture curve → peak/nadir/g60/g120/g240, minutes below/above); `contaminatedWhenExtraDoseWithin4h`; `contaminatedWhenExtraCarbsWithin4h`; `notContaminatedByOwnDoseWithin30MinBefore`; `under10gIgnored`; `foodTextIncludesActivity`.
- [ ] **Step 2:** FAIL → implement (embedding filled by scheduler via `EmbeddingGateway`, null on failure and retried next run) → PASS.
- [ ] **Step 3: Commit** `feat(assistant): meal episode store with backfill`.

### Task 19: Similar-episode search

**Files:** Create `assistant/episode/EpisodeSearchService.java`, `assistant/episode/Situation.java`; Test `EpisodeSearchServiceTest.java` (unit, scoring) and `EpisodeSearchIT.java` (Testcontainers + pgvector).

**Interfaces:** Produces `EpisodeSearchService.similar(UUID userId, Situation s, int limit): List<ScoredEpisode>`; `record Situation(String foodText, MealMacros macros, int minuteOfDay, double glucose, double iob, int activityMinutes)`; `record ScoredEpisode(MealEpisode episode, double score)`. Score = `0.4 × cosine + 0.4 × (1 − macroDistance) + 0.2 × (1 − situationDistance)`, distances normalised to [0,1] (macro: per-macro |Δ| / max(a, b, 10 g) averaged; situation: time-of-day band diff / 12 h, glucose |Δ| / 10, IOB |Δ| / 5, activity |Δ| / 120, averaged). No embedding (gateway down or episode not embedded) → text term dropped, the other two re-weighted to 0.5/0.5. Candidate pool: pgvector top 50 by cosine, plus top 50 by macro distance in SQL, deduplicated.

- [ ] **Step 1: Tests:** fixtures where soup+bread at lunch ranks above juice with equal carbs; `fallsBackWithoutEmbedding`; `onlySameUser`; IT returns expected top-3 ids.
- [ ] **Step 2:** FAIL → implement → PASS. **Step 3: Commit** `feat(assistant): similar-episode search`.

### Task 20: Facts payload, number guard, episode cards

**Files:**
- Create: `assistant/chat/FactsPayload.java` (record; serialised into the prompt), `ai/NumberGuard.java`.
- Modify: `ai/SafetyAndScoringService.java` (call `NumberGuard`), `AssistantService` (emit `Episodes` with top 5 for plans and questions; build `FactsPayload` with state, plan, episodes, banner, citations, last 10 turns), `ai/AiAnalysisTraceService` (record evidence ids).
- Question retrieval (spec §6.7 last paragraph): `IntentClassifier` also returns optional filters `{fromDate, toDate, foodWords[], eventKind: MEAL|CORRECTION|ACTIVITY|LOW}`; meals come from `EpisodeSearchService`, corrections/activity from `forecast_snapshot` anchors with their notes, lows from `hypo_events`, each filtered by those fields and capped at 5 per kind.
- iOS: `Chat/EpisodeCardView.swift`, decoding for `episodes`.
- Test: `ai/NumberGuardTest.java`, `AssistantServiceTest.inventedNumberRegeneratedThenTemplated` (Review Focus 5).

**Interfaces:** `NumberGuard.check(String reply, FactsPayload facts): GuardResult` (`record GuardResult(boolean ok, List<String> offending)`): extracts numbers; each must match a payload number within rounding (±0.05 for one-decimal, ±0.5 for integers) directly or via ×18.016 (mmol↔mg/dL); times (HH:mm) and dates are checked against payload times. On fail: one regenerate; second fail → template from payload.

- [ ] **Step 1: Tests:** `allowsPayloadNumbersAndRounding` ("4 U, wait 15 min, peak ~10.4" with payload 4.0/15/10.37); `allowsMgdlConversion` (payload 10.0 mmol → "180" ok); `flagsInventedDose` ("take 6 U" with payload 4.0 → offending ["6"]); service test: LLM mock returns invented number twice → reply equals template, `Token` events carry template text.
- [ ] **Step 2:** FAIL → implement → PASS (backend + iOS decoding test). **Step 3: Commit** both repos `feat(assistant): facts payload, number guard, episode cards`.

---

## Phase 5 — History dose and banner

### Task 21: History dose

**Files:** Create `assistant/mealplan/HistoryDoseService.java`, `assistant/mealplan/HistoryDose.java`; Modify `MealPlanService` (attach banner), `config/FeatureToggleConfig` + `FeatureToggleService` (`history-dose-enabled`, default `true`); Test `HistoryDoseServiceTest.java`.

**Interfaces:**
- Consumes: `EpisodeSearchService.similar(..., 10)`, `UserSettingsDTO.getEffectiveIsf(time)`, target setting, today's calculator terms.
- Produces: `HistoryDoseService.evaluate(UUID userId, Situation s, double modelDose, double todayCorrection, double todayIob, double carbs): Optional<HistoryDose>`; `record HistoryDose(double units, double modelUnits, double deltaPct, List<UUID> episodeIds, String pendingIsfNote)`; present only when the banner should show.

Algorithm (spec §6.6), per qualifying episode i (not contaminated, has dose, start reading, complete 4 h CGM):
```
ideal_i = dose_i + (g240_i − target) / ISF_i
if nadir_i < 3.9: ideal_i = min(ideal_i, dose_i − (target − nadir_i) / ISF_i)
meal_i  = ideal_i − (start_i − target) / ISF_i + iob_i
drop if meal_i <= 0
upg_i   = meal_i / carbs_i
units   = roundHalf(median(upg) × carbs + todayCorrection − todayIob)
show iff count >= 3 AND |units − modelDose| / modelDose > 0.20 AND |units − modelDose| >= 0.5
```
`pendingIsfNote` set when `IsfMealWindowSuggestionService` has a pending suggestion for the current meal window.

- [ ] **Step 1: Tests:** `medianScaledToCurrentCarbs` (3 episodes → expected units); `startedHighEpisodeDoesNotRaiseDose` (Review Focus 2: episode starting at 12.0 with full correction landing on target → upg equals the meal-only part); `iobAddedBackPerEpisode`; `nadirRuleIsConservative`; `fewerThan3NoBanner`; `exactly20PctNoBanner`; `under0_5UNoBanner`; `negativeMealDropped`; `toggleOffNoBanner`; `pendingIsfSuggestionNoted`.
- [ ] **Step 2:** FAIL → implement → PASS. **Step 3: Commit** `feat(assistant): history dose and 20% banner condition`.

### Task 22: Banner, low re-check and iOS

**Files:** Modify `AssistantService` (emit `Banner` with both curves: model dose curve and history dose curve via `PreBolusPauseOptimizer` / dashboard forecast); REST `POST /api/assistant/plans/{messageId}/recheck` (body `doseUnits`) → `{curve, min, level: OK|AMBER|RED, refusalCode}` (RED when min < 3.9 within 4 h, AMBER when < 4.5; max-bolus and below-3.9 refusals apply); iOS `Chat/HistoryBannerView.swift` (both doses, episodes line, ISF note, Use / Keep buttons, nothing preselected, RED requires a second tap). Tests: `MealActionServiceTest.recheckRedBelow3_9`, `recheckAmberBelow4_5`, `recheckRefusesAboveMaxBolus`; iOS `HistoryBannerTests.redNeedsSecondTap`.

- [ ] Steps: tests → FAIL → implement → PASS/build → commit both repos `feat(assistant): history banner with low re-check`.

### Task 23: Backtest report

**Files:** Create `src/test/java/che/glucosemonitorbe/backtest/HistoryDoseBacktestTest.java` (tagged `backtest`, excluded from default `test` if the harness already uses a tag; reuse `BacktestHarness`): for each past meal episode with ≥ 3 earlier qualifying neighbours (only episodes before it), compute model dose and history dose and report which one's implied landing (`g240 + (actualDose − candidate) × ISF`) is closer to target; print counts and mean |landing − target| for both, and how often the banner would have shown.

- [ ] **Step 1:** Implement and run against the AZT1D seed users / dev data; paste the summary into the commit message. No pass/fail gate (toggle decision is the user's).
- [ ] **Step 2: Commit** `test(backtest): history dose vs model dose report`.

---

## Phase 6 — Clinical library

### Task 24: Clinical retrieval by embedding

**Files:**
- Modify: `V1__baseline_schema.sql` `clinical_knowledge_chunk` + `embedding vector(1536)`, `citation TEXT`, `chunk_index INT`, `source_doc_id VARCHAR(128)`, unique `(source_doc_id, chunk_index)`; `V4__seed_clinical_knowledge.sql` → keep only the 'General safety disclaimer' row; `entity/ClinicalKnowledgeChunk.java`.
- Replace: `ai/RagRetrieverService.java` → `retrieve(String query, AnalysisContext ctx): List<ClinicalKnowledgeChunk>` = embed query + situation text, cosine top 4 among `active`, plus the GENERAL row; on `EmbeddingUnavailableException` return only the GENERAL row.
- Modify: `AssistantService` question path (emit `Citations` with title, source, year, url), `FactsPayload` (passages with citation ids), `NumberGuard` (numbers in passages allowed only in sentences that cite them, i.e. contain `[n]`).
- Test: `ai/RagRetrieverServiceTest.java` (rewrite), `NumberGuardTest.passageNumberNeedsCitation`.

- [ ] Steps: tests → FAIL → implement → PASS → commit `feat(ai): clinical retrieval by embedding with citations`.

### Task 25: Ingestion script and citation chips

**Files:**
- Create: `scripts/clinical_ingest.py` (+ `scripts/clinical_sources.yaml`): YAML lists guideline sections (ADA Standards of Care, ISPAD; title, year, url, text or summary where licence forbids full text) and PubMed PMIDs (fetched via NCBI E-utilities `efetch`, abstracts only); chunks to ~300–500 words; embeds via OpenAI (`OPENAI_API_KEY`); upserts by `(source_doc_id, chunk_index)`; flags `--reembed`, `--dry-run`. Run with `python3 -I scripts/clinical_ingest.py --db $DATABASE_URL`.
- Initial PMID/section list covering: exercise with MDI, fat/protein dosing, alcohol, pre-bolus timing, hypo treatment (rule of 15), trend-arrow dosing risk (doi:10.1177/19322968211043162).
- iOS: `Chat/CitationChipsView.swift` (tappable, opens URL in Safari view).
- Test: `scripts` — `--dry-run` on a fixture YAML prints expected chunk counts; iOS decoding test for `citations`.

- [ ] Steps: implement → dry-run → real run on dev DB (check row count and a sample query in chat) → commit both repos `feat: clinical library ingestion and citation chips`.

---

## Finish

- [ ] Full backend `./gradlew test` green; iOS build + tests green (known pre-existing failures only).
- [ ] Manual check on simulator against the dev backend: peek visible over dashboard; `5u 50g` logs instantly with undo; photo meal prep shows plan + curve + episodes; hit % appears after checks fill; Experiments tab gone; glycemic prompt appears in chat; low prompt still on dashboard.
- [ ] Update memory notes: remove/adjust *cob-iob-single-source* (Experiments tab gone), *dev-db-is-native-pg14* (pgvector), add a note for the assistant architecture.
