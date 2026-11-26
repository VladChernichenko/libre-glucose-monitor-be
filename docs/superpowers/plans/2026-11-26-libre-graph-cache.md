# LibreLinkUp Graph Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Serve every caller of `LibreLinkUpService.fetchGraph` from an in-memory, per-user cache, so LibreView's `/graph` is called at most once per user per 120 s.

**Architecture:** Add a `ConcurrentHashMap<String, CachedGraph>` next to the existing `graphInflight` and `sensorInfoCache` in `LibreLinkUpService`. `fetchGraph` returns a fresh-enough cached body before the in-flight check, and stores only successful bodies. `logout` drops the user's entries.

**Tech Stack:** Java 21, Spring Boot, Jackson `JsonNode`, JUnit 5 + Mockito + AssertJ, Gradle.

**Spec:** `docs/superpowers/specs/2026-11-26-libre-graph-cache-design.md`

## Global Constraints

- TTL property: `libre.api.graph-cache-ttl-seconds`, default **120**.
- Cache key: the existing `cacheKey(userId, patientId)`.
- Only cache bodies with no top-level `"error"` field (the condition `throwIfApiError` checks).
- All callers, forced syncs included, are served from the cache. No bypass flag.
- No Redis, no new dependency, no schema change.
- Run only the focused tests listed per task (project rule). Run the full suite once before merge.
- Work on branch `feat/libre-graph-cache` off `main` in `glucose-monitor-be`.

## Review Focus

1. **Error body from LibreView** (`{"error":{...}}`): must not be cached. The next call must go upstream again. → `graphCache_errorBodyIsNotCached` (Task 1).
2. **Two users / two patients**: one user's cached graph must never be served to another. → `graphCache_isPerUser` (Task 1).
3. **Logout or credential change** inside the TTL: the next call must go upstream. → `graphCache_clearedOnLogout` (Task 1).
4. **Exception from the client** (429, timeout): nothing cached; the next call goes upstream. → `graphCache_failureIsNotCached` (Task 1).
5. **Existing sensor test that expects two upstream calls back to back** (`getSensorInfo_unknownIsNotCached`): it now has to move the clock past the graph TTL, or it would pass for the wrong reason. → updated in Task 1.

---

### Task 1: Graph response cache in `LibreLinkUpService`

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/service/LibreLinkUpService.java` (fields at ~60-69, `fetchGraph` at ~427-448, `logout` at ~493-501)
- Modify: `src/main/resources/application.yml` (the `libre.api` block, ~line 209)
- Test: `src/test/java/che/glucosemonitorbe/service/LibreLinkUpServiceTest.java`
- Also commit: `docs/superpowers/specs/2026-11-26-libre-graph-cache-design.md`, this plan

**Interfaces:**
- Produces (package-private, for tests): `long graphCacheTtlSeconds` (default 120, Spring-overridable); `sensorClock` stays the single `Clock` used for both caches.
- No public API changes.

- [ ] **Step 0: Branch**

```bash
cd glucose-monitor-be && git checkout -b feat/libre-graph-cache
```

- [ ] **Step 1: Write the failing tests** in `LibreLinkUpServiceTest`

Add a helper `LibreLinkUpService svcWith(LibreLinkUpClient c)` that builds the service like the existing sensor tests do, and sets `svc.sensorClock` to a mutable clock starting at `2026-11-26T10:00:00Z`. Use the graph body `{"data":{"graphData":[],"activeSensors":[]}}`. Each test calls `sessionStore.saveLogin(userId, "tok", null, null, null, null)` first.

```java
@Test void graphCache_secondCallWithinTtlIsServedFromCache()
  // getGlucoseData("p1",1,u) twice, clock +119 s between
  // verify(client, times(1)).authenticatedGet(eq(u), anyString())

@Test void graphCache_expiresAfterTtl()
  // first call, clock +121 s, second call -> times(2)

@Test void graphCache_sharedBySensorInfo()
  // getGlucoseData then getSensorInfo("p1",u) within TTL -> times(1)

@Test void graphCache_errorBodyIsNotCached()
  // client returns {"error":{"message":"boom"}} then the good body
  // first getGlucoseData throws; second succeeds -> times(2)

@Test void graphCache_failureIsNotCached()
  // client throws RuntimeException("timeout") then returns the good body
  // first call throws; second succeeds -> times(2)

@Test void graphCache_isPerUser()
  // users u1,u2 both logged in; getGlucoseData("p1",1,u1) then ("p1",1,u2)
  // verify times(1) for u1 and times(1) for u2

@Test void graphCache_clearedOnLogout()
  // call, logout(u), saveLogin again, call within TTL -> times(2)
```

Also update `getSensorInfo_unknownIsNotCached`: set a fixed clock, and advance it by 121 s between the two calls, so it still asserts `times(2)`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'che.glucosemonitorbe.service.LibreLinkUpServiceTest'`
Expected: `secondCallWithinTtlIsServedFromCache`, `sharedBySensorInfo`, `isPerUser` FAIL (they get 2 calls, not 1). The others pass, because there is no caching yet.

- [ ] **Step 3: Implement**

In `LibreLinkUpService`:
- `private record CachedGraph(JsonNode body, long fetchedAtMs) {}` and `private final ConcurrentHashMap<String, CachedGraph> graphCache`.
- `@Value("${libre.api.graph-cache-ttl-seconds:120}") long graphCacheTtlSeconds = 120;`. The initializer keeps the 120 default for tests that call `new` directly.
- `fetchGraph`: first look up `graphCache`. If the entry exists and `sensorClock.millis() - fetchedAtMs < graphCacheTtlSeconds * 1000`, log at debug `"LibreLinkUp graph for patient {} served from cache ({} s old)"` and return it. Remove an expired entry when found. After a successful `authenticatedGet`, `put` only when `!json.has("error")`. Keep `rememberSensor` and the in-flight logic as they are.
- `logout`: remove `graphCache` keys with the same `userId + "\0"` prefix, the same way as the sensor cache loop (extract a small `removeUserEntries(Map<String,?>, String prefix)` helper used by both).

In `application.yml`, under `libre.api`, add:
```yaml
    graph-cache-ttl-seconds: ${LIBRE_GRAPH_CACHE_TTL_SECONDS:120}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests 'che.glucosemonitorbe.service.LibreLinkUpServiceTest' --tests 'che.glucosemonitorbe.service.LibreLinkUpServiceReloginTest'`
Expected: BUILD SUCCESSFUL, all green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/che/glucosemonitorbe/service/LibreLinkUpService.java src/main/resources/application.yml src/test/java/che/glucosemonitorbe/service/LibreLinkUpServiceTest.java docs/superpowers/specs/2026-11-26-libre-graph-cache-design.md docs/superpowers/plans/2026-11-26-libre-graph-cache.md
git commit -m "feat(libre): cache LibreLinkUp /graph per user for 120s"
```

### Task 2: Pre-merge check

- [ ] **Step 1: Full suite once**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. If unrelated tests were already failing on `main`, list them and don't fix them here.

- [ ] **Step 2: Manual check against dev DB (optional, LibreView reachable)**

Start the backend and log in as a Libre user. Call `POST /api/libre/sync-now` twice within a minute. The log shows one `Fetching LibreLinkUp graph` line and one `served from cache` debug line (enable debug for `che.glucosemonitorbe.service.LibreLinkUpService`).
