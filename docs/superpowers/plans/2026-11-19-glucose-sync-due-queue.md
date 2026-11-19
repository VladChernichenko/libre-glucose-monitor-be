# Glucose Sync Due-Queue Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the "submit every user, wait 4 min, sleep 5 min" sync ticks with a per-source due-queue that starts each user on their own fixed 5-minute slot, at most 8 calls in flight per source.

**Architecture:** Two pure helpers (`SyncSlot` for slot math, `SyncPump` for the start decision) feed a `SyncLane` base class that holds an in-memory heap keyed by eligibility time, counts in-flight calls, and writes `next_poll_at` from each outcome. `LibreSyncLane` and `NightscoutSyncLane` specialise it. The existing `@Scheduled` schedulers keep their 8-thread pools and become 1-second pump ticks. `next_poll_at` in the DB stays the source of truth; no new table.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Mockito, AssertJ, Maven.

**Spec:** `docs/superpowers/specs/2026-11-18-glucose-sync-due-queue-design.md`

## Global Constraints

- New package for queue code: `che.glucosemonitorbe.sync`.
- Window = 300 s. Slot offset = `Math.floorMod(id.getMostSignificantBits() ^ id.getLeastSignificantBits(), 300)` seconds; slot math on epoch seconds.
- `next_poll_at` is written as `LocalDateTime.ofInstant(instant, ZoneId.systemDefault())`; read back with `.atZone(ZoneId.systemDefault()).toInstant()`.
- Max in flight: 8 per lane. Pool size stays 8. No ninth call replaces a slow one; no cancellation.
- Libre 429 pause: 60 s. Lock-miss hide: 15 s (in memory only). Circuit-open fallback: now + 120 s. Lag log: oldest due > 60 s late while 8 busy, at most once per 60 s.
- No `ALTER TABLE`, no new migration. Do not touch `fast-interval-minutes` / `slow-interval-minutes` properties (leave them unread).
- Only one process runs lanes: lanes pump only from schedulers gated by `app.glucose-sync.enabled`.
- `consecutive_no_change_count` keeps incrementing in `markNoChange`; it never affects the interval.
- `src/main/resources/application.yml` has an unrelated uncommitted change — never `git add` it wholesale; add only hunks this plan makes.
- Run focused tests per task; full `./mvnw test` once in the last task.

## Review Focus

1. **Off-grid catch-up then back on grid** — a user served late (due = now) must have the *following* attempt on their own grid slot, not `now + 300`. Test in Task 1 (`followingSlot_afterOffGridCatchUp_returnsToGrid`).
2. **Completion after deactivation** — an in-flight sync that finishes after the user left the lane must not put them back in the heap. Test in Task 5 (`completionAfterRemoval_doesNotRequeue`).
3. **Same user started twice** — a user already in flight must not be started again by the next 1-second pump even if their key is still due. Test in Task 5 (`inFlightUser_isNotStartedTwice`).
4. **Worker throws instead of returning** — an unexpected exception from `syncUser` must still decrement in-flight and requeue on the following slot, or the lane silently loses a worker forever. Test in Task 5 (`workerThrows_releasesSlotAndRequeues`).
5. **429 arriving during an existing pause** — extends to its own receipt + 60 s, and never shortens an existing pause. Test in Task 5 (`secondRateLimit_extendsPause`).

---

### Task 1: `SyncSlot` — pure slot math

**Files:**
- Create: `src/main/java/che/glucosemonitorbe/sync/SyncSlot.java`
- Test: `src/test/java/che/glucosemonitorbe/sync/SyncSlotTest.java`

**Interfaces:**
- Produces (all `static`, final utility class):
  - `int offsetSeconds(UUID userId)` — `0..299`.
  - `Instant slotAtOrAfter(UUID userId, Instant t)` — smallest instant `s ≥ t` (truncated to whole seconds) with `floorMod(s.epochSecond - offset, 300) == 0`.
  - `Instant followingSlot(UUID userId, Instant dueAt, Instant now)` — `slotAtOrAfter(userId, dueAt + 1s)`; if that is before `now`, return `now`.
  - `Instant nextFutureSlot(UUID userId, Instant now)` — `slotAtOrAfter(userId, now + 1s)`.
  - `Instant slotAtLeast300After(UUID userId, Instant t)` — `slotAtOrAfter(userId, t + 300s)`.
  - `Instant startupSlot(UUID userId, Instant storedOrNull, Instant now)` — stored value if non-null and after `now`, else `nextFutureSlot`.
  - `static final Duration WINDOW = Duration.ofSeconds(300)`.

