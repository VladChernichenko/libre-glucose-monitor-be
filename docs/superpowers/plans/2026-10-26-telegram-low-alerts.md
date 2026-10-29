# Telegram Low Alerts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a user's glucose goes low, the backend reaches their phone through a Telegram bot. It repeats every 10 min until they tap OK, escalates to a linked contact, and sends an all-clear on recovery. No LLM, open app or iOS push is involved.

**Architecture:**
- **Trigger:** a new `alert` package listens for a `CgmReadingsIngested` event, published after `CgmReadingService.storeChartData` commits. `GlucoseAnomalyDetector.scan()` is a fallback.
- **Episodes:** each low is a row in `low_alert_episodes`. A 1-minute `LowAlertEscalationJob` drives repeats, escalation, re-arming, expiry and resolution from that row, so a restart loses nothing.
- **Delivery:** a `notify` package wraps the Telegram Bot API. It uses long polling, so it works on localhost, and sits behind a `NotificationChannel` interface that later features reuse.
- **iOS:** a Settings "Alerts" section, a night checklist and a dashboard banner.

**Tech Stack:** Spring Boot 3.5.5, Java 21, Postgres + Flyway, JPA/Lombok, JUnit 5, Mockito, Testcontainers (`postgres:16-alpine`); SwiftUI, XCTest.

**Spec:** `glucose-monitor-be/docs/superpowers/specs/2026-10-26-telegram-low-alerts-design.md`. Read it before each task; section numbers below (§7.3 etc.) refer to it.

**Branches:**
- Backend: create `feat/telegram-low-alerts` in `glucose-monitor-be` from the current `feat/injection-sites` HEAD. That branch owns the latest `V1__baseline_schema.sql` (`site_zone`), which Task 1 edits.
- iOS: do the same in `glucose-monitor-iphone`.
- Run backend tests with `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`. Testcontainers needs Docker running.

## Global Constraints

- Glucose is mmol/L everywhere in the backend. `cgm_readings.sgv` is mg/dL: convert with `GlucoseConversion.mgdlToMmol`. Show the user's unit only in message text.
- Default thresholds: low `3.9` mmol/L (70 mg/dL), recovery `4.5` mmol/L (81 mg/dL). These are per-user settings defaulting to `HypoThresholds.HYPO_MMOL` / `RECOVERY_MMOL`.
- Repeat interval: 10 min. Contact escalation: +30 min unacknowledged. Pause after OK or "I'm on it": 30 min, then the full cycle restarts if BG < recovery.
- Episode opens only from a reading at most 15 min old. Sensor message after 20 min without a reading during an episode. Episode `EXPIRED` after 2 h without a reading.
- New episode only after the previous one is RESOLVED or EXPIRED, and at least 15 min above recovery (`HypoThresholds.SUPPRESSION_MINUTES`).
- Heads-up: forecast < low within 30 min, at most one per 60 min, never while an episode is open.
- Rescue hint grams: `rescue_carbs_min_g`–`rescue_carbs_max_g`, defaults 15–20.
- Link codes: ≥ 128 bits random, URL-safe, expire after 15 min, single use. At most 1 `SELF` link and 3 `CONTACT` links per user. A contact link requires `alert_display_name`.
- After 3 consecutive delivery failures to a link, mark it `FAILING`.
- The bot token comes only from env `TELEGRAM_BOT_TOKEN`. It must never appear in logs or commits. Telegram alerts are on only when the token is set. Under the `prod` profile with `app.telegram.enabled=true` and no token, refuse to start.
- The urgent low must not depend on `app.features.glucose-calculations-enabled`, the forecast, IOB, or any LLM.
- Schema: new columns on `user_settings` go into `V1__baseline_schema.sql`, which needs `flyway repair` on the dev DB. New tables go in new files `V13`–`V16`.
- Message languages `en` and `ru`, fallback `en`.

## Review Focus

1. **Two evaluations racing to open an episode** (the event listener and the 5-min fallback scan at the same time) must produce exactly one episode and one urgent message → test in Task 7.
2. **Backfilled old readings:** a LibreLinkUp catch-up sync inserting a 3-hour-old 3.2 reading must not open an episode or send anything → test in Task 7.
3. **A button press from a chat that isn't linked to that episode** (a removed contact, someone forwarding the message, a stranger) must change nothing → test in Task 8.
4. **Telegram rejecting delivery:**
   - 403 (the user blocked the bot) counts toward `FAILING` without crashing the job;
   - 429 (rate limit) is retried on the next run and is not counted as a failure;
   - test in Task 3 for the client classification, and in Task 8 for the job.
5. **Replaying or stealing a link code:** a second `/start` with the same code, or a code that has expired, links nothing and replies "link expired" → test in Task 4.

---

## File Structure (backend, under `src/main/java/che/glucosemonitorbe/`)

| File | Responsibility |
|---|---|
| `notify/telegram/TelegramProperties.java` | `@ConfigurationProperties("app.telegram")`: `enabled`, `botToken`, `botUsername`, `pollTimeoutSeconds` (25), `hashKey` |
| `notify/telegram/TelegramClient.java` | Bot API HTTP calls; redacted logging; failure classification |
| `notify/telegram/TelegramUpdatePoller.java` | `getUpdates` long-poll loop; routes `/start` and callbacks |
| `notify/NotificationChannel.java`, `notify/OutgoingMessage.java`, `notify/SendResult.java` | Channel-neutral send API |
| `notify/telegram/TelegramNotificationChannel.java` | `NotificationChannel` over `TelegramClient` + links |
| `notify/TelegramLinkService.java` | Link codes, `/start` consumption, link CRUD, chat-id hashing |
| `entity/TelegramLink.java`, `entity/TelegramLinkCode.java`, `entity/LowAlertEpisode.java` + repositories | Persistence |
| `controller/TelegramController.java` | `/api/telegram/*` |
| `alert/CgmReadingsIngested.java` | Event record |
| `alert/LowAlertService.java` | Evaluate one user: open episode, urgent message, heads-up |
| `alert/LowAlertEscalationJob.java` | 1-min job: repeats, escalation, re-arm, sensor, expiry, resolution |
| `alert/LowAlertCallbackHandler.java` | OK / "I'm on it" button presses |
| `alert/AlertMessageFormatter.java` | Message text in en/ru and the user's unit |
| `alert/AlertHeartbeat.java` | Dead-man's switch ping |
| `config/ClockConfig.java` | `@Bean Clock clock()` = `Clock.systemDefaultZone()` (no Clock bean exists today) |

