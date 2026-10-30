package che.glucosemonitorbe.assistant.snapshot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Entry point for note writers: schedules {@link ForecastSnapshotService} work after the note's
 * transaction commits (so the forecast sees the notes as saved) on a single background thread
 * (so the save's response never waits on the forecast, and freeze/edit/delete for one note run in
 * order). Every failure is logged and swallowed: a snapshot must never block or fail a save.
 *
 * <p>The save moment is captured as an {@link Instant} when the writer calls in, and converted to
 * the user's wall time with the user's stored zone.
 */
@Slf4j
@Component
public class ForecastSnapshotTrigger {

    private final ForecastSnapshotService service;
    private final Executor executor;

    @Autowired
    public ForecastSnapshotTrigger(ForecastSnapshotService service) {
        this(service, defaultExecutor());
    }

    ForecastSnapshotTrigger(ForecastSnapshotService service, Executor executor) {
        this.service = service;
        this.executor = executor;
    }

    public void noteSaved(UUID userId, UUID noteId) {
        Instant at = Instant.now();
        afterCommit("save", noteId, () -> service.onNoteSaved(userId, noteId, service.wallTime(userId, at)));
    }

    /** Call only when the note's dose, carbs, time or type changed. */
    public void noteUpdated(UUID userId, UUID noteId, Instant originalSavedAt) {
        Instant at = Instant.now();
        Instant original = originalSavedAt != null ? originalSavedAt : at;
        afterCommit("update", noteId, () -> service.onNoteUpdated(userId, noteId,
                service.wallTime(userId, original), service.wallTime(userId, at)));
    }

    public void noteDeleted(UUID noteId) {
        afterCommit("delete", noteId, () -> service.onNoteDeleted(noteId));
    }

    private void afterCommit(String what, UUID noteId, Runnable task) {
        Runnable safe = () -> {
            try {
                task.run();
            } catch (Exception e) {
                log.warn("Forecast snapshot {} failed for note {}: {}", what, noteId, e.toString());
            }
        };
        Runnable dispatch = () -> {
            try {
                executor.execute(safe);
            } catch (RejectedExecutionException e) {
                log.warn("Forecast snapshot {} dropped for note {}: queue full", what, noteId);
            }
        };
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        dispatch.run();
                    }
                });
            } else {
                dispatch.run();
            }
        } catch (Exception e) {
            log.warn("Forecast snapshot {} not scheduled for note {}: {}", what, noteId, e.toString());
        }
    }

    private static Executor defaultExecutor() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(500), r -> {
            Thread t = new Thread(r, "forecast-snapshot");
            t.setDaemon(true);
            return t;
        });
    }
}