- [ ] **Step 1: Write the failing tests** — `SyncSlotTest`:
  - `offset_isStableAndInRange`: same UUID twice → equal; 1000 random UUIDs all in `[0, 300)`.
  - `followingSlot_is300AfterServedSlot`: `due = slotAtOrAfter(id, T0)`, `now = due + 2s` → `followingSlot(id, due, now) == due + 300s`.
  - `followingSlot_whenPast_isNow`: `now = due + 400s` → result `== now`.
  - `followingSlot_afterOffGridCatchUp_returnsToGrid`: with `due2 = now` from the previous case, `followingSlot(id, due2, due2 + 1s)` lies on the grid (`floorMod(epochSec - offset, 300) == 0`) and is `> due2`.
  - `startupSlot_nullOrOverdue_isNextFutureSlot`: `null` and `now - 10s` both → `> now`, on grid, `≤ now + 300s`; a stored future value is returned unchanged.
  - `slotAtLeast300After_isOnGridAndAtLeast300Out`.
- [ ] **Step 2:** `./mvnw -q test -Dtest=SyncSlotTest` → FAIL (class missing).
- [ ] **Step 3:** Implement `SyncSlot` per Interfaces.
- [ ] **Step 4:** `./mvnw -q test -Dtest=SyncSlotTest` → PASS.
- [ ] **Step 5:** `git add src/main/java/che/glucosemonitorbe/sync/SyncSlot.java src/test/java/che/glucosemonitorbe/sync/SyncSlotTest.java && git commit -m "feat(sync): per-user 5-minute slot math"`

---

### Task 2: `SyncPump.decide` — pure start decision

**Files:**
- Create: `src/main/java/che/glucosemonitorbe/sync/SyncPump.java`
- Test: `src/test/java/che/glucosemonitorbe/sync/SyncPumpTest.java`

**Interfaces:**
- Produces:
  - `record QueueEntry(UUID userId, Instant eligibleAt)` — heap order: `eligibleAt`, then `userId`. Expose `static final Comparator<QueueEntry> ORDER`.
  - `record Decision(List<UUID> start, Instant pausedUntil)` — `pausedUntil` is the input pause, or `null` once `now ≥ pause`.
  - `static Decision decide(List<QueueEntry> ordered, Instant now, int inFlight, int maxInFlight, Instant pausedUntil)` — if paused (`pausedUntil != null && now < pausedUntil`) start nobody; otherwise take entries in order while `eligibleAt ≤ now` and `inFlight + started < maxInFlight`; stop at the first future entry.

- [ ] **Step 1: Write the failing tests** — `SyncPumpTest`:
  - `futureEntry_notStarted`: one entry at `now + 1s` → `start` empty.
  - `startsOldestFirst_upToCap`: 10 due entries, `inFlight = 5`, cap 8 → exactly the 3 oldest.
  - `eighthInFlight_blocksNinth`: `inFlight = 8` → empty.
  - `paused_startsNobody_thenClears`: `pausedUntil = now + 5s` → empty, pause returned; `pausedUntil = now` → starts, returned pause `null`.
- [ ] **Step 2:** `./mvnw -q test -Dtest=SyncPumpTest` → FAIL.
- [ ] **Step 3:** Implement `SyncPump`.
- [ ] **Step 4:** `./mvnw -q test -Dtest=SyncPumpTest` → PASS.
- [ ] **Step 5:** Commit `feat(sync): pure pump decision`.

---

### Task 3: Sync results, `RATE_LIMITED`, and caller-supplied `next_poll_at`

`LibreLinkUpSyncService.syncUser` stops writing `next_poll_at` and stops checking backoff; it reports an outcome. The state service takes the time the lane decides.

