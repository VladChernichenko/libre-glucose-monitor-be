package che.glucosemonitorbe.sync;

import che.glucosemonitorbe.domain.UserGlucoseSyncState;
import che.glucosemonitorbe.service.UserGlucoseSyncStateService;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One upstream's due-queue: an in-memory heap of member users keyed by the instant they become
 * eligible, up to {@link #MAX_IN_FLIGHT} concurrent syncs, and next_poll_at written from each outcome.
 * All mutable state is guarded by {@link #lock}; {@link #deciding} only prevents re-entrant pumps.
 */
@Slf4j
public abstract class SyncLane {

    public static final int MAX_IN_FLIGHT = 8;
    /** On the startup reconcile, a lane with at most this many members syncs them all immediately. */
    static final int IMMEDIATE_STARTUP_MAX_USERS = MAX_IN_FLIGHT;
    public static final Duration LOCK_MISS_HIDE = Duration.ofSeconds(15);
    private static final Duration LAG_THRESHOLD = Duration.ofSeconds(60);
    private static final Duration LAG_LOG_INTERVAL = Duration.ofSeconds(60);

    private final String name;
    protected final Clock clock;
    protected final UserGlucoseSyncStateService state;

    private final Object lock = new Object();
    private final AtomicBoolean deciding = new AtomicBoolean(false);
    private final AtomicBoolean reconciling = new AtomicBoolean(false);

    // ---- guarded by lock ----
    private final TreeSet<SyncPump.QueueEntry> heap = new TreeSet<>(SyncPump.ORDER);
    private final Map<UUID, Instant> keys = new HashMap<>();
    /** The DB next_poll_at each queued (or in-flight) user is due for. */
    private final Map<UUID, Instant> dbDue = new HashMap<>();
    private final Set<UUID> members = new HashSet<>();
    private final Set<UUID> inFlight = new HashSet<>();
    private Instant pausedUntil;
    private Instant lastLagLog;
    private boolean firstReconcileDone;

    protected SyncLane(String name, Clock clock, UserGlucoseSyncStateService state) {
        this.name = name;
        this.clock = clock;
        this.state = state;
    }

    protected abstract Set<UUID> members();

    protected abstract SyncResult run(UUID userId);

    /**
     * Syncs membership with {@link #members()}: drops departed users, schedules new ones. On the first
     * call after construction, a lane with at most {@link #IMMEDIATE_STARTUP_MAX_USERS} members makes
     * every member due now (stored value ignored); afterwards new users get their next future slot.
     * An empty first reconcile still consumes the startup-immediate chance ("startup only" by design).
     * Single-flight: a concurrent call returns immediately.
     */
    public void reconcile() {
        if (!reconciling.compareAndSet(false, true)) {
            return;
        }
        try {
            doReconcile();
        } finally {
            reconciling.set(false);
        }
    }

    private void doReconcile() {
        Set<UUID> current = members();
        List<UUID> added = new ArrayList<>();
        boolean immediate;
        synchronized (lock) {
            // Only the startup reconcile may sync immediately, and only when the lane is small.
            immediate = !firstReconcileDone && current.size() <= IMMEDIATE_STARTUP_MAX_USERS;
            firstReconcileDone = true;
            Iterator<UUID> it = members.iterator();
            while (it.hasNext()) {
                UUID id = it.next();
                if (!current.contains(id)) {
                    it.remove();
                    removeKey(id);
                    dbDue.remove(id);
                }
            }
            for (UUID id : current) {
                if (!members.contains(id)) {
                    added.add(id);
                }
            }
        }
        for (UUID id : added) {
            Instant now = clock.instant();
            UserGlucoseSyncState s = state.getOrCreate(id);
            Instant stored = s == null || s.getNextPollAt() == null ? null : toInstant(s.getNextPollAt());
            Instant slot = immediate ? now : SyncSlot.startupSlot(id, stored, now);
            if (!slot.equals(stored)) {
                state.schedule(id, toLocal(slot));
            }
            synchronized (lock) {
                if (members.add(id)) {
                    dbDue.put(id, slot);
                    putKey(id, slot);
                }
            }
        }
    }

    /**
     * A user came back after being inactive: reconcile so they rejoin the lane, then make them due
     * now (they have been without fresh readings since they went inactive). No-op for a user who is
     * not a member (no source in this lane) or is already in flight. If a concurrent reconcile made
     * this one return early, the next periodic reconcile adds the user on their next slot.
     */
    public void wake(UUID id) {
        reconcile();
        synchronized (lock) {
            if (!members.contains(id) || inFlight.contains(id)) {
                return;
            }
        }
        Instant now = clock.instant();
        state.schedule(id, toLocal(now));
        rekeyIfIdle(id, now);
    }

    /** Starts due users up to the in-flight cap. A concurrent call returns immediately. */
    public void pump(Executor executor) {
        if (!deciding.compareAndSet(false, true)) {
            return;
        }
        try {
            Instant now = clock.instant();
            List<UUID> start = new ArrayList<>();
            Map<UUID, Instant> dueAts = new HashMap<>();
            synchronized (lock) {
                List<SyncPump.QueueEntry> ordered = new ArrayList<>();
                for (SyncPump.QueueEntry e : heap) {
                    if (ordered.size() >= MAX_IN_FLIGHT) {
                        break;
                    }
                    if (!inFlight.contains(e.userId())) {
                        ordered.add(e);
                    }
                }
                SyncPump.Decision d = SyncPump.decide(ordered, now, inFlight.size(), MAX_IN_FLIGHT, pausedUntil);
                pausedUntil = d.pausedUntil();
                for (UUID id : d.start()) {
                    Instant key = removeKey(id);
                    Instant due = dbDue.getOrDefault(id, key);
                    inFlight.add(id);
                    start.add(id);
                    dueAts.put(id, due);
                }
                maybeLogLag(now);
            }
            for (UUID id : start) {
                Instant due = dueAts.get(id);
                try {
                    executor.execute(() -> complete(id, due, safeRun(id)));
                } catch (RejectedExecutionException e) {
                    if (executor instanceof ExecutorService es && es.isShutdown()) {
                        log.debug("{} sync: executor shut down, requeued user {}", name, id);
                    } else {
                        log.warn("{} sync: executor rejected user {}", name, id);
                    }
                    synchronized (lock) {
                        inFlight.remove(id);
                        if (members.contains(id)) {
                            putKey(id, due);
                        }
                    }
                }
            }
        } finally {
            deciding.set(false);
        }
    }

    private SyncResult safeRun(UUID id) {
        try {
            SyncResult r = run(id);
            return r == null ? SyncResult.of(SyncOutcome.ERROR) : r;
        } catch (Throwable t) {
            log.warn("{} sync failed for user {}: {}", name, id, t.toString());
            return SyncResult.of(SyncOutcome.ERROR);
        }
    }

    private void complete(UUID id, Instant dueAt, SyncResult r) {
        Instant now = clock.instant();
        Instant key = null;
        Error fatal = null;
        try {
            key = onResult(id, dueAt, r, now);
        } catch (Throwable t) {
            log.warn("{} sync: recording outcome for user {} failed: {}", name, id, t.toString());
            key = SyncSlot.followingSlot(id, dueAt, now);
            if (t instanceof Error err) {
                fatal = err;
            }
        }
        synchronized (lock) {
            inFlight.remove(id);
            if (members.contains(id) && key != null) {
                // A lock miss writes nothing: the DB value stays dueAt.
                dbDue.put(id, r.outcome() == SyncOutcome.IN_PROGRESS ? dueAt : key);
                putKey(id, key);
            } else if (key == null) {
                // No credentials: drop membership so the next reconcile re-adds the user once restored.
                members.remove(id);
                dbDue.remove(id);
            }
        }
        if (fatal != null) {
            throw fatal;
        }
    }

    /** Writes the outcome to the DB and returns the new heap key, or null to drop the user. */
    protected Instant onResult(UUID id, Instant dueAt, SyncResult r, Instant now) {
        Instant next = SyncSlot.followingSlot(id, dueAt, now);
        switch (r.outcome()) {
            case NEW_DATA -> {
                state.markNewData(id, r.newestTimestamp() == null ? 0L : r.newestTimestamp(), toLocal(now), toLocal(next));
                return next;
            }
            case NO_CHANGE -> {
                state.markNoChange(id, toLocal(now), toLocal(next));
                return next;
            }
            case IN_PROGRESS -> {
                Instant hide = now.plus(LOCK_MISS_HIDE);
                return dueAt.isAfter(hide) ? dueAt : hide;
            }
            case SKIPPED_NO_CREDS -> {
                return null;
            }
            default -> {
                state.markError(id, toLocal(now), toLocal(next));
                return next;
            }
        }
    }

    /** Extends the lane pause to at least {@code until}; returns the resulting pause end. */
    protected Instant extendPause(Instant until) {
        synchronized (lock) {
            if (pausedUntil == null || pausedUntil.isBefore(until)) {
                pausedUntil = until;
            }
            return pausedUntil;
        }
    }

    /**
     * Moves a member that is neither in flight nor departed to {@code key} (also the DB value just
     * written). An in-flight user is left alone: its completion sets the key.
     */
    protected void rekeyIfIdle(UUID id, Instant key) {
        synchronized (lock) {
            if (members.contains(id) && !inFlight.contains(id)) {
                dbDue.put(id, key);
                putKey(id, key);
            }
        }
    }

    private void maybeLogLag(Instant now) {
        if (inFlight.size() < MAX_IN_FLIGHT || heap.isEmpty()) {
            return;
        }
        Instant oldest = heap.first().eligibleAt();
        if (!oldest.isBefore(now.minus(LAG_THRESHOLD))) {
            return;
        }
        if (lastLagLog != null && now.isBefore(lastLagLog.plus(LAG_LOG_INTERVAL))) {
            return;
        }
        int dueCount = 0;
        for (SyncPump.QueueEntry e : heap) {
            if (e.eligibleAt().isAfter(now)) {
                break;
            }
            dueCount++;
        }
        long lateSec = Duration.between(oldest, now).getSeconds();
        log.warn("{} sync lagging: oldest due {}s late, {} user(s) waiting", name, lateSec, dueCount);
        lastLagLog = now;
    }

    private void putKey(UUID id, Instant key) {
        removeKey(id);
        keys.put(id, key);
        heap.add(new SyncPump.QueueEntry(id, key));
    }

    private Instant removeKey(UUID id) {
        Instant old = keys.remove(id);
        if (old != null) {
            heap.remove(new SyncPump.QueueEntry(id, old));
        }
        return old;
    }

    protected static LocalDateTime toLocal(Instant i) {
        return LocalDateTime.ofInstant(i, ZoneId.systemDefault());
    }

    protected static Instant toInstant(LocalDateTime t) {
        return t.atZone(ZoneId.systemDefault()).toInstant();
    }

    // ---- test accessors ----
    int inFlight() {
        synchronized (lock) {
            return inFlight.size();
        }
    }

    Optional<Instant> keyOf(UUID id) {
        synchronized (lock) {
            return Optional.ofNullable(keys.get(id));
        }
    }

    Instant pausedUntil() {
        synchronized (lock) {
            return pausedUntil;
        }
    }
}