---

### Task 1: Schema, entities and repositories

**Files:**
- Modify: `src/main/resources/db/migration/V1__baseline_schema.sql` (`user_settings` block, lines ~40–58)
- Create: `src/main/resources/db/migration/V13__telegram_links.sql`, `V14__telegram_link_codes.sql`, `V15__low_alert_episodes.sql`, `V16__telegram_poll_state.sql`
- Create: `entity/TelegramLink.java`, `entity/TelegramLinkCode.java`, `entity/LowAlertEpisode.java`, `entity/TelegramPollState.java`
- Create: `repository/TelegramLinkRepository.java`, `repository/TelegramLinkCodeRepository.java`, `repository/LowAlertEpisodeRepository.java`, `repository/TelegramPollStateRepository.java`
- Modify: `entity/UserSettings.java`
- Test: `src/test/java/che/glucosemonitorbe/repository/LowAlertRepositoriesIntegrationTest.java` (copy the Testcontainers setup from `HypoEventRepositoryIntegrationTest`)

**Interfaces:**
- Produces:
  - `user_settings` columns:
    - `low_alert_enabled BOOLEAN NOT NULL DEFAULT TRUE`
    - `low_alert_mmol DOUBLE PRECISION NOT NULL DEFAULT 3.9`
    - `low_alert_recovery_mmol DOUBLE PRECISION NOT NULL DEFAULT 4.5`
    - `rescue_carbs_min_g INTEGER NOT NULL DEFAULT 15`
    - `rescue_carbs_max_g INTEGER NOT NULL DEFAULT 20`
    - `glucose_unit VARCHAR(4) NOT NULL DEFAULT 'MMOL'`, checked `IN ('MMOL','MGDL')`
    - `language VARCHAR(8) NOT NULL DEFAULT 'en'`
    - `alert_display_name VARCHAR(60)`
    - check `low_alert_mmol < low_alert_recovery_mmol`
  - `TelegramLink`:
    - fields `UUID id, UUID ownerUserId, Role role {SELF, CONTACT}, String chatId` (`@Convert(EncryptedStringConverter.class)`), `String chatIdHash, String displayName, Status status {ACTIVE, FAILING, REMOVED}, int failureCount, Instant linkedAt, Instant lastOkAt`;
    - FK to users with `ON DELETE CASCADE`; index on `chat_id_hash`; index on `(owner_user_id, status)`.
  - `TelegramLinkCode`: `String code` (PK), `UUID ownerUserId, TelegramLink.Role role, Instant expiresAt, Instant usedAt`.
  - `LowAlertEpisode`:
    - fields `UUID id, UUID userId, State state {OPEN, ACKNOWLEDGED, RESOLVED, EXPIRED}, Instant openedAt, double nadirMmol, double lastMmol, Instant lastReadingAt, Instant lastSentAt, int repeatCount, Instant cycleStartedAt, Instant contactsAlertedAt, Instant acknowledgedAt, UUID acknowledgedByLinkId, Instant sensorMessageAt, Instant resolvedAt, Instant recoveredAt`;
    - partial unique index `ux_low_alert_one_active ON low_alert_episodes(user_id) WHERE state IN ('OPEN','ACKNOWLEDGED')`.
    - `cycleStartedAt` is the start of the current cycle, so the contact escalation at +30 counts from it; `recoveredAt` is the first reading ≥ recovery.
  - `TelegramPollState`: single row `id = 1`, `long lastUpdateId`.
  - Repository methods:
    - `TelegramLinkRepository`: `Optional<TelegramLink> findFirstByOwnerUserIdAndRoleAndStatusNot(UUID, Role, Status)`, `List<TelegramLink> findByOwnerUserIdAndRoleAndStatus(UUID, Role, Status)`, `List<TelegramLink> findByChatIdHashAndStatusNot(String, Status)`, `long countByOwnerUserIdAndRoleAndStatusNot(UUID, Role, Status)`.
    - `LowAlertEpisodeRepository`: `Optional<LowAlertEpisode> findActiveByUserId(UUID)` (state in OPEN/ACKNOWLEDGED), `List<LowAlertEpisode> findByStateIn(Collection<State>)`, `Optional<LowAlertEpisode> findFirstByUserIdOrderByOpenedAtDesc(UUID)`, and `@Lock(PESSIMISTIC_WRITE) Optional<LowAlertEpisode> findLockedById(UUID)`.
    - `TelegramLinkCodeRepository`: `@Lock(PESSIMISTIC_WRITE) Optional<TelegramLinkCode> findLockedByCode(String)`.

- [ ] **Step 1: Write the failing tests** in `LowAlertRepositoriesIntegrationTest`:
  - `onlyOneActiveEpisodePerUser`: saving a second `OPEN` episode for the same user throws `DataIntegrityViolationException`. A second episode after the first is set `RESOLVED` saves fine.
  - `chatIdIsEncryptedAtRest`: save a link with `chatId = "123456789"`; a native query `SELECT chat_id FROM telegram_links` does not equal `"123456789"`; `findByChatIdHashAndStatusNot(hash, REMOVED)` returns it.
  - `userSettingsDefaults`: a new `UserSettings` row reads back `lowAlertEnabled = true, lowAlertMmol = 3.9, lowAlertRecoveryMmol = 4.5, rescueCarbsMinG = 15, rescueCarbsMaxG = 20, glucoseUnit = MMOL, language = "en", alertDisplayName = null`.
- [ ] **Step 2: Run, and verify the tests fail**
  - Run: `./gradlew test --tests '*LowAlertRepositoriesIntegrationTest'`
  - Expected: FAIL (compilation: types missing)
- [ ] **Step 3: Write the migrations, entities and repositories** listed under Interfaces. Use the `hypo_events` style: FK `ON DELETE CASCADE`, `CHECK` on enum columns, `COMMENT ON TABLE`.
- [ ] **Step 4: Run, and verify the tests pass.** Same command → PASS. Then run the full `./gradlew test` and confirm no other test broke.
- [ ] **Step 5: Repair the dev DB.** V1 changed, so run `flyway repair` (memory *dev-db-is-native-pg14*). Boot once and confirm the new columns exist (`\d user_settings`).
- [ ] **Step 6: Commit**: `feat(alerts): schema for Telegram links and low-alert episodes`