**Files:**
- Create: `src/main/java/che/glucosemonitorbe/sync/SyncOutcome.java`, `src/main/java/che/glucosemonitorbe/sync/SyncResult.java`, `src/main/java/che/glucosemonitorbe/service/libre/LibreRateLimitedException.java`
- Modify: `src/main/java/che/glucosemonitorbe/service/LibreLinkUpSyncService.java` (remove nested `Outcome`, the `fast/slow-interval` fields, the backoff branch at 185-191, all `markNoChange/markNewData/markError` calls)
- Modify: `src/main/java/che/glucosemonitorbe/service/UserGlucoseSyncStateService.java` (`markError` signature; delete `markSkippedBackoff` and `STATUS_SKIPPED_BACKOFF` once unused)
- Modify: `src/main/java/che/glucosemonitorbe/service/libre/LibreLinkUpClient.java:107-121` (`authError` returns `LibreRateLimitedException` for 429)
- Modify: `src/main/java/che/glucosemonitorbe/controller/LibreLinkUpController.java:147-165`
- Test: `src/test/java/che/glucosemonitorbe/service/LibreLinkUpSyncServiceCircuitTest.java`, `LibreLinkUpSyncServiceCgmTagTest.java`, `UserGlucoseSyncStateServiceTest.java`, `controller/LibreLinkUpControllerTest.java`; create `service/LibreLinkUpSyncServiceOutcomeTest.java`

**Interfaces:**
- Produces:
  - `enum SyncOutcome { NEW_DATA, NO_CHANGE, SKIPPED_NO_CREDS, IN_PROGRESS, ERROR, UNAVAILABLE, RATE_LIMITED }`
  - `record SyncResult(SyncOutcome outcome, Long newestTimestamp, Instant retryAt)` with factories `of(SyncOutcome)`, `newData(long newestTs)`, `unavailable(Instant retryAtOrNull)`.
  - `LibreLinkUpSyncService.syncUser(UUID userId, boolean force) : SyncResult` — `UNAVAILABLE` carries the breaker's `getRetryAt()` converted to `Instant` (null if absent). `RATE_LIMITED` when the cause chain contains `LibreRateLimitedException` or an `HttpClientErrorException` with status 429. Region fallback for 429 stays off (already true in `LibreLinkUpRegionResolver`). Keep the 401 logout. Coalesced forced sync still returns `NO_CHANGE`.
  - `class LibreRateLimitedException extends RuntimeException` (same message `authError` builds today).
  - `UserGlucoseSyncStateService.markError(UUID userId, LocalDateTime checkedAt, LocalDateTime nextPollAt)`; `markNoChange` / `markNewData` unchanged in shape.
  - Controller mapping: `NEW_DATA, NO_CHANGE → 200`, `IN_PROGRESS → 202`, `SKIPPED_NO_CREDS → 409`, `ERROR → 502`, `UNAVAILABLE → 503`, `RATE_LIMITED → 429`. Body stays `{"outcome": name}`. (Forced-sync lane hook is added in Task 7.)

- [ ] **Step 1: Write the failing tests** — `LibreLinkUpSyncServiceOutcomeTest` (mock `LibreLinkUpService`, `UserGlucoseSyncStateService`, `CgmReadingService`, config repo, real `CircuitBreakerManager`):
  - `newData_returnsNewestTimestamp_andWritesNoNextPoll`: `verify(syncStateService, never()).markNewData(any(), anyLong(), any(), any())` and no `markNoChange`/`markError`.
  - `http429_returnsRateLimited`: `getGlucoseData` throws `HttpClientErrorException.create(TOO_MANY_REQUESTS, …)` → `RATE_LIMITED`.
  - `authRateLimited_returnsRateLimited`: `ensureSession` throws `LibreRateLimitedException` → `RATE_LIMITED`.
  - `futureNextPollAt_doesNotSkip`: state `nextPollAt = now + 1h`, `force = false` → the fetch is still called.
  - `openCircuit_returnsBreakerRetryAt`: 3 failures then call → `UNAVAILABLE` with non-null `retryAt` ≈ last failure + 120 s.
  - Update `UserGlucoseSyncStateServiceTest` for `markError(userId, checkedAt, nextPollAt)`: stored `nextPollAt` equals the argument.
  - Update `LibreLinkUpControllerTest`: `RATE_LIMITED → 429`.
