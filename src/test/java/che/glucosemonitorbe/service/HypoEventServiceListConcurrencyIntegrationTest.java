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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the lost-update race between {@link HypoEventService#list} and
 * {@link HypoEventService#confirm} at the {@link HypoThresholds#MAX_OPEN_MINUTES} staleness
 * boundary, against a real Postgres container with two real, independently-committing
 * connections - the same style as {@link HypoEventServiceConcurrencyIntegrationTest}, which is
 * the precedent this class follows (no class-level {@code @Transactional}, a latch-synchronized
 * thread pool, assertions against what actually landed in the database).
 *
 * <p><b>What the race is.</b> Before the fix, {@code list()} read events with a plain
 * (non-locking) {@code SELECT} and then, in the same read/write transaction, aged out any row it
 * found OPEN and older than {@code MAX_OPEN_MINUTES} by mutating the in-memory entity and calling
 * {@code repository.save}. Hibernate emits a full-row {@code UPDATE} (no {@code @DynamicUpdate}
 * on {@link HypoEvent}), so if that entity was loaded <em>before</em> a concurrent, properly
 * locked {@code confirm()} call committed a rescue note against the same row, {@code list()}'s
 * eventual {@code UPDATE} - executed from a stale in-memory snapshot - overwrites
 * {@code confirmed}'s {@code note_id} back to {@code null} and the state back to
 * {@code EXPIRED}, even though a real {@code hypo_treatment} note now exists uncommitted-to
 * nothing in the database. That both un-suppresses the prompt and disarms {@code confirm}'s
 * idempotency guard (which keys on state {@code CONFIRMED}), reopening the door to a second
 * rescue-carb note for the same hypo.
 *
 * <p><b>Why this test is timing-sensitive, and how that is handled honestly.</b> The race only
 * manifests in a sub-second window: {@code confirm()}'s own {@code expireIfStale} check (run
 * under its pessimistic lock, immediately before it would write) must see the row as
 * <em>not yet</em> stale, while {@code list()}'s unlocked read must see it as stale mere
 * milliseconds later - both are plain wall-clock reads of {@code LocalDateTime.now()} with no
 * injectable {@link java.time.Clock} in production code to pin them deterministically (adding one
 * is out of scope for this fix). Rather than chase an exact millisecond, each attempt backdates
 * {@code detected_at} so the staleness boundary is crossed a small, known delay after a shared
 * start signal, and deliberately staggers {@code list()}'s start just past that crossing while
 * {@code confirm()} starts immediately - then repeats across many attempts so ordinary JVM/DB
 * scheduling jitter has many chances to land inside the window. This is a real race against a
 * real database on real threads throughout; only the retry count is a concession to timing, not
 * the substance of the assertion.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@SuppressWarnings({"resource", "null"})
class HypoEventServiceListConcurrencyIntegrationTest {

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
    @Autowired private JdbcTemplate jdbcTemplate;

    /** Attempts across which the sub-second race window is given a chance to land. */
    private static final int RACE_ATTEMPTS = 150;

    /**
     * The gap between the staleness crossing and {@code list()}'s deliberately-delayed start is
     * swept across attempts (rather than fixed) because how long {@code confirm()} actually takes
     * to reach its own commit - the other edge of the race window - is environment-dependent
     * (JIT warmup, DB round-trip latency, thread scheduling) and not knowable in advance. Cycling
     * the crossing point through a wide range gives some attempt a good chance of landing inside
     * whatever that window turns out to be on this machine.
     */
    private static final long MIN_CROSS_DELAY_MICROS = 500;
    private static final long CROSS_DELAY_STEP_MICROS = 400;
    private static final long CROSS_DELAY_CYCLE = 40;

    /** How long past the crossing {@code list()} deliberately waits before reading. */
    private static final long LIST_AFTER_CROSS_MICROS = 300;

    private UUID userId;

    @BeforeEach
    void setUp() {
        hypoEventRepository.deleteAll();
        noteRepository.deleteAll();
        userRepository.deleteAll();
        userId = newUser("listrace").getId();
    }

    @AfterEach
    void tearDown() {
        hypoEventRepository.deleteAll();
        noteRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("A concurrent list() sweep never erases a rescue note that confirm() committed")
    void concurrentListDoesNotLoseAConcurrentlyConfirmedNote() throws Exception {
        int successfulConfirms = 0;

        for (int attempt = 0; attempt < RACE_ATTEMPTS; attempt++) {
            hypoEventRepository.deleteAll();
            noteRepository.deleteAll();

            long crossDelayMicros = MIN_CROSS_DELAY_MICROS
                    + (attempt % CROSS_DELAY_CYCLE) * CROSS_DELAY_STEP_MICROS;
            long listDelayMicros = crossDelayMicros + LIST_AFTER_CROSS_MICROS;
            UUID eventId = seedEventCrossingStalenessBoundaryAfter(crossDelayMicros);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);

            Future<HypoEventDTO> confirmFuture = pool.submit(() -> {
                ready.countDown();
                go.await();
                return hypoEventService.confirm(userId, eventId, 15.0);
            });
            Future<List<HypoEventDTO>> listFuture = pool.submit(() -> {
                ready.countDown();
                go.await();
                spinWaitMicros(listDelayMicros);
                return hypoEventService.list(userId, State.OPEN);
            });

            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            listFuture.get(20, TimeUnit.SECONDS);

            HypoEventDTO confirmResult;
            try {
                confirmResult = confirmFuture.get(20, TimeUnit.SECONDS);
            } catch (Exception ex) {
                // confirm() saw the row as already stale in this attempt (timing missed the
                // window on the safe side) - not the case under test; try again.
                pool.shutdownNow();
                continue;
            }
            pool.shutdownNow();

            if (confirmResult.noteId() == null) {
                continue;
            }
            successfulConfirms++;

            HypoEvent persisted = hypoEventRepository.findById(eventId).orElseThrow();

            // The assertion that discriminates: a concurrent list() sweep must never clobber the
            // note (or state) that a committed confirm() just wrote, no matter how the two
            // interleaved. Fails against the pre-fix implementation whenever an attempt lands in
            // the race window; structurally cannot fail once list() no longer writes at all.
            assertThat(persisted.getNoteId())
                    .as("attempt %d: a concurrent list() sweep erased the confirmed rescue note",
                            attempt)
                    .isEqualTo(confirmResult.noteId());
            assertThat(persisted.getState())
                    .as("attempt %d: a concurrent list() sweep reverted a confirmed event's state",
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
                .as("the race window must have been exercised by at least one attempt out of %d",
                        RACE_ATTEMPTS)
                .isGreaterThan(0);
    }

    /**
     * Seeds an OPEN event whose {@code detected_at} is backdated so that, {@code crossDelayMicros}
     * microseconds from now, its age crosses {@link HypoThresholds#MAX_OPEN_MINUTES}. Uses a raw
     * JDBC update rather than the entity setter because {@code detected_at} is
     * {@code updatable = false} on {@link HypoEvent} (it is a {@code @CreationTimestamp}), so a
     * normal JPA save would silently not persist a backdated value - the same reason
     * {@code HypoEventRepositoryIntegrationTest}'s sibling age-ordering tests rely on a real
     * insertion delay rather than a setter.
     */
    private UUID seedEventCrossingStalenessBoundaryAfter(long crossDelayMicros) {
        HypoEvent event = hypoEventRepository.saveAndFlush(HypoEvent.builder()
                .userId(userId).triggerGlucoseMmol(3.4).state(State.OPEN).build());
        LocalDateTime detectedAt = LocalDateTime.now()
                .minusMinutes(HypoThresholds.MAX_OPEN_MINUTES)
                .plusNanos(crossDelayMicros * 1_000L);
        jdbcTemplate.update(
                "UPDATE hypo_events SET detected_at = ? WHERE id = ?",
                detectedAt, event.getId());
        return event.getId();
    }

    /** Busy-waits for {@code micros} microseconds using a monotonic clock, for sub-millisecond
     * precision that {@link Thread#sleep} cannot offer - this method is scheduling the read side
     * of a sub-second race, where {@code Thread.sleep}'s coarse OS-timer granularity and overshoot
     * would blow past the window entirely. */
    private static void spinWaitMicros(long micros) {
        long deadline = System.nanoTime() + micros * 1_000L;
        while (System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
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
