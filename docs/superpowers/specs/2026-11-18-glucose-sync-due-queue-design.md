# Glucose sync due-queue — design

Date: 2026-11-18. Scope: backend (`glucose-monitor-be`) only.

## Intent

A few hundred people can use the app at once. Each active user needs a CGM reading stored within 5 minutes. Today each source submits every user at once onto 8 workers, waits up to 4 minutes, and the next tick starts 5 minutes after that wait returns. The same account is visited about every 9 minutes, and a Libre restart logs everyone in together from one server IP.

## Goals

- Every user with an active LibreLinkUp or Nightscout config is synced on a 5-minute cadence.
- At most 8 Libre calls are in flight, and at most 8 Nightscout calls are in flight.
- Users are spread across the 5-minute window so a restart does not log every Libre account in during the same second.
- A Libre HTTP 429 pauses new Libre starts for 60 seconds. The user who received it is due again when that pause ends.
- An open phone can still force a Libre sync immediately, and that force sync does not cause a second Libre call within 5 minutes.

## Non-goals

- Raising the pool of 8, or adding a second server. A lag log is the signal to do that later.
- Persisting Libre tokens across a process restart. Slots already spread the logins that follow a restart.
- Changing the phone, the chart API, or the per-user sync lock.
- Deleting `app.libre-sync.fast-interval-minutes`, `app.libre-sync.slow-interval-minutes`, or the Nightscout equivalents. The lanes do not read them.

## Scheduling

Each user has a fixed offset of `slot = floorMod(userId.mostSignificantBits XOR userId.leastSignificantBits, 300)` seconds. Slot math uses epoch seconds. The value stored in `user_glucose_sync_state.next_poll_at` is that instant converted to `LocalDateTime` in the JVM zone, which is how the column is already written.

A one-second loop per lane calls `SyncPump.decide`. That function is pure: due list, clock, in-flight count, and pause in; the users to start and the pause to set out. The loop does not call Libre or Nightscout and does not wait for them. A second invocation while one is still deciding does nothing.

The function starts the oldest due user while the in-flight count is under 8. It never submits the whole user list. A user whose `next_poll_at` is in the future is not started. Two users may share a second. The 8 workers cover that.

After a sync that stored a point or found nothing new, `next_poll_at` becomes the following slot: 300 seconds after the `next_poll_at` this attempt was due for, not 300 seconds after the call returned. If that time is already past, the next attempt is due at the clock’s now, once, and the attempt after that returns to the normal slot.

On startup, a null or overdue `next_poll_at` becomes that user’s next future slot. Users loaded together are not all due at the same instant.

`consecutive_no_change_count` may still increment. It does not lengthen the interval. A quiet sensor is checked on the next slot.

One process owns the queues. Any extra replica keeps `app.glucose-sync.enabled=false`.

## Components

`SyncSlot` is pure. It computes the offset, the following slot, the next future slot, and the slot that is at least 300 seconds after a given instant. `SyncPump.decide` is the pure start decision both lanes call.

`LibreSyncLane` and `NightscoutSyncLane` each hold an in-memory heap ordered by `next_poll_at`, then user id, and a count of in-flight syncs. The heap is an index. `next_poll_at` in the database is the source of truth. There is no new table.

`LibreLinkUpGlucoseSyncScheduler` and `NightscoutGlucoseSyncScheduler` stay the `@Scheduled` entry points and the owners of the existing 8-thread pools. Their “submit every user and wait up to 4 minutes” bodies are removed. Each scheduled method calls its lane’s pump and returns.

Libre work still goes through `LibreLinkUpSyncService.syncUser`. The per-user Nightscout body that today lives in `NightscoutGlucoseSyncScheduler` moves into `NightscoutSyncService.syncUser`. Both methods store readings and return an outcome plus the newest timestamp when there is one. They do not write `next_poll_at`.

The lane writes `next_poll_at` after it sees the outcome, through the existing `markNewData`, `markNoChange`, and `markError` methods. Those methods take the instant the lane decided. `markError` stops hard-coding “now plus 5 minutes”.