- [ ] **Step 2:** `./mvnw -q test -Dtest='LibreLinkUpSyncService*Test,UserGlucoseSyncStateServiceTest,LibreLinkUpControllerTest'` → FAIL (compile).
- [ ] **Step 3:** Implement per Interfaces; fix remaining compile references to `LibreLinkUpSyncService.Outcome` (`grep -rn "SyncService.Outcome" src`). `LibreLinkUpGlucoseSyncScheduler` may be left compiling with a temporary `syncUser(...).outcome()` — it is rewritten in Task 7.
- [ ] **Step 4:** Same command → PASS.
- [ ] **Step 5:** Commit `refactor(libre-sync): report outcomes, leave next_poll_at to caller, RATE_LIMITED`.

---

### Task 4: Extract `NightscoutSyncService.syncUser`

**Files:**
- Create: `src/main/java/che/glucosemonitorbe/service/NightscoutSyncService.java`
- Test: `src/test/java/che/glucosemonitorbe/service/NightscoutSyncServiceTest.java`

**Interfaces:**
- Consumes: `NightScoutIntegration.getGlucoseEntries(UUID, int)`, `CgmReadingService.storeChartData(UUID, List<NightscoutEntryDto>, CgmReading.DataSource.NIGHTSCOUT)`, `UserGlucoseSyncStateService.getOrCreate(UUID)`.
- Produces: `@Service NightscoutSyncService.syncUser(UUID userId) : SyncResult` — body moved from `NightscoutGlucoseSyncScheduler` lines 110-145 minus the backoff check and all `mark*` calls. `NEW_DATA` with newest ts when newest > `lastSeenEntryTimestamp`, else `NO_CHANGE`; any exception (including HTTP 429) → `ERROR`. Reads `app.glucose-sync.entry-count` (default 100).

- [ ] **Step 1: Write the failing tests:** `newerEntry_returnsNewData`, `sameNewest_returnsNoChange`, `emptyEntries_returnsNoChange`, `integrationThrows_returnsError`, and in each `verifyNoMoreInteractions` on the `mark*` methods.
- [ ] **Step 2:** `./mvnw -q test -Dtest=NightscoutSyncServiceTest` → FAIL.
- [ ] **Step 3:** Implement. Leave the old scheduler body in place until Task 7.
- [ ] **Step 4:** Same command → PASS.
- [ ] **Step 5:** Commit `refactor(ns-sync): per-user Nightscout sync service`.

---

### Task 5: `SyncLane` base + `LibreSyncLane`

**Files:**
- Create: `src/main/java/che/glucosemonitorbe/sync/SyncLane.java`, `src/main/java/che/glucosemonitorbe/sync/LibreSyncLane.java`
- Modify: `src/main/java/che/glucosemonitorbe/repository/UserDataSourceConfigRepository.java` (add credentialed-Libre query)
- Test: `src/test/java/che/glucosemonitorbe/sync/LibreSyncLaneTest.java`, `src/test/java/che/glucosemonitorbe/sync/ManualExecutor.java` (test helper: queues `Runnable`s, `runAll()` executes them)