### Task 2: Alert settings through the settings API

**Files:**
- Modify: `dto/UserSettingsDTO.java`, `service/UserSettingsService.java` (copy-in block ~lines 105–141, to-DTO ~185)
- Test: `src/test/java/che/glucosemonitorbe/service/UserSettingsAlertFieldsTest.java`

**Interfaces:**
- Consumes: Task 1 `UserSettings` fields.
- Produces: `UserSettingsDTO` fields `Boolean lowAlertEnabled, Double lowAlertMmol, Double lowAlertRecoveryMmol, Integer rescueCarbsMinG, Integer rescueCarbsMaxG, String glucoseUnit ("MMOL"|"MGDL"), String language, String alertDisplayName`. Null in a request means "unchanged", matching the existing partial-update style.

- [ ] **Step 1: Write the failing tests:**
  - `partialUpdateLeavesAlertFieldsUnchanged`: saving with only `carbRatio` keeps alert defaults.
  - `rejectsLowNotBelowRecovery`: `lowAlertMmol = 4.6` with recovery 4.5 throws `IllegalArgumentException`.
  - `rejectsOutOfRangeLow`: `lowAlertMmol` outside 3.0–5.5, and rescue grams outside 5–40 or with min > max, are rejected.
  - `normalisesLanguage`: `"ru-RU"` is stored as `"ru"`; `"de"` is stored as `"en"`.
  - `trimsDisplayName`: blank becomes null, and longer than 60 characters is rejected.
- [ ] **Step 2: Run, and verify the tests fail.** `./gradlew test --tests '*UserSettingsAlertFieldsTest'` → FAIL
- [ ] **Step 3: Implement the copy-in, validation and to-DTO mapping** in `UserSettingsService`. Validate at this boundary; the controller already maps `IllegalArgumentException` to 400 (check `GlobalExceptionHandler`, and add the mapping if missing).
- [ ] **Step 4: Run, and verify the tests pass.** Same command → PASS
- [ ] **Step 5: Commit**: `feat(alerts): alert, unit and language fields in user settings`

### Task 3: Telegram client and configuration

**Files:**
- Create: `notify/telegram/TelegramProperties.java`, `notify/telegram/TelegramClient.java`, `config/ClockConfig.java`
- Modify: `src/main/resources/application.yml`: add `app.telegram.enabled: ${TELEGRAM_ENABLED:false}`, `bot-token: ${TELEGRAM_BOT_TOKEN:}`, `bot-username: ${TELEGRAM_BOT_USERNAME:}`, `hash-key: ${TELEGRAM_HASH_KEY:}`, `poll-timeout-seconds: 25`
- Modify: `config/SecretsStartupValidator.java`
- Test: `src/test/java/che/glucosemonitorbe/notify/telegram/TelegramClientTest.java` (MockWebServer or `MockRestServiceServer`, whichever the repo already uses; `grep -r MockRestServiceServer src/test` first)

**Interfaces:**
- Produces:
  - `TelegramClient`:
    - `SendOutcome sendMessage(String chatId, String text, List<InlineButton> buttons)`;
    - `void answerCallback(String callbackQueryId, String text)`;
    - `List<JsonNode> getUpdates(long offset, int timeoutSeconds)`.
  - `record InlineButton(String label, String callbackData)`.
  - `sealed interface SendOutcome` with variants:
    - `Sent(long messageId)`;
    - `Rejected(int status)` for 400/403, which count as delivery failures;
    - `RetryLater()` for 429, 5xx, timeouts and an open circuit, which are not counted.
  - `TelegramProperties.isActive()` = `enabled && botToken` not blank.
- Behaviour:
  - Base URL `https://api.telegram.org/bot<token>/`.
  - Connect timeout 5 s; read timeout `pollTimeoutSeconds + 10` s for `getUpdates`, 10 s otherwise.
  - Wrapped in `CircuitBreakerManager` under the name `"telegram"`.
  - Every log line uses `redact(url)`, which replaces the token with `***`.

- [ ] **Step 1: Write the failing tests:**
  - `sendMessagePostsJsonWithInlineKeyboard`: the request body has `chat_id`, `text`, `reply_markup.inline_keyboard[0][0].callback_data`.
  - `classifies403AsRejected`.
  - `classifies429And500AsRetryLater`.
  - `tokenNeverLogged`: capture logs with a Logback `ListAppender` during a failing call, and assert no event contains the token value.
  - In `SecretsStartupValidatorTest` (extend it if it exists): prod + `enabled=true` + empty token throws `IllegalStateException`; non-prod only warns.
- [ ] **Step 2: Run, and verify the tests fail.** `./gradlew test --tests '*TelegramClientTest' --tests '*SecretsStartupValidator*'` → FAIL
- [ ] **Step 3: Implement** `TelegramProperties`, `TelegramClient` (its own `RestTemplate` built with the timeouts above; not the shared bean), the `ClockConfig` bean, and the validator rule.
- [ ] **Step 4: Run, and verify the tests pass.** Same command → PASS
- [ ] **Step 5: Commit**: `feat(alerts): Telegram Bot API client with redacted logging`

### Task 4: Linking users and contacts

**Files:**
- Create: `notify/TelegramLinkService.java`, `controller/TelegramController.java`, `dto/TelegramLinksDTO.java`
- Test: `src/test/java/che/glucosemonitorbe/notify/TelegramLinkServiceTest.java` (Mockito), `src/test/java/che/glucosemonitorbe/integration/TelegramLinkApiE2ETest.java`

