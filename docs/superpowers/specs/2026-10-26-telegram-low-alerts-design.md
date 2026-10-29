# Low alerts through a Telegram bot: Design

**Date:** 2026-10-26
**Status:** Draft, awaiting approval
**Repos:** `glucose-monitor-be` (most of the work) and `glucose-monitor-iphone` (Settings, checklist, banner)
**Context:** first piece of the in-app coach plan (`/Users/vlad/IdeaProjects/glucose-monitor-project/docs/glucose-coach-implementation-plan.md`, Phase 0.1–0.3). This design replaces that plan's APNs delivery with Telegram, because there is no paid Apple Developer account.

---

## 1. Intent

On the night of Oct 23–24 the user was under 70 mg/dL for 3.5 hours and nothing woke them. Libre's own alarms were off, and the app's alert engine only writes a log line (`GlucoseAlertService.deliverAlert` is a stub). This piece makes the backend reach the user's phone by itself when they go low: no laptop, no LLM and no open app in the path.

The channel is a **Telegram bot**. Without a paid Apple Developer account the app cannot receive remote push. Telegram delivers reliably, costs nothing, and the user already lives in it.

The bot built here is also the **delivery channel that later pieces reuse**: scheduled checks, meal-plan "time to eat" reminders, and proactive coach messages. Build it as a general "send a message to this user" service, not something tied to lows.

## 2. Goals

1. A reading under the user's low threshold sends an urgent Telegram message within one CGM sync cycle of the reading being stored. The default threshold is 70 mg/dL (3.9 mmol/L).
2. The urgent message repeats every 10 min until the user taps **OK**, or glucose recovers above the recovery threshold (default 81 mg/dL / 4.5 mmol/L).
3. A forecast low (predicted under the low threshold within 30 min) sends **one** heads-up message, with no repeats.
4. After 30 min without OK while still low, the bot also messages the user's linked **emergency contact**, who can stop the repeats with **"I'm on it"**.
5. When glucose recovers, the user and any contact who was alerted get an **all-clear**.
6. Linking is done from the iOS app with one tap, both for the user and for a contact, so beta users can set it up themselves.
7. The app shows that the alarm is not set up, and gives a checklist that makes Telegram audible at night.

## 3. Non-goals (this piece)

- APNs, Critical Alerts, Pushover, phone calls and SMS.
- Any two-way bot features beyond the OK and "I'm on it" buttons: no "check" command, no chat with the coach, no photos.
- High alerts, fast-fall alerts, stacking warnings, unlogged-meal prompts. The existing `GlucoseAlertEvaluator` types other than the forecast low keep logging only.
- Scheduled follow-up checks (coach plan 0.7). They will reuse this channel later.
- Multi-instance safety (ShedLock). The backend runs as a single instance; see the constraints.

## 4. Anti-goals: it failed if…

- **A low goes unsent because of an unrelated failure.** Examples: the LLM or Ollama is down, `glucose-calculations-enabled` is off, the forecast throws, a different user's sync breaks, or the server restarted mid-episode.
- **It nags.** Repeats continue after OK, the heads-up fires every 5 min, or one low produces several parallel episodes.
- **It is silent about being broken.** For example, Telegram is unlinked or the bot token is rejected while the user believes they are covered.
- **It leaks.** A bot token is committed or logged, or a contact can see anything beyond the alert messages.
- **The treatment hint is wrong for the user**, because it was hard-coded instead of taken from their settings.

## 5. Decisions made with the user

| Topic | Decision |
|---|---|
| Channel | Telegram bot |
| Linking the user | iOS Settings → **Connect Telegram** opens `https://t.me/<bot>?start=<one-time code>`; tapping Start in Telegram links that chat to the account |
| Triggers | Urgent: a reading under the low threshold, repeating every 10 min until OK or recovery. Heads-up: one message when the forecast is under the low threshold within 30 min |
| Escalation | After 30 min unacknowledged and still low, message the emergency contact |
| Contact linking | iOS Settings → **Add emergency contact** shares a t.me invite link with a one-time code. Once the contact taps Start, their name shows in the app, and the user can remove them |
| Contact actions | An **"I'm on it"** button stops further repeats. Both the user and the contact get the all-clear |
| Language and units | Follow each user's app settings |
| Urgent message content | Reading, trend arrow, insulin on board, and a treatment hint: under the low threshold → "take 15–20 g fast sugar, recheck in 15 min", with the grams from the user's settings. Plus an OK button |
| Nights | An in-app setup checklist (ringer on at night, Telegram allowed in the Sleep Focus, a loud custom sound for the bot chat), and an "alarms off" banner until Telegram is linked |
| Scope | Alerts and the buttons only |