**Interfaces:**
- Consumes: `SyncSlot`, `SyncPump`, `SyncResult`, `UserGlucoseSyncStateService.{getOrCreate, markNewData, markNoChange, markError}`.
- Produces:
  - `abstract class SyncLane(String name, Clock clock, UserGlucoseSyncStateService state)`:
    - `static final int MAX_IN_FLIGHT = 8`, `Duration LOCK_MISS_HIDE = 15s`.
    - `void reconcile()` — calls `protected abstract Set<UUID> members()`; removes users no longer present; adds new users with key `SyncSlot.startupSlot(id, stored next_poll_at, now)` and, if that differs from the stored value, writes it via a new `UserGlucoseSyncStateService.schedule(UUID, LocalDateTime nextPollAt)` (sets only `next_poll_at` and `updated_at`).
    - `void pump(Executor executor)` — guarded by `AtomicBoolean deciding` (concurrent call returns immediately). Under the lane monitor: build ordered due list excluding in-flight users, call `SyncPump.decide`, mark each started user in-flight, remove from heap, remember its `dueAt` (the DB `next_poll_at` it was due for), submit `() -> complete(id, dueAt, safeRun(id))` to `executor`. Emits the lag log.
    - `protected abstract SyncResult run(UUID userId)`.
    - `protected Instant onResult(UUID id, Instant dueAt, SyncResult r, Instant now)` — returns the new key; base writes DB for `NEW_DATA` (`markNewData` with `newestTimestamp`), `NO_CHANGE` (`markNoChange`), `ERROR` (`markError`), each with `SyncSlot.followingSlot(id, dueAt, now)`; `IN_PROGRESS` → no write, key `max(dueAt, now + 15s)`; `SKIPPED_NO_CREDS` → return `null` (drop).
    - `complete(...)`: decrement in-flight; if user is still a member and key non-null, reinsert.
    - Package-private test accessors: `int inFlight()`, `Optional<Instant> keyOf(UUID)`, `Instant pausedUntil()`.
    - `safeRun`: any `Throwable` from `run` → `SyncResult.of(ERROR)`.
  - `@Component LibreSyncLane extends SyncLane` — `members()` = `configRepository.findActiveLibreUserIdsWithCredentials()`; `run` = `syncService.syncUser(id, false)`; overrides `onResult` for `UNAVAILABLE` (key = `retryAt` or `now + 120s`, written with `markError`) and `RATE_LIMITED` (`pausedUntil = max(current pause, now + 60s)`, key = `pausedUntil`, written with `markError`). Constructor-overload with `Clock` for tests; Spring uses `Clock.systemUTC()`.
  - `UserDataSourceConfigRepository.findActiveLibreUserIdsWithCredentials() : List<UUID>` — JPQL distinct user ids where `dataSource = LIBRE_LINK_UP`, `isActive = true`, email/password/patientId non-null and patientId not blank.
  - Lag log (base): when `inFlight == 8` and oldest due key is `> 60s` before now and last lag log was `≥ 60s` ago → `log.warn("{} sync lagging: oldest due {}s late, {} user(s) waiting", name, lateSec, dueCount)`.

- [ ] **Step 1: Write the failing tests** — `LibreSyncLaneTest` (mutable fake `Clock`, `ManualExecutor`, mocked `LibreLinkUpSyncService`, state service, repo):
  - `futureUser_notStarted`
  - `eighthInFlight_blocksNinth`: 9 due users, pump, don't run → `inFlight() == 8`, ninth still keyed.
  - `newPoint_and_quietSensor_writeFollowingSlot`: `markNewData(id, ts, now, followingSlot)` and `markNoChange(id, now, followingSlot)`.
  - `lockMiss_leavesNextPollAt_andHides15s`: `IN_PROGRESS` → no `mark*`, key `== now + 15s`; pump at +14 s doesn't start, at +15 s does.
  - `rateLimit_pauses60_andRequeuesAtPauseEnd`: user A gets `RATE_LIMITED` at T; user B's slot is T+30; pump at T+59 starts nobody; at T+60 B starts before A (with `MAX_IN_FLIGHT` filled to 7 so only one start fits).
  - `secondRateLimit_extendsPause`: second 429 at T+20 → `pausedUntil == T+80`.
  - `openCircuit_usesBreakerRetryAt` and `openCircuit_missingRetry_uses120s`.
  - `noCredentials_absentFromQueue`: repo returns only the credentialed user.
  - `completionAfterRemoval_doesNotRequeue`: start, reconcile with empty members, run → `keyOf` empty.
  - `inFlightUser_isNotStartedTwice`: two pumps before run → `run` invoked once.
  - `workerThrows_releasesSlotAndRequeues`: `syncUser` throws → `inFlight() == 0`, `markError` with following slot.
  - `reconcile_nullNextPoll_assignsFutureSlot`: `schedule(id, slot)` called with slot `> now`.
- [ ] **Step 2:** `./mvnw -q test -Dtest=LibreSyncLaneTest` → FAIL.
- [ ] **Step 3:** Implement `SyncLane`, `LibreSyncLane`, repo query, `UserGlucoseSyncStateService.schedule`.
- [ ] **Step 4:** `./mvnw -q test -Dtest='LibreSyncLaneTest,SyncSlotTest,SyncPumpTest'` → PASS.
- [ ] **Step 5:** Commit `feat(sync): due-queue lane with Libre rate-limit pause`.

---

### Task 6: `NightscoutSyncLane`