**Interfaces:**
- Consumes: Task 1 repositories, Task 2 `alertDisplayName`, Task 3 `TelegramProperties`, `TelegramClient`, `Clock`.
- Produces:
  - `TelegramLinkService`:
    - `LinkInvite createInvite(UUID userId, TelegramLink.Role role)`, where `record LinkInvite(String url, Instant expiresAt)` and `url = "https://t.me/" + botUsername + "?start=" + code`;
    - `StartResult consumeStart(String code, String chatId, String telegramDisplayName)`, where `StartResult` is an enum `{LINKED_SELF, LINKED_CONTACT, EXPIRED_OR_USED, LIMIT_REACHED}`;
    - `TelegramLinksDTO list(UUID userId)`;
    - `void remove(UUID userId, UUID linkId)`;
    - `String hashChatId(String chatId)` (HMAC-SHA256 with `hashKey`, hex);
    - `Optional<TelegramLink> activeSelf(UUID userId)`;
    - `List<TelegramLink> activeContacts(UUID userId)`.
  - `TelegramLinksDTO(SelfDTO self, List<ContactDTO> contacts, boolean botHealthy)`. `SelfDTO` / `ContactDTO` are `(UUID id, String status, String displayName, Instant linkedAt)`. `botHealthy` is filled by Task 9 and is `false` until then.
  - Endpoints (authenticated, user from `Authentication` as in other controllers):

    | Endpoint | Returns |
    |---|---|
    | `POST /api/telegram/link {role}` | `LinkInvite`; 409 when `role=CONTACT` and `alertDisplayName` is null; 503 when `!properties.isActive()` |
    | `GET /api/telegram/links` | `TelegramLinksDTO` |
    | `DELETE /api/telegram/links/{id}` | 204; 404 for someone else's link |
    | `POST /api/telegram/test` | 202; sends "✅ Test alert from Glucose Monitor" through `NotificationChannel` (Task 5) |

    Wire `POST /api/telegram/test` in Task 5.
- Rules:
  - A new `SELF` link replaces the old one, which becomes `REMOVED`.
  - CONTACT is capped at 3 non-removed links, otherwise `LIMIT_REACHED`.
  - Codes are 32 bytes from `SecureRandom`, Base64url without padding.

- [ ] **Step 1: Write the failing unit tests:**
  - `inviteUrlUsesBotUsernameAndExpiresIn15Min`.
  - `consumeStartLinksSelfAndMarksCodeUsed`.
  - `secondUseOfCodeIsExpiredOrUsed` (Review Focus 5).
  - `expiredCodeIsExpiredOrUsed` (clock at +15 min 1 s).
  - `newSelfLinkRemovesOldOne`.
  - `fourthContactIsLimitReached`.
  - `contactInviteRequiresDisplayName`.
- [ ] **Step 2: Write the failing E2E test:** `TelegramLinkApiE2ETest`. Mock `TelegramClient` with `@MockitoBean` and set `app.telegram.enabled=true` with dummy token and username via `@TestPropertySource`. Assert:
  - `POST /link` → 200 with a `t.me` url;
  - `consumeStart` is called directly with the code;
  - `GET /links` shows `self.status = ACTIVE`;
  - `DELETE` of another user's link → 404.
- [ ] **Step 3: Run, and verify the tests fail.** `./gradlew test --tests '*TelegramLink*'` → FAIL
- [ ] **Step 4: Implement** the service and the controller.
- [ ] **Step 5: Run, and verify the tests pass.** Same command → PASS
- [ ] **Step 6: Commit**: `feat(alerts): link Telegram chats for users and emergency contacts`

### Task 5: Notification channel and update poller

**Files:**
- Create: `notify/NotificationChannel.java`, `notify/OutgoingMessage.java`, `notify/SendResult.java`, `notify/telegram/TelegramNotificationChannel.java`, `notify/telegram/TelegramUpdatePoller.java`
- Modify: `controller/TelegramController.java` (the test endpoint)
- Test: `src/test/java/che/glucosemonitorbe/notify/telegram/TelegramNotificationChannelTest.java`, `TelegramUpdatePollerTest.java`

**Interfaces:**
- Consumes: Task 3 `TelegramClient`, `SendOutcome`, `InlineButton`; Task 4 `TelegramLinkService`.
- Produces:
  - `NotificationChannel`:
    - `SendResult send(TelegramLink link, OutgoingMessage message)`;
    - `record OutgoingMessage(String text, List<Button> buttons)`, `record Button(String label, String callbackData)`;
    - `enum SendResult { SENT, FAILED, RETRY }`.
  - `TelegramNotificationChannel`:
    - on `Sent`: reset `failureCount`, set `lastOkAt`, set status `ACTIVE` if it was `FAILING`;
    - on `Rejected`: `failureCount++`, and at 3 set `FAILING`;
    - on `RetryLater`: nothing changes.
  - `TelegramUpdatePoller`:
    - a `SmartLifecycle` that starts a single daemon thread only when `properties.isActive()`;
    - the loop: `getUpdates(offset, 25)` → for each update, route it, then persist `lastUpdateId` in `TelegramPollState`;
    - on exception, back off 5 s;
    - exposes `Instant lastSuccessfulPollAt()`.
  - Routing:
    - `message.text` starting `/start <code>` → `TelegramLinkService.consumeStart`. Reply per `StartResult`: "Connected. You'll get low alerts here." / "You're now an emergency contact for <alertDisplayName>." / "This link has expired. Request a new one in the app." / "This person already has 3 contacts."
    - `callback_query` → `CallbackRouter.route(String chatId, String callbackData, String callbackQueryId)`. `CallbackRouter` is an interface in `notify`, implemented in Task 8. Until then a no-op bean answers "OK".
    - Any other text → a short "This bot only sends alerts." reply.

- [ ] **Step 1: Write the failing tests:**
  - Channel: `rejectedThreeTimesMarksFailing`, `sentResetsFailures`, `retryLaterChangesNothing` (Review Focus 4, channel half).
  - Poller, calling its `processBatch(List<JsonNode>)` directly, not the thread:
    - `startCommandRoutesToLinkService`;
    - `callbackRoutesToRouter`;
    - `offsetAdvancesPastProcessedUpdates`;
    - `routingExceptionOnOneUpdateDoesNotStopOthers`.
- [ ] **Step 2: Run, and verify the tests fail.** `./gradlew test --tests '*TelegramNotificationChannelTest' --tests '*TelegramUpdatePollerTest'` → FAIL
- [ ] **Step 3: Implement** the channel, the poller with `processBatch`, and `POST /api/telegram/test`. The test endpoint returns 409 when there is no active SELF link.
- [ ] **Step 4: Run, and verify the tests pass.** Same command → PASS
- [ ] **Step 5: Commit**: `feat(alerts): notification channel and Telegram update poller`

### Task 6: Message formatter

**Files:**
- Create: `alert/AlertMessageFormatter.java`, `alert/AlertMessageKind.java`, `src/main/resources/alerts/messages_en.properties`, `messages_ru.properties`
- Test: `src/test/java/che/glucosemonitorbe/alert/AlertMessageFormatterTest.java`

