package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.User;
import che.glucosemonitorbe.dto.HypoEventDTO;
import che.glucosemonitorbe.entity.HypoEvent;
import che.glucosemonitorbe.entity.HypoEvent.State;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.HypoEventRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.repository.UserRepository;
import che.glucosemonitorbe.service.observer.HypoThresholds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the lost-update race between {@link HypoEventService#onGlucoseReading} (the CGM
 * scanner, called every 5 minutes for every user by {@code GlucoseAnomalyDetector}) and
 * {@link HypoEventService#confirm} - the same failure shape as
 * {@link HypoEventServiceListConcurrencyIntegrationTest}, reached through a different caller.
 * Follows that class's precedent: a real Postgres Testcontainer, two independently-committing
 * threads (no class-level {@code @Transactional}), a latch to line up the start, and assertions
 * against what actually landed in the database.
 *
 * <p><b>What the race is.</b> {@code onGlucoseReading}'s initial lookup of the user's OPEN event
 * is a plain, unlocked {@code SELECT} - deliberately so, since it runs for every user on every
 * scan and the common case (no open event) should not pay for a row lock. The recovery branch
 * then wrote that stale in-memory copy straight back with no freshness check at all: if a
 * concurrent, properly-locked {@code confirm()} call locked the same row, wrote
 * {@code CONFIRMED} plus a real {@code note_id}, and committed while the scanner still held its
 * stale {@code OPEN} snapshot, the scanner's later {@code UPDATE} - a full-row write, since
 * {@link HypoEvent} carries no {@code @Version} - clobbered {@code state} back to
 * {@code EXPIRED} and {@code note_id} back to {@code null}, discarding a real committed rescue
 * note. That both un-suppresses the next prompt and disarms {@code confirm}'s idempotency guard
 * (which keys on state {@code CONFIRMED}), reopening the door to a second rescue-carb note for
 * the same hypo while the patient may still be low.
 *
 * <p><b>Why this test does not need microsecond timing tricks.</b> Unlike the {@code list()} race
 * (which only manifests in a sub-second window at the staleness boundary), this race only needs
 * the scanner's unlocked read to happen before {@code confirm()}'s commit - not before or after
 * any particular write. Once that ordering holds, Postgres itself supplies the rest of the
 * ordering for free: the scanner's eventual {@code UPDATE} targets the same row {@code confirm()}
 * holds under {@code SELECT ... FOR UPDATE}, so if the scanner reaches its write while
 * {@code confirm()}'s transaction is still open, the scanner's statement simply blocks until
 * {@code confirm()} commits and releases the lock - then applies unconditionally in the unfixed
 * code. Starting both calls from a shared latch, with {@code confirm()} doing strictly more work
 * (lock, create a {@code Note}, two saves, commit) than the scanner's single unlocked
 * {@code SELECT}, makes the required read-before-commit ordering the overwhelmingly likely
 * outcome on every run without needing to pin an exact delay.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "app.features.hypo-rescue-logging-enabled=true")
@Testcontainers
@SuppressWarnings({"resource", "null"})
class HypoEventServiceScannerConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("testdb")
                    .withUsername("test")
                    .withPassword("test");

    @Autowired private HypoEventService hypoEventService;
    @Autowired private HypoEventRepository hypoEventRepository;
    @Autowired private NoteRepository noteRepository;
    @Autowired private UserRepository userRepository;

    /** Attempts across which the race window is given a chance to land. */
    private static final int RACE_ATTEMPTS = 30;

    private UUID userId;

    @BeforeEach
    void setUp() {
        hypoEventRepository.deleteAll();
        noteRepository.deleteAll();
        userRepository.deleteAll();
        userId = newUser("scannerrace").getId();
    }

    @AfterEach
    void tearDown() {
        hypoEventRepository.deleteAll();
        noteRepository.deleteAll();
        userRepository.deleteAll();
    }

    /**
     * A recovery-glucose CGM reading arrives (routing into the scanner's unconditional expire
     * branch) at the same moment the user confirms the same event's rescue carbs. Without the
     * fix, whichever of the scanner's read-then-write brackets {@code confirm()}'s commit wins
     * the row: the assertions below fail whenever the scanner's stale {@code EXPIRED} write lands
     * after {@code confirm()}'s {@code CONFIRMED} commit. With the fix, the scanner re-reads the
     * row under the same pessimistic lock immediately before writing, sees the committed
     * {@code CONFIRMED} state, and does nothing.
     */
    @Test
    @DisplayName("A concurrent recovery-reading scan never erases a rescue note that confirm() committed")
    void concurrentScannerRecoveryDoesNotLoseAConcurrentlyConfirmedNote() throws Exception {
        int successfulConfirms = 0;

        for (int attempt = 0; attempt < RACE_ATTEMPTS; attempt++) {
            hypoEventRepository.deleteAll();
            noteRepository.deleteAll();

            HypoEvent event = hypoEventRepository.saveAndFlush(HypoEvent.builder()
                    .userId(userId).triggerGlucoseMmol(3.4).state(State.OPEN).build());
            UUID eventId = event.getId();

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);

            Future<HypoEventDTO> confirmFuture = pool.submit(() -> {
                ready.countDown();
                go.await();
                return hypoEventService.confirm(userId, eventId, 15.0);
            });
            Future<Void> scanFuture = pool.submit(() -> {
                ready.countDown();
                go.await();
                // A recovery-level reading routes straight into the scanner's unconditional
                // expire branch for the user's current OPEN event.
                hypoEventService.onGlucoseReading(userId, HypoThresholds.RECOVERY_MMOL);
                return null;
            });

            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            HypoEventDTO confirmResult = confirmFuture.get(20, TimeUnit.SECONDS);
            scanFuture.get(20, TimeUnit.SECONDS);
            pool.shutdown();

            if (confirmResult.noteId() == null) {
                continue;
            }
            successfulConfirms++;

            HypoEvent persisted = hypoEventRepository.findById(eventId).orElseThrow();

            // The assertion that discriminates: a concurrent recovery scan must never clobber the
            // note (or state) that a committed confirm() just wrote, no matter how the two
            // interleaved.
            assertThat(persisted.getNoteId())
                    .as("attempt %d: a concurrent recovery scan erased the confirmed rescue note",
                            attempt)
                    .isEqualTo(confirmResult.noteId());
            assertThat(persisted.getState())
                    .as("attempt %d: a concurrent recovery scan reverted a confirmed event's state",
                            attempt)
                    .isEqualTo(State.CONFIRMED);

            long noteCount = noteRepository.findByUserIdOrderByTimestampDesc(userId).stream()
                    .filter(Note::isHypoTreatment)
                    .count();
            assertThat(noteCount)
                    .as("attempt %d: exactly one rescue-carb note must exist", attempt)
                    .isEqualTo(1);
        }

        assertThat(successfulConfirms)
                .as("confirm() must have won the race at least once across %d attempts",
                        RACE_ATTEMPTS)
                .isGreaterThan(0);
    }

    // -- helpers ------------------------------------------------------------------

    private User newUser(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User u = User.builder()
                .username(prefix + "_" + suffix)
                .email(prefix + "+" + suffix + "@example.com")
                .password("hash")
                .fullName(prefix)
                .role(User.Role.USER)
                .enabled(true)
                .accountNonExpired(true)
                .credentialsNonExpired(true)
                .accountNonLocked(true)
                .build();
        return userRepository.save(u);
    }
}