**Files:**
- Create: `src/main/java/che/glucosemonitorbe/sync/NightscoutSyncLane.java`
- Test: `src/test/java/che/glucosemonitorbe/sync/NightscoutSyncLaneTest.java`

**Interfaces:**
- Consumes: `SyncLane`, `NightscoutSyncService.syncUser(UUID)`.
- Produces: `@Component NightscoutSyncLane extends SyncLane` — `members()` = active Nightscout ids minus active Libre ids (both via `findDistinctUserIdsByDataSourceAndIsActiveTrue`); `run` = `nightscoutSyncService.syncUser(id)`; no `onResult` override (errors, 429 included, use the following slot; never pauses).

- [ ] **Step 1: Write the failing tests:** `eighthInFlight_blocksNinth`, `newPoint_writesFollowingSlot`, `error_writesFollowingSlot_andNoPause` (`pausedUntil() == null`), `libreUser_excluded`, and `nightscoutError_doesNotPauseLibre` (one `LibreSyncLane` and one `NightscoutSyncLane` built in the test; NS error → `libreLane.pausedUntil() == null`).
- [ ] **Step 2:** `./mvnw -q test -Dtest=NightscoutSyncLaneTest` → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Same command → PASS.
- [ ] **Step 5:** Commit `feat(sync): Nightscout lane`.

---