**Interfaces:**
- Produces:
  - `String format(AlertMessageKind kind, AlertMessageContext ctx)`.
  - `enum AlertMessageKind { URGENT, REPEAT, HEADS_UP, CONTACT, CONTACT_SELF_UNREACHABLE, ALL_CLEAR, SENSOR, EXPIRED, ACK_BY_CONTACT, TEST }`.
  - `record AlertMessageContext(String language, String glucoseUnit, double mmol, String arrow, Double iobUnits, int minutesLow, Integer staleMinutes, Double forecastMmol, Integer forecastInMinutes, int rescueMinG, int rescueMaxG, String displayName, String responderName)`.
  - `static String arrowFromDirection(String nightscoutDirection)`: maps `DoubleDown → ↓↓`, `SingleDown → ↓`, `FortyFiveDown → ↘`, `Flat → →`, `FortyFiveUp → ↗`, `SingleUp → ↑`, `DoubleUp → ↑↑`; null or unknown → `→`.
- Value format: mg/dL as an integer (`Math.round(mmol * 18.018)`); mmol/L to one decimal. IOB is omitted when null.
- The English copy follows spec §7.4 exactly:
  - Urgent: `⚠️ LOW {value} {unit} {arrow}` + ` · {iob} U active` when IOB is present + ` · Take {min}–{max} g fast sugar, recheck in 15 min.`
  - Repeat: `⚠️ Still low: {value} {unit} {arrow} ({minutes} min). Take {min}–{max} g fast sugar.`, with ` Last reading {stale} min ago.` appended when stale.
  - Heads-up: `↘️ Heading low: {value} {unit} {arrow}, forecast ~{forecast} in {in} min.`
  - Contact: `🚨 {name} has been low for {minutes} min: {value} {unit} {arrow}. They haven't answered.`
  - Contact, own alerts down: `🚨 {name} is low: {value} {unit} {arrow}. Their own alerts are not reaching them.`
  - All-clear: `✅ Back to {value} {unit}. Low lasted {minutes} min.`
  - Sensor: `❔ No sensor reading for 20 min during a low. Open Libre or send a fingerstick.`
  - Expired: `❔ No data for 2 h. Alerts resume with the next reading.`
  - Ack by contact: `{responder} is on it.`
  - Russian: translate with the same placeholders.

- [ ] **Step 1: Write the failing tests:**
  - `urgentMgdlWithIob`: 3.55 mmol, MGDL, `↓`, IOB 1.2, 15–20 → `"⚠️ LOW 64 mg/dL ↓ · 1.2 U active · Take 15–20 g fast sugar, recheck in 15 min."`
  - `urgentMmolWithoutIob` → `"⚠️ LOW 3.6 mmol/L ↓ · Take 15–20 g fast sugar, recheck in 15 min."`
  - `russianUrgentContainsValueAndGrams`.
  - `unknownLanguageFallsBackToEnglish`.
  - `arrowMapping` (a table of all seven directions plus null).
  - `repeatShowsStaleness`.
- [ ] **Step 2: Run, and verify the tests fail.** `./gradlew test --tests '*AlertMessageFormatterTest'` → FAIL
- [ ] **Step 3: Implement** the formatter, loading the properties through `ResourceBundle` (UTF-8).
- [ ] **Step 4: Run, and verify the tests pass.** Same command → PASS
- [ ] **Step 5: Commit**: `feat(alerts): low-alert message texts in en and ru`

### Task 7: Opening episodes (trigger, urgent message, heads-up)

**Files:**
- Create: `alert/CgmReadingsIngested.java`, `alert/LowAlertService.java`, `alert/LowAlertSender.java`
- Modify: `service/CgmReadingService.java` (inject `ApplicationEventPublisher`; after `repository.saveAll(toInsert)`, publish `new CgmReadingsIngested(userId, maxInsertedTimestamp)` when `toInsert` is non-empty)
- Modify: `service/observer/GlucoseAnomalyDetector.java` (`evaluateUser`: call `lowAlertService.evaluate(userId)` in its own try/catch **before** the `MIN_READINGS_FOR_ROC` early return)
- Test: `src/test/java/che/glucosemonitorbe/alert/LowAlertServiceTest.java` (Mockito + fixed `Clock`), `src/test/java/che/glucosemonitorbe/integration/LowAlertOpenE2ETest.java`

**Interfaces:**
- Consumes:
  - `CgmReadingRepository.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc`;
  - Task 1 `LowAlertEpisodeRepository`;
  - Task 2 settings via `UserSettingsService.getUserSettings`;
  - Task 4 `activeSelf` / `activeContacts`;
  - Task 5 `NotificationChannel`;
  - Task 6 formatter;
  - `GlucoseCalculationsService.activeCobIobInputs` (IOB, optional);
  - `GlucoseAlertEvaluator` path math for the heads-up (optional).
- Produces:
  - `record CgmReadingsIngested(UUID userId, long newestEpochMs)`.
  - `LowAlertService`:
    - `void evaluate(UUID userId)`;
    - `@TransactionalEventListener(phase = AFTER_COMMIT) void onIngested(CgmReadingsIngested e)` → `evaluate(e.userId())`, wrapped in try/catch.
  - `LowAlertSender`, shared with Task 8:
    - `void sendToSelf(LowAlertEpisode ep, AlertMessageKind kind, AlertMessageContext ctx, boolean withOkButton)`;
    - `void sendToContacts(LowAlertEpisode ep, AlertMessageKind kind, AlertMessageContext ctx, boolean withOnItButton)`;
    - `AlertMessageContext context(UUID userId, LowAlertEpisode ep, double mmol, String direction, Integer staleMinutes)`.
  - Callback data format: `ack:<episodeId>` for OK, `onit:<episodeId>` for "I'm on it".
- `evaluate` rules:
  1. Skip if `lowAlertEnabled == false`.
  2. Take the newest reading in the last 2 h. If it is older than 15 min, there is no urgent action.
  3. Newest < `lowAlertMmol`, no active episode, and the last RESOLVED/EXPIRED episode's `recoveredAt` (or `resolvedAt`) is at least 15 min ago → insert an `OPEN` episode with `cycleStartedAt = openedAt = now`. On `DataIntegrityViolationException`, re-read and continue: someone else opened it.
  4. Then `sendToSelf(URGENT, ok button)`.
  5. If there is no active SELF link and contacts exist → `sendToContacts(CONTACT_SELF_UNREACHABLE)`, and set `contactsAlertedAt`.
  6. With an active episode: update `lastMmol`, `nadirMmol` and `lastReadingAt` only.
  7. Heads-up: only when there is no active episode and the newest reading is ≥ low. Compute the forecast in a separate try/catch; any exception skips the heads-up. Send when the forecast < low within 30 min and no heads-up was sent in the last 60 min. Track that in memory per user (`ConcurrentHashMap<UUID, Instant>`); losing it on restart is acceptable for a non-repeating heads-up.