## 6. Constraints from the existing code

- **Glucose is mmol/L everywhere in the backend.** `cgm_readings.sgv` is mg/dL; `GlucoseConversion.mgdlToMmol` converts. Thresholds are stored in mmol/L and shown in the user's unit.
- **Thresholds already exist:** `service/observer/HypoThresholds` (`HYPO_MMOL = 3.9`, `RECOVERY_MMOL = 4.5`). Those constants become the defaults of the new per-user settings; the hypo prompt (`HypoEventService`) keeps using the constants.
- **The current alert path is gated.** `GlucoseAlertService.evaluateAll` returns early unless `app.features.glucose-calculations-enabled`. It runs a full `GlucoseCalculationsService` calculation and is `@Async` with in-memory cooldowns. **The urgent low must not go through that path** (anti-goal 1). It depends only on the stored reading.
- **Sync cadence:** `LibreLinkUpGlucoseSyncScheduler` and `NightscoutGlucoseSyncScheduler` run every 5 min. `GlucoseAnomalyDetector.scan()` also runs every 5 min (`app.observer.interval-ms`) over all users, and calls `HypoEventService.onGlucoseReading`. No ingest event is published today.
- **The backend is single-instance** (memory *load-ceiling*). The design must not get worse with two instances, but it does not need leader election now. Claim work with row locks (`SELECT … FOR UPDATE SKIP LOCKED`) where it is cheap.
- **Schema rules** (`glucose-monitor-be/CLAUDE.md`): columns on an existing table go into the owning migration. For `user_settings` that is `V1__baseline_schema.sql`; editing it requires `flyway repair` on the dev DB. New tables go into new files; the next free number is **V13**.
- **Secrets:** the bot token comes from an environment variable (e.g. `TELEGRAM_BOT_TOKEN`). It is never committed, and it is never logged, including inside request URLs: Telegram puts the token in the path, so log a redacted URL. Follow `SecretsStartupValidator`, and fail fast in `prod` if alerts are enabled without a token.
- **Hosting (user's decision): the backend runs on the user's always-on Mac** for this piece. It runs as a login service (launchd), kept awake at night. Render's free plan (`render.yaml`, `plan: free`) sleeps when idle and runs no scheduled jobs, so it is not the alert host.
  - Only one process may poll a bot token: Telegram returns HTTP 409 to a second poller. So alerts are enabled by config only where `TELEGRAM_BOT_TOKEN` is set, and the Render deployment must not set it.
  - The Mac is a single point of failure (sleep, closed lid, Wi-Fi, a crash). Section 7.8 adds the setup and a dead-man's switch for that.
- **The backend runs on localhost** with no public URL. The bot must work without a webhook.
- **iOS knows the glucose unit** (`AppState.preferredGlucoseUnit`, `"mmol/L"` or `"mg/dL"`). The backend does not store it. There is no in-app language setting; the app follows the device language.

## 7. How it works

### 7.1 Components (backend)

| Unit | Responsibility |
|---|---|
| `notify/telegram/TelegramClient` | Thin HTTP client for the Bot API: `sendMessage` with an inline keyboard, `editMessageReplyMarkup`, `answerCallbackQuery`, `getUpdates`. Connect and read timeouts, plus a circuit breaker reused from `circuitbreaker/CircuitBreakerManager` |
| `notify/telegram/TelegramUpdatePoller` | Long polling of `getUpdates` on its own thread, persisting the update offset. It routes `/start <code>` to linking and button callbacks to the alert service |
| `notify/NotificationChannel` (interface) + `TelegramNotificationChannel` | "Send this message to this user" or "to this contact", with buttons. Later pieces (checks, meal plans) depend on this interface, not on Telegram |
| `notify/TelegramLinkService` | Creates one-time link codes, consumes them on `/start`, and stores links for users and contacts |
| `alert/LowAlertService` | Owns the low-alert episode lifecycle (7.3) |
| `alert/LowAlertEscalationJob` | Runs every minute. It sends due repeats, escalates to contacts at +30 min, and closes episodes |
| `alert/AlertMessageFormatter` | Builds the text in the user's language and unit from a small template set (en, ru; fallback en) |

### 7.2 Trigger

- After `LibreLinkUpSyncService` and the Nightscout sync persist **new** readings for a user, they publish a Spring event `CgmReadingsIngested(userId, newestEpochMs)`. `LowAlertService` listens and evaluates that user against the newest stored reading.
- `GlucoseAnomalyDetector.scan()` also calls `LowAlertService.evaluate(userId)` on every cycle as a fallback, for example when a sync wrote nothing new.
  - The call goes **before** the detector's early return for too few recent readings, so a single fresh low reading is still evaluated.
  - The call runs in its own try/catch, like the hypo lifecycle call. Evaluation must be idempotent, so a reading evaluated twice opens nothing twice.
- **Urgent:** the newest reading is under `low_alert_mmol` and is at most 15 min old → open an episode, or keep the open one.
- **Heads-up:**
  - The urgent condition is false, and the forecast drops under `low_alert_mmol` within 30 min → send one heads-up.
  - At most one heads-up per 60 min, and none while an episode is open.
  - Use the existing forecast path (`GlucoseAlertEvaluator` / `GlucoseCalculationsService`), wrapped so that its failure can only lose the heads-up, never the urgent alert.
  - Builder's call: if the forecast isn't available, the heads-up is skipped silently and logged.

### 7.3 Episode lifecycle

There is at most one open episode per user. It is stored in a new table, so a restart changes nothing.

```
OPEN ──(user taps OK)──────────────► ACKNOWLEDGED ──(BG ≥ recovery)──► RESOLVED (all-clear)
  ▲ │                                     │  ▲
  │ ├──(contact taps "I'm on it")──────────┘  │   (repeats stop; contact named in the user's chat)
  │ │                                         │
  └─┼──(30 min after ack, BG still < recovery)┘   full cycle restarts
    ├──(BG ≥ recovery)──────────────────────────────────────────────► RESOLVED (all-clear)
    ├──(no reading for 20 min)──► sensor message once, episode stays OPEN
    └──(no reading for 2 h)─────────────────────────────────────────► EXPIRED (message)
```

| Time since open, still low, not acknowledged | Action |
|---|---|
| 0 | Urgent message to the user, with OK |
| every +10 min | Repeat to the user. Send a new message rather than editing the old one, so it notifies again |
| +30 min | Also message every linked contact, with "I'm on it". Repeats to the user continue |
| any time BG ≥ recovery | All-clear to the user, and to each contact who was alerted |

- **"Low" and "recovered" are two thresholds.** An episode opens under `low_alert_mmol` (3.9). It ends only at or above `low_alert_recovery_mmol` (4.5). Between the two it stays open: repeats continue, and the +30-min contact escalation still fires if nothing was acknowledged. When in doubt, the safer outcome wins.
- **OK or "I'm on it" pauses the episode for 30 min (user's decision).** If BG is still below recovery when the 30 min end, the episode returns to OPEN and the **full cycle restarts**: an urgent message, repeats every 10 min, and the contact again at +30 min from the restart. Store `acknowledged_at`; the job re-arms the episode when `acknowledged_at + 30 min` has passed and the episode is still not resolved.
- **Sensor gap during an episode** (*my call*):
  - No reading for 20 min sends the sensor message once. Repeats keep going on the last known value, labelled "last reading 25 min ago".
  - Contact escalation still fires at +30 min when nothing was acknowledged, because silence plus no data is exactly when someone else should check.
  - An episode with no reading for 2 h closes as `EXPIRED`, with a "no data for 2 h; alerts resume with the next reading" message to the user and to alerted contacts.
- **The user's own link isn't working** (*my call*): no ACTIVE `SELF` link (never linked, `REMOVED` or `FAILING`) while a contact is linked. Escalation to the contact then happens **at once** instead of at +30 min, and the contact message says "<name>'s own alerts are not reaching them".
- A new episode can open only after the previous one is RESOLVED, and after at least 15 min above recovery (`HypoThresholds.SUPPRESSION_MINUTES`).
- Button presses on a closed episode answer "already resolved" and change nothing.
- **Delivery failure** (Telegram down, or the chat blocked the bot): the next repeat retries. After 3 consecutive failures, the link is marked `FAILING`, and the app banner shows "Telegram alerts are not reaching you".

### 7.4 Messages

Template sketches; the builder may adjust the wording. Values are shown in the user's unit.

- **Urgent:** `⚠️ LOW 64 mg/dL ↓ · 1.2 U active · Take 15–20 g fast sugar, recheck in 15 min.` Button: **OK**
- **Repeat:** `⚠️ Still low: 61 mg/dL → (20 min). Take 15–20 g fast sugar.` Button: **OK**
- **Heads-up:** `↘️ Heading low: 92 mg/dL ↓, forecast ~68 in 25 min.` No button.
- **Contact:** `🚨 <alert name> has been low for 30 min: 58 mg/dL ↓. They haven't answered.` Button: **I'm on it**
  - `<alert name>` is a new `user_settings.alert_display_name`, which the user sets in the Alerts section, prefilled from `users.full_name`.
  - The contact link cannot be created until it is set (*my call*), so a contact never sees an email address.
- **All-clear:** `✅ Back to 88 mg/dL. Low lasted 42 min.`
- **Sensor:** `❔ No sensor reading for 20 min during a low. Open Libre or send a fingerstick.`
- Trend arrows come from the newest readings: the LibreLinkUp trend when present, otherwise the slope over the last 15 min.
- IOB comes from `GlucoseCalculationsService.activeCobIobInputs` (memory *cob-iob-single-source*). If that fails, the IOB part is left out and the message still goes.
- The grams in the hint are `rescue_carbs_min_g`–`rescue_carbs_max_g`, defaults 15–20 (F4). No other advice appears in these messages.

### 7.5 Data

New table **V13 `telegram_links`**: `id`, `owner_user_id`, `role` (`SELF` | `CONTACT`), `chat_id`, `display_name` (from Telegram), `status` (`ACTIVE` | `FAILING` | `REMOVED`), `linked_at`, `last_ok_at`.
- At most one `SELF` link per user. Contacts: up to 3 (*my call*).
- `chat_id` is encrypted at rest with `security/EncryptedStringConverter`.
  - That converter uses a random IV, so add a `chat_id_hash` column (HMAC-SHA256 of the chat id with a server key) for looking up an incoming update's chat. Index it.

New table **V14 `telegram_link_codes`**: `code` (random, ≥ 128 bits, URL-safe), `owner_user_id`, `role`, `expires_at` (15 min), `used_at`.

New table **V15 `low_alert_episodes`**: `id`, `user_id`, `state`, `opened_at`, `nadir_mmol`, `last_reading_at`, `last_sent_at`, `repeat_count`, `contacts_alerted_at`, `acknowledged_at`, `acknowledged_by` (`SELF` or a contact link id), `resolved_at`, `failure_count`.
- A partial unique index allows only one open (non-RESOLVED) episode per user.

Table **V16 `telegram_poll_state`**: holds the last `update_id` offset.

**`user_settings` columns** (edit V1):
- `low_alert_enabled` (default true);
- `low_alert_mmol` (default 3.9) and `low_alert_recovery_mmol` (default 4.5);
- `rescue_carbs_min_g` / `rescue_carbs_max_g` (defaults 15 / 20);
- `glucose_unit` (`MMOL` | `MGDL`, default `MMOL`) and `language` (default `en`);
- `alert_display_name` (nullable).

The `low_alert_episodes.state` values are `OPEN`, `ACKNOWLEDGED`, `RESOLVED` and `EXPIRED`.

### 7.6 API (backend, for iOS)

- `POST /api/telegram/link` with body `{role: SELF|CONTACT}` → `{url, expiresAt}`.
- `GET /api/telegram/links` → `{self: {status, displayName, linkedAt}?, contacts: [...]}`.
- `DELETE /api/telegram/links/{id}`.
- `POST /api/telegram/test`: sends "✅ Test alert from Glucose Monitor" to the user's own chat.
- `PUT /api/user-settings` accepts the new fields. iOS sends `glucose_unit` from `preferredGlucoseUnit` and `language` from the device's first preferred language (`ru` or `en`, else `en`) whenever settings are saved and once at app start.

### 7.7 iOS

- **SettingsView** gets an "Alerts" section:
  - **Connect Telegram**, which calls `/api/telegram/link` and opens the URL. It then shows "Connected as @name" and a **Send test** button.
  - **Emergency contacts**, which share the invite URL through the share sheet, list names and allow removing them.
  - The low threshold and rescue grams, in the user's unit.
  - The **night checklist**: three rows the user ticks themselves (ringer on, Telegram allowed in the Sleep Focus, custom sound for the bot chat), each with a one-line "how" note. The ticks are stored locally.
- **Dashboard banner:** "Low alarm not set up" while there is no ACTIVE `SELF` link or `low_alert_enabled == false`. "Telegram alerts are not reaching you" while the link is `FAILING`. Tapping the banner opens the Alerts section.
- New files go into `GlucoseMonitor.xcodeproj/project.pbxproj` (memory *repo-topology*).

### 7.8 Running on the Mac

- **Setup doc:** add a short section to the backend README covering:
  - a launchd `LaunchAgent` plist that starts the backend at login and restarts it if it exits (`KeepAlive`). Put it in `scripts/`, with the token read from a local env file that is never committed;
  - `pmset` / "Prevent automatic sleeping when the display is off" on power. `caffeinate -s` is the fallback;
  - the Homebrew Postgres service started at login (`brew services`).
- **Dead-man's switch** (*my call*):
  - The backend pings an external heartbeat monitor every 5 min (e.g. healthchecks.io's free tier). The URL comes from `ALERT_HEARTBEAT_URL`; unset means off.
  - The monitor sends its own Telegram message when pings stop for 15 min, which catches a sleeping Mac, a crash or a dropped network.
  - This is the only third-party service added. It receives no health data, only a ping.
- **Bot health in the app:**
  - `GET /api/telegram/links` also returns `botHealthy` (the last successful `getUpdates` was under 2 min ago).
  - The iOS banner shows "Alert server offline" when the backend itself can't be reached. This only works while the app is open; nights rely on the dead-man's switch.

## 8. Acceptance

1. A stored reading of 64 mg/dL sends the urgent message within one sync cycle, with the IOB and the hint in the user's unit and language.
2. With no OK, repeats arrive at +10 and +20 min. At +30 the contact gets their message, and repeats to the user continue.
3. OK at +12 min stops the repeats, and the contact is not messaged at +30. If BG is still under 4.5 at +42 min, the full cycle restarts: an urgent message, repeats, and the contact at +72 if there is no new OK.
3a. A reading of 4.2 mmol/L at +30 with no OK still escalates to the contact.
3b. With the user's own link `FAILING` and a contact linked, the contact is messaged at +0.
3c. No reading for 2 h during an episode closes it as `EXPIRED`, with one message.
4. "I'm on it" from the contact stops the repeats, and the user's chat says who responded.
5. A reading of 88 mg/dL resolves the episode. The user and the alerted contact get the all-clear, and a dip to 68 within 15 min opens no new episode.
6. A server restart at +15 min keeps the episode, and the +20 repeat still goes.
7. With `glucose-calculations-enabled=false`, or with the forecast throwing, the urgent alert still fires (anti-goal 1). The heads-up is skipped.
8. A forecast low within 30 min sends one heads-up, and the next 5-min cycle does not send another.
9. A link code works once and expires after 15 min. A used or expired code answers "link expired, request a new one in the app".
10. Three failed sends mark the link `FAILING`, and the iOS banner shows it.
11. The bot token appears in no log line (test with a captured logger).
12. No reading for 20 min during an open episode sends the sensor message once.
13. On a real phone: Connect Telegram → Send test arrives. A simulated low through a dev-only endpoint or a seeded reading arrives in Telegram.

**Testing approach:** TDD with a mocked `TelegramClient` and a fake clock for the escalation timings. Use one integration test against Postgres (Testcontainers, as in the existing ITs) for the episode table and restart behaviour. Real Telegram is used only in the manual check (13).

## 9. Left to the builder

- The HTTP library for the Bot API: the existing `RestTemplate` config or `java.net.http`. Long-polling timeout and thread setup.
- Exact message wording in en and ru, within 7.4.
- Whether the heads-up reuses `GlucoseAlertEvaluator`'s `PREDICTED_HYPO` maths with a 30-min horizon or a small new function. It must not use the `evaluateAll` path's feature gate.
- The bot's username and setup (BotFather) is a manual step for the user. Document it in the backend README section for local setup.
- The iOS layout of the Alerts section.

## 10. Calls I made (please check)

Decided with the user after review: the backend runs on the always-on Mac (7.8), and OK pauses the episode for 30 min, after which the full cycle restarts if BG is still low.

- Long polling instead of a webhook, so it works on localhost.
- The urgent low depends only on stored readings, never on the forecast or the calculations flag.
- An episode stays open until BG is at or above 4.5; between 3.9 and 4.5 the repeats and escalation continue.
- During a sensor gap the repeats continue on the last value, escalation still fires, and the episode expires after 2 h without data.
- A broken or missing own link escalates to the contact at once.
- The contact sees `alert_display_name`, which is required before a contact can be linked.
- A heartbeat to an external dead-man's switch (healthchecks.io or similar) covers the Mac being down.
- At most 3 contacts. A contact sees only alert messages, never history.
- `chat_id` is encrypted at rest. Link codes expire after 15 min.
- A reading older than 15 min does not open an episode, and the sensor message fires after 20 min without data during an episode.
- The heads-up is limited to one per 60 min and is skipped while an episode is open.
