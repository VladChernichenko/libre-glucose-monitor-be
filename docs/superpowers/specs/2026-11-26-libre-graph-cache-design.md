# LibreLinkUp graph response cache — design

Date: 2026-11-26 · Size: small change

## Intent

Make fewer backend → LibreView calls to `GET /llu/connections/{patientId}/graph`. Today
every trigger makes its own upstream call: the 5-min sync queue, the iPhone's forced
`sync-now` (every minute while the newest reading is more than 5 min old), pull-to-refresh, the watch
glance's reconnect attempt, and the `/current`, `/history` and `/sensor` endpoints. They all
go through `LibreLinkUpService.fetchGraph`. That method only merges calls that are in flight
at the same moment (`graphInflight`), and `syncUser(force)` merges forced syncs within 8 s.
A response is never reused after that.

## Goals / non-goals / anti-goals

- **Goal:** at most one upstream `/graph` call per user per cache window, whatever triggered it.
- **Non-goal:** speeding up `chart-data` or other Postgres reads (measured at ~7 ms, not a problem).
- **Non-goal:** changing the iPhone's every-minute forced-sync loop. That's a possible follow-up.
- **Anti-goal:** serving LibreView error responses or another user's data from the cache;
  keeping a user's cached graph after they log out or change credentials.

## Approaches

- **A. In-memory cache inside `fetchGraph` (chosen).** It sits next to the existing
  `graphInflight` and `sensorInfoCache`, needs no new infrastructure, and covers every
  trigger. A restart clears it, which costs one extra call per user.
- B. The same cache in Redis. It survives restarts and is shared across replicas, but it adds a
  service on Render and locally. The backend runs as a single instance on the free plan, so
  this was rejected for now.
- C. Change the phone's polling instead. That needs an app release and leaves the watch, the
  scheduler and pull-to-refresh uncovered. Rejected as the fix; kept as a possible follow-up.

## Design

- In `fetchGraph`, before the in-flight check, return the cached `JsonNode` for
  `cacheKey(userId, patientId)` when it is younger than the TTL. On a miss, fetch as today and
  store the body with its fetch time. Only **successful** bodies are stored: those that would
  pass `throwIfApiError`.
- TTL: new property `libre.api.graph-cache-ttl-seconds`, **default 120**. The user asked for
  1–2 min. 60 s would barely help, because the phone's loop also ticks every 60 s.
- **Every caller is served from the cache, including forced syncs** (pull-to-refresh,
  `sync-now`, watch reconnect). LibreView itself updates about once a minute, so a
  reading can be up to 2 min older than a direct call would give. Agreed with the user.
- `logout(userId)` removes the user's graph entries, as it already does for the sensor cache.
  Credential changes reach the cache through the same logout path.
- A debug log line on each cache hit, so the drop in upstream calls shows in the logs.

## Left to the builder

- Whether to reuse `sensorClock` or add a separate clock for testing the TTL.
- How expired entries are removed (drop on read is enough: at most one entry per user,
  each a single graph document).
- Unit tests in `LibreLinkUpServiceTest`: two calls within the TTL make one client call;
  after the TTL they make two; an error body is not cached; logout clears the entry;
  different users don't share entries.
- Note: a cache hit runs inside the per-user circuit breaker and counts as a success. That is
  fine because only bodies under 2 min old that succeeded are cached. Don't serve cached data
  while the breaker is open (it throws before `fetchGraph` runs, so this holds as written).