- IOB lookup failure → `iobUnits = null`; the message still goes.

- [ ] **Step 1: Write the failing unit tests:**
  - `freshLowOpensEpisodeAndSendsUrgent`: reading 3.5 at now−3 min, SELF link active → one `OPEN` episode, one send with kind `URGENT` and a button with callback `ack:<id>`.
  - `staleLowOpensNothing`: reading 3.2 at now−180 min, i.e. a backfill (Review Focus 2) → no episode, no send.
  - `secondEvaluateIsIdempotent`: evaluate twice → one episode, one send.
  - `concurrentOpenRace`: the repository's save throws `DataIntegrityViolationException` on the second call → exactly one send in total (Review Focus 1).
  - `suppressedWithin15MinOfRecovery`: the previous episode's `recoveredAt` is now−10 min and the reading is 3.7 → nothing.
  - `selfUnreachableEscalatesToContactsImmediately`.
  - `urgentDoesNotNeedCalculationsFlagOrForecast`: the IOB and forecast mocks throw → the urgent message is still sent, with no IOB.
  - `headsUpOncePerHour`: forecast 3.7 in 25 min with current 5.0 → one HEADS_UP; again 5 min later → none.
  - `headsUpNotWhileEpisodeOpen`.
  - `disabledUserGetsNothing`.
- [ ] **Step 2: Write the failing E2E test** `LowAlertOpenE2ETest`. Use real Postgres, a `@MockitoBean TelegramClient` returning `Sent`, and a seeded user with an active SELF link. Call `CgmReadingService.storeChartData` with one entry at sgv 64, now−2 min. Assert one `OPEN` episode and one `sendMessage` call whose text starts with `"⚠️ LOW"`. Also check that `app.features.glucose-calculations-enabled=false` (`@TestPropertySource`) changes nothing (spec acceptance 7).
- [ ] **Step 3: Run, and verify the tests fail.** `./gradlew test --tests '*LowAlertServiceTest' --tests '*LowAlertOpenE2ETest'` → FAIL
- [ ] **Step 4: Implement** the event, the publish point, the detector fallback, `LowAlertSender` and `LowAlertService`.
- [ ] **Step 5: Run, and verify the tests pass.** Same command → PASS. Then run `./gradlew test --tests '*GlucoseAnomalyDetector*' --tests '*CgmReading*'` and confirm the existing tests still pass.
- [ ] **Step 6: Commit**: `feat(alerts): open low-alert episodes from ingested CGM readings`

### Task 8: Escalation job and button handling