The Nightscout lane contains active Nightscout users who do not also have an active Libre config. The Libre lane contains active Libre users.

Deactivating a source removes that user from the heap. A sync already in flight may finish and write state. The heap ignores that write until the source is activated again, and activation assigns the next future slot.

`POST /api/libre/sync-now` still calls `syncUser(userId, true)`. On completion the Libre lane sets `next_poll_at` to the user’s next slot that is at least 300 seconds later.

## Failures

An attempt that does not acquire the per-user lock does not write `next_poll_at`. The lane keeps an in-memory not-before of 15 seconds for that user, so the pump does not start them again during the sync that holds the lock. The attempt that holds the lock writes `next_poll_at` when it finishes.

| Outcome | `next_poll_at` |
|---|---|
| New point, or nothing new | Following 5-minute slot |
| Lock not acquired | Unchanged. Hidden from the pump for 15 seconds |
| `UNAVAILABLE` (Libre circuit open) | The breaker’s retry time. If that time is missing, now plus 120 seconds |
| `RATE_LIMITED` (Libre HTTP 429) | The end of a 60-second Libre pause |
| Any other error, including a timeout or a rejected token | Following 5-minute slot |
| No Libre email, password, or patient id | User is not queued. Saving the config places them on their next future slot |

`LibreLinkUpSyncService` returns `RATE_LIMITED` for HTTP 429. The lane does not inspect exception text. A 429 pauses new Libre starts for 60 seconds and does not try another Libre region. A second 429 pauses for another 60 seconds. When the pause ends, the oldest due user starts first. The user who received the 429 has `next_poll_at` at the pause end, so users whose slots came due during the pause start ahead of them.

A Nightscout error, including HTTP 429, sets that user’s following slot and does not pause either lane. A Libre pause does not pause Nightscout.

One call holds a worker for at most the existing 10-second connect timeout and 30-second read timeout. The lane does not cancel the call and does not start a ninth call in its place.

If the oldest due user is more than 60 seconds late while all 8 workers of that lane are busy, the lane logs the lag and the number of users waiting, at most once a minute. The pool stays at 8.

## Testing

The pump decision is a pure function of the due list, the clock, the in-flight count, and any pause. Tests call it with a fake clock. They do not open HTTP and they do not start the pools.

`SyncSlotTest`

- The same user id always yields the same offset in `0..299`.
- The following slot is 300 seconds after the slot just served.
- When that following slot is already past, the result is the clock’s now.
- A null or overdue startup value becomes the next future slot.

`LibreSyncLaneTest`

- A user due in the future is not started.
- An eighth in-flight sync blocks a ninth start.
- A new point and a quiet sensor both write the following slot.
- A lock miss leaves `next_poll_at` unchanged and hides the user for 15 seconds.
- A 429 sets a 60-second pause and makes that user due at the pause end. During the pause nobody starts. When it ends, an older due user starts before the user who got the 429. A second 429 pauses for another 60 seconds.
- An open circuit uses the breaker’s retry time.
- A user with no Libre credentials is absent from the queue.
- A forced sync sets `next_poll_at` at least 300 seconds out.
- A Nightscout error does not set a Libre pause.

`NightscoutSyncLaneTest` covers the same start cap, the following-slot write, and the rule that a Nightscout failure does not pause Libre.

Two clock simulations use 300 users and a 1-second fake sync on 8 workers:

- Startup: null `next_poll_at` values become future slots, and the first pump does not start all 300.
- Burst: all 300 are already due. At 300 seconds of simulated time every user has been started once, none are still waiting, and each finished user sits on their own following slot.

`LibreLinkUpGlucoseSyncSchedulerTest` and `NightscoutGlucoseSyncSchedulerTest` expect the pump to start only due users. They no longer expect a tick that submits every user and then waits.

No iOS test is required. The phone’s API does not change.

## Capacity

Three hundred users across 300 seconds is about one sync per second. Eight workers provide that while a cached Libre graph call stays under about 8 seconds. The 300-user burst simulation uses a 1-second call so the queue logic itself finishes inside the window. A production call slower than 8 seconds shows up as the lag log, not as a larger pool.