### Task 7: Rewire schedulers, startup, forced sync

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/scheduler/LibreLinkUpGlucoseSyncScheduler.java` (drop `syncTimeoutMs`, `awaitAndTally`, `SyncTally`)
- Modify: `src/main/java/che/glucosemonitorbe/scheduler/NightscoutGlucoseSyncScheduler.java` (drop the per-user body and now-unused deps)
- Modify: `src/main/java/che/glucosemonitorbe/sync/LibreSyncLane.java` (add `onForcedSync`)
- Modify: `src/main/java/che/glucosemonitorbe/controller/LibreLinkUpController.java` (call `onForcedSync`)
- Modify: `src/main/resources/application.yml` — add `app.glucose-sync.pump-interval-ms: 1000` and `app.glucose-sync.reconcile-interval-ms: 60000` (stage only these lines with `git add -p`)
- Test: rewrite `src/test/java/che/glucosemonitorbe/scheduler/LibreLinkUpGlucoseSyncSchedulerTest.java`, `NightscoutGlucoseSyncSchedulerTest.java`; extend `LibreSyncLaneTest`, `LibreLinkUpControllerTest`

**Interfaces:**
- Consumes: `SyncLane.reconcile()`, `SyncLane.pump(Executor)`.
- Produces:
  - Each scheduler: same class name, same `@ConditionalOnProperty`, same 8-thread pool + `@PreDestroy`. Methods:
    - `@EventListener(ApplicationReadyEvent.class) void start()` → `lane.reconcile()`.
    - `@Scheduled(fixedDelayString = "${app.glucose-sync.reconcile-interval-ms:60000}", initialDelayString = same) void reconcile()` → `lane.reconcile()`.
    - `@Scheduled(fixedDelayString = "${app.glucose-sync.pump-interval-ms:1000}") void pump()` → `lane.pump(syncExecutor)`.
  - `LibreSyncLane.onForcedSync(UUID userId, SyncResult result)` — for `NEW_DATA`/`NO_CHANGE`/`ERROR`/`RATE_LIMITED`/`UNAVAILABLE`: compute key `SyncSlot.slotAtLeast300After(id, now)` (for `RATE_LIMITED` also set the 60 s pause; key = max of the two), write the DB via the matching `mark*`, and rekey the heap entry if the user is a member and not in flight. `IN_PROGRESS`/`SKIPPED_NO_CREDS`: no-op. Lane beans are unconditional (passive), so the controller can call this even when schedulers are disabled.

- [ ] **Step 1: Write the failing tests:**
  - `LibreLinkUpGlucoseSyncSchedulerTest.pump_delegatesToLaneWithPool` / `reconcile_delegates` (mocked lane; no `syncUser` call on the scheduler thread). Same pair in `NightscoutGlucoseSyncSchedulerTest`. Delete the old "submits every user / awaitAndTally" tests.
  - `LibreSyncLaneTest.forcedSync_setsNextPollAtLeast300Out`: `onForcedSync(id, newData)` → `markNewData(..., nextPollAt)` with `nextPollAt ≥ now + 300s` and on grid; heap key equals it.
  - `LibreLinkUpControllerTest.syncNow_notifiesLane`: `verify(libreSyncLane).onForcedSync(uid, result)`.
- [ ] **Step 2:** `./mvnw -q test -Dtest='*GlucoseSyncSchedulerTest,LibreSyncLaneTest,LibreLinkUpControllerTest'` → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Same command → PASS.
- [ ] **Step 5:** Commit `feat(sync): schedulers pump due-queue lanes every second`.

---

### Task 8: 300-user clock simulations

**Files:**
- Test: `src/test/java/che/glucosemonitorbe/sync/SyncLaneSimulationTest.java`

**Interfaces:**
- Consumes: `LibreSyncLane` (fake clock, mocked state service backed by an in-test `Map<UUID, LocalDateTime>` via Mockito `doAnswer`), `ManualExecutor`.
- Simulation loop: each simulated second → `executor.runAll()` (a 1-second fake sync completes), then `clock += 1s`, then `lane.pump(executor)`.

- [ ] **Step 1: Write the tests:**
  - `startup_nullNextPoll_becomesFutureSlots_andFirstPumpDoesNotStartAll`: 300 users with null `next_poll_at`; `reconcile()`; every stored value `> now` and `≤ now + 300s`; first pump starts `≤ 8` and only users whose slot is `≤ now`.
  - `burst_allDue_drainsWithinWindow`: 300 users stored at `now - 1s`; run 300 simulated seconds; every user started exactly once (count `syncUser` invocations per id), no entry with key `≤ clock` remains, every finished user's stored `next_poll_at` equals `SyncSlot.followingSlot(id, dueAt, completionTime)`.
- [ ] **Step 2:** `./mvnw -q test -Dtest=SyncLaneSimulationTest` → PASS (no production change expected; if it fails, fix the lane in Task 5's files and rerun Task 5's tests too).
- [ ] **Step 3:** Commit `test(sync): 300-user startup and burst simulations`.

---

### Task 9: Full verification

- [ ] **Step 1:** `grep -rn "SKIPPED_BACKOFF\|markSkippedBackoff\|sync-timeout-ms\|awaitAndTally" src` → no hits.
- [ ] **Step 2:** `./mvnw -q test` → BUILD SUCCESS. Report any failure verbatim.
- [ ] **Step 3:** `./mvnw -q -DskipTests package` → BUILD SUCCESS.
- [ ] **Step 4:** Commit any test fixes: `test: align suite with due-queue sync`.

## Spec coverage

| Spec item | Task |
|---|---|
| Slot formula, epoch seconds, JVM-zone `LocalDateTime` | 1, 5 |
| Pure pump; no waiting; re-entrant call does nothing | 2, 5 |
| Oldest due first, cap 8, future not started | 2, 5, 6 |
| Following slot from due-for time; past → now once | 1, 5 |
| Startup null/overdue → next future slot | 1, 5, 8 |
| No-change count doesn't lengthen interval | 5 (writes following slot regardless) |
| Heap index, DB source of truth, no new table | 5 |
| Schedulers keep pools, become pump entry points | 7 |
| Nightscout body → `NightscoutSyncService.syncUser`; services don't write `next_poll_at` | 3, 4 |
| `mark*` take lane's instant; `markError` not hard-coded | 3, 5 |
| NS lane excludes Libre users | 6 |
| Deactivation removes; late write ignored; activation → next slot | 5 (reconcile ≤ 60 s) |
| Forced sync → slot ≥ 300 s later | 7 |
| Failure table (lock miss, UNAVAILABLE, RATE_LIMITED, other errors, no creds) | 3, 5 |
| 429 typed, no region retry, pause order | 3, 5 |
| NS errors never pause; Libre pause doesn't pause NS | 6 |
| No cancellation, no ninth call | 5 |
| Lag log once/min, pool stays 8 | 5 |
| Simulations | 8 |
| Single owner via `app.glucose-sync.enabled` | 7 (unchanged gate) |

**Design choice beyond the spec:** membership changes (save/activate/deactivate) reach the lanes through a 60-second `reconcile()` instead of events from `UserDataSourceConfigService`, so a newly saved config is queued within a minute on its next future slot.