**Files:**
- Create: `alert/LowAlertEscalationJob.java`, `alert/LowAlertCallbackHandler.java` (implements Task 5's `CallbackRouter`; remove the no-op bean)
- Test: `src/test/java/che/glucosemonitorbe/alert/LowAlertEscalationJobTest.java`, `LowAlertCallbackHandlerTest.java`, `src/test/java/che/glucosemonitorbe/integration/LowAlertRestartE2ETest.java`

**Interfaces:**
- Consumes: Task 7 `LowAlertSender`, the callback format and the episode fields; Task 4 `hashChatId`, `activeContacts`; `Clock`.
- Produces:
  - `LowAlertEscalationJob.tick()`, annotated `@Scheduled(fixedDelayString = "${app.low-alert.tick-ms:60000}")`. It processes every episode in OPEN/ACKNOWLEDGED. Each episode runs in its own transaction via `findLockedById`, with a per-episode try/catch.
  - `LowAlertCallbackHandler.route(chatId, data, callbackQueryId)`.
- `tick` rules for one episode, at `now` and with `latest` the newest reading:
  1. **Resolved:** `latest ≥ recovery` and newer than `lastReadingAt` → `RESOLVED`, set `recoveredAt = resolvedAt = now`. Send `ALL_CLEAR` to self, and to contacts only if `contactsAlertedAt != null`.
  2. **Expired:** `now − lastReadingAt ≥ 2 h` → `EXPIRED`, send `EXPIRED` to self (and to contacts if alerted).
  3. **Sensor message:** `now − lastReadingAt ≥ 20 min` and `sensorMessageAt == null` → send `SENSOR` to self and set `sensorMessageAt`. A fresh reading clears `sensorMessageAt`.
  4. **Re-arm:** `ACKNOWLEDGED` and `now ≥ acknowledgedAt + 30 min` → `OPEN`, with `cycleStartedAt = now`, `contactsAlertedAt = null`, `repeatCount = 0`, `acknowledgedAt = null`. Send `URGENT`.
  5. **Repeat:** `OPEN` and `now − lastSentAt ≥ 10 min` → send `REPEAT` to self, with `staleMinutes` when the last reading is more than 15 min old. Then `repeatCount++` and `lastSentAt = now`.
  6. **Contacts:** `OPEN`, `contactsAlertedAt == null` and `now − cycleStartedAt ≥ 30 min` → `sendToContacts(CONTACT, onit button)` and set `contactsAlertedAt`. This applies even during a sensor gap, and whenever BG is still below recovery.
- `route` rules:
  - Parse `ack:` / `onit:`, load the episode and check the chat:
    - `ack` must come from that user's active SELF link;
    - `onit` must come from one of their active CONTACT links (matched by `hashChatId`).
  - Wrong chat, unknown episode, or an episode in RESOLVED/EXPIRED → `answerCallback("Already resolved")` or `answerCallback("Not for you")`, and nothing changes (Review Focus 3).
  - Valid → `ACKNOWLEDGED`, set `acknowledgedAt = now` and `acknowledgedByLinkId`. For `onit`, send `ACK_BY_CONTACT` to self.

- [ ] **Step 1: Write the failing job tests**, with a fixed `Clock` advanced in steps and a mocked `LowAlertSender` (they map to spec §8):
  - `repeatsAt10And20Minutes`.
  - `contactAt30WhileUnacknowledged`.
  - `contactAt30EvenAt4_2` (acceptance 3a).
  - `ackStopsRepeatsAndContactNeverMessaged`: ack at +12 → no sends through +41.
  - `reArmsAt42WhenStillLow`: URGENT at +42, contact at +72 (acceptance 3).
  - `recoveryResolvesAndAllClearsAlertedContacts`.
  - `allClearSkipsContactsNeverAlerted`.
  - `sensorMessageOnceAfter20MinGap`.
  - `repeatsContinueDuringGapWithStaleness`.
  - `expiresAfter2hWithoutData` (acceptance 3c).
  - `oneEpisodeFailingDoesNotStopOthers`.
- [ ] **Step 2: Write the failing callback tests:**
  - `ackFromSelfAcknowledges`.
  - `onItFromContactAcknowledgesAndNotifiesSelf`.
  - `onItFromRemovedContactChangesNothing`.
  - `ackFromStrangerChangesNothing`.
  - `ackOnResolvedEpisodeAnswersAlreadyResolved`.
- [ ] **Step 3: Write the failing restart E2E test** `LowAlertRestartE2ETest`: open an episode at T0. Build a **new** `LowAlertEscalationJob` instance from the same Spring context's repositories with the clock at T0+20 min, and call `tick()`. Assert a `REPEAT` send, with state read from the DB only (acceptance 6).
- [ ] **Step 4: Run, and verify the tests fail.** `./gradlew test --tests '*LowAlertEscalationJobTest' --tests '*LowAlertCallbackHandlerTest' --tests '*LowAlertRestartE2ETest'` → FAIL
- [ ] **Step 5: Implement** the job and the handler.
- [ ] **Step 6: Run, and verify the tests pass.** Same command → PASS. Then run the full `./gradlew test`.
- [ ] **Step 7: Commit**: `feat(alerts): repeats, contact escalation, re-arm and buttons for low alerts`

### Task 9: Heartbeat, bot health and running on the Mac

**Files:**
- Create: `alert/AlertHeartbeat.java`, `scripts/com.glucosemonitor.backend.plist.example`, `scripts/run-backend.sh`
- Modify: `notify/TelegramLinkService.java` (`list` fills `botHealthy`), `src/main/resources/application.yml` (`app.alerts.heartbeat-url: ${ALERT_HEARTBEAT_URL:}`), `README.md` (a "Low alerts on your Mac" section)
- Test: `src/test/java/che/glucosemonitorbe/alert/AlertHeartbeatTest.java`

**Interfaces:**
- Consumes: Task 5 `TelegramUpdatePoller.lastSuccessfulPollAt()`.
- Produces:
  - `AlertHeartbeat.ping()`, annotated `@Scheduled(fixedDelay = 300000)`. It does a GET to `heartbeat-url` (timeout 10 s) only when the URL is set **and** the last poll succeeded within 2 min. A broken bot therefore also stops the heartbeat and triggers the dead-man's switch.
  - `botHealthy` = poller active, and `lastSuccessfulPollAt` within 2 min.

- [ ] **Step 1: Write the failing tests:**
  - `pingsWhenPollerHealthy`.
  - `skipsPingWhenPollerStale`.
  - `noUrlNoPing`.
  - `linksDtoReportsBotHealthy`.
- [ ] **Step 2: Run, and verify the tests fail.** `./gradlew test --tests '*AlertHeartbeatTest' --tests '*TelegramLinkServiceTest'` → FAIL
- [ ] **Step 3: Implement the heartbeat and `botHealthy`.** Write `run-backend.sh`: it sources `~/.config/glucose-monitor/backend.env` (never in the repo), sets `JAVA_HOME` to temurin-21, then `exec`s `./gradlew bootRun`. Write the plist example with `RunAtLoad` and `KeepAlive` true, `StandardOutPath` / `StandardErrorPath` under `~/Library/Logs/glucose-monitor/`. In the README, give these steps:
  1. Create the bot with BotFather.
  2. Fill `backend.env` (`TELEGRAM_ENABLED`, `TELEGRAM_BOT_TOKEN`, `TELEGRAM_BOT_USERNAME`, `TELEGRAM_HASH_KEY`, `ALERT_HEARTBEAT_URL`).
  3. `cp` the plist to `~/Library/LaunchAgents/` and `launchctl load` it.
  4. Run `brew services start postgresql@15`.
  5. Turn on "Prevent automatic sleeping on power adapter when the display is off" (or `sudo pmset -c sleep 0`).
  6. Create a healthchecks.io check (period 5 min, grace 10 min) with its Telegram integration.
  7. Never set `TELEGRAM_BOT_TOKEN` on Render.
- [ ] **Step 4: Run, and verify the tests pass.** Same command → PASS
- [ ] **Step 5: Commit**: `feat(alerts): heartbeat, bot health and Mac run setup`

### Task 10 (iOS): API client and settings sync

**Files** (repo `glucose-monitor-iphone`):
- Create: `GlucoseMonitor/BackendAPI+Telegram.swift`
- Modify: `GlucoseMonitor/BackendAPI.swift` (the user-settings model and update call: add the Task 2 fields), `GlucoseMonitor/AppState.swift` (after login/app start, send `glucoseUnit` and `language`)
- Modify: `GlucoseMonitor.xcodeproj/project.pbxproj` (add the new files to the app target, and the test file to the test target)
- Test: `GlucoseMonitorTests/TelegramAlertsDecodingTests.swift`

**Interfaces:**
- Consumes: the Task 4 endpoints and the Task 2 DTO fields.
- Produces:
  - `BackendAPI.createTelegramLink(role: String) async throws -> TelegramInvite`, where `struct TelegramInvite: Decodable { let url: URL; let expiresAt: Date }`.
  - `BackendAPI.fetchTelegramLinks() async throws -> TelegramLinks`, where:
    - `struct TelegramLinks: Decodable, Equatable { let self_: Link?` (CodingKey `"self"`) `; let contacts: [Link]; let botHealthy: Bool }`;
    - `struct Link: Decodable, Equatable { let id: String; let status: String; let displayName: String?; let linkedAt: Date }`.
  - `BackendAPI.removeTelegramLink(id: String) async throws`.
  - `BackendAPI.sendTelegramTest() async throws`.
  - `AppState`:
    - `@Published var telegramLinks: BackendAPI.TelegramLinks?` and `func refreshTelegramLinks() async`;
    - `static func alertSettingsLanguage(_ preferred: [String]) -> String`, which returns `"ru"` when the first entry starts with `ru`, else `"en"`;
    - `static func backendGlucoseUnit(_ preferredGlucoseUnit: String) -> String`, which returns `"MGDL"` when `GlucoseUnit.isMgdl`, else `"MMOL"`.

- [ ] **Step 1: Write the failing tests:**
  - `decodesLinksWithSelfAndContacts` (JSON with `"self"`).
  - `decodesLinksWithoutSelf`.
  - `languageMapping` (`["ru-RU"] → "ru"`, `["de-DE"] → "en"`, `[] → "en"`).
  - `unitMapping` (`"mg/dL" → "MGDL"`, `"mmol/L" → "MMOL"`).
- [ ] **Step 2: Run, and verify the tests fail.** Run: `xcodebuild test -scheme GlucoseMonitor -project GlucoseMonitor.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 16,OS=18.3.1' -derivedDataPath /tmp/gm-derived CODE_SIGNING_ALLOWED=NO -only-testing:GlucoseMonitorTests/TelegramAlertsDecodingTests > /tmp/gm-test.log 2>&1; echo $?`. Expect a non-zero exit. Do not pipe the build through `tail` (memory *repo-topology*).
- [ ] **Step 3: Implement** the API calls with `performWithRefresh` / `authorizedRequest` as in `BackendAPI+InjectionSites.swift`, and send unit and language once per app start after authentication.
- [ ] **Step 4: Run, and verify the tests pass.** Same command → exit 0, and `grep "Test Suite 'TelegramAlertsDecodingTests' passed" /tmp/gm-test.log`.
- [ ] **Step 5: Commit**: `feat(alerts): Telegram links API and unit/language sync`

### Task 11 (iOS): Alerts settings, night checklist and banner

**Files:**
- Create: `GlucoseMonitor/AlertsSettingsSection.swift`, `GlucoseMonitor/LowAlarmBanner.swift`
- Modify: `GlucoseMonitor/SettingsView.swift` (embed the section), `GlucoseMonitor/ContentView.swift` (show the banner at the top of the dashboard), `project.pbxproj`
- Test: `GlucoseMonitorTests/LowAlarmBannerTests.swift`

**Interfaces:**
- Consumes: Task 10.
- Produces:
  - `enum LowAlarmBannerState: Equatable { case none, notSetUp, failing, serverOffline }`.
  - `static func LowAlarmBannerState.from(links: BackendAPI.TelegramLinks?, lowAlertEnabled: Bool, backendReachable: Bool) -> LowAlarmBannerState`, with precedence `serverOffline` > `notSetUp` (no self link, or alerts disabled) > `failing` (self status `FAILING`, or `!botHealthy`) > `none`.
  - Banner copy:
    - "Low alarm not set up";
    - "Telegram alerts are not reaching you";
    - "Alert server offline".
    - Tapping the banner opens Settings → Alerts.
- Alerts section contents:
  - **Connect Telegram:** opens the invite URL with `openURL`. It then shows "Connected as {displayName}" and **Send test**.
  - **Alert name:** a text field bound to `alertDisplayName`.
  - **Emergency contacts:** the list with swipe-to-remove. **Add contact** is disabled with a hint until the alert name is set; it shares the invite URL through `ShareLink`.
  - **Low threshold and recovery:** shown in the user's unit and stored in mmol/L.
  - **Rescue grams:** min and max steppers 5–40.
  - **Night checklist:** three toggles stored in `@AppStorage("nightChecklist.ringer" / ".focus" / ".sound")`, each with a one-line "how" caption.

- [ ] **Step 1: Write the failing tests** `bannerPrecedence`, a table covering all four states plus `none`.
- [ ] **Step 2: Run, and verify the tests fail** (the xcodebuild command from Task 10 with `-only-testing:GlucoseMonitorTests/LowAlarmBannerTests`).
- [ ] **Step 3: Implement** the views and the banner state.
- [ ] **Step 4: Run, and verify the tests pass.** Then run a simulator build: `xcodebuild … CODE_SIGNING_ALLOWED=NO build > /tmp/gm-build.log 2>&1; echo $?` → 0, and `grep "BUILD SUCCEEDED"`.
- [ ] **Step 5: Commit**: `feat(alerts): Alerts settings, night checklist and low-alarm banner`

### Task 12: On-device acceptance (manual)

- [ ] **Step 1:** Do the Mac setup from Task 9's README section with a real bot. Confirm `launchctl list | grep glucosemonitor` shows the service, and that the healthchecks.io check is green.
- [ ] **Step 2:** On the iPhone: Settings → Alerts → Connect Telegram → Start in Telegram. The app shows "Connected as …", and **Send test** arrives (spec acceptance 13).
- [ ] **Step 3:** Simulate a low. Insert a reading `sgv = 62` at now into `cgm_readings` for the user with `psql`, then run `POST /api/libre/sync-now`, or wait 1 min for the job. Confirm:
  - the urgent message arrives with OK;
  - tapping OK stops repeats;
  - inserting `sgv = 90` gives the all-clear.
- [ ] **Step 4:** Link a second Telegram account as a contact through the share link. Repeat step 3 without tapping OK, with the clock compressed by setting `app.low-alert.tick-ms` and testing at +30 min in real time. Confirm the contact message, and that "I'm on it" notifies the user.
- [ ] **Step 5:** Stop the backend (`launchctl unload …`) and confirm the healthchecks.io Telegram alert arrives within 15 min.
- [ ] **Step 6:** Write down the results in the PR description. Delete the test readings: `DELETE FROM cgm_readings WHERE device IS NULL AND sgv IN (62, 90) AND user_id = …`.

---

## Self-review notes

- **Spec coverage:**
  - Goals 1–7 → Tasks 7, 8, 7 (heads-up), 8, 8, 4/11, 11.
  - §7.5 data → Task 1. §7.6 API → Tasks 4, 5, 2. §7.7 → Tasks 10, 11. §7.8 → Task 9.
  - Anti-goals: 1 → Task 7 `urgentDoesNotNeedCalculationsFlagOrForecast` and the E2E flag-off test. 2 → idempotence, heads-up limit and ack tests. 3 → FAILING, banner and heartbeat. 4 → `tokenNeverLogged` and chat-id encryption. 5 → grams from settings in the Task 6 test.
- **Not covered by automated tests:** real Telegram delivery and night audibility (Task 12, manual).
