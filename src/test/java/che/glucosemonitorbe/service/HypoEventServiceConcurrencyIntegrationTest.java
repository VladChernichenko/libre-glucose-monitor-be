package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.User;
import che.glucosemonitorbe.dto.HypoEventDTO;
import che.glucosemonitorbe.entity.HypoEvent;
import che.glucosemonitorbe.entity.HypoEvent.State;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.HypoEventRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the {@code confirm}/{@code dismiss} idempotency guard across two real, concurrent
 * database connections - the failure mode a mock-repository unit test cannot reach. Runs against
 * a real Postgres container so the pessimistic-write lock added to
 * {@link HypoEventRepository#findByIdForUpdate} actually serializes the two callers.
 *
 * <p>Deliberately has NO class-level {@code @Transactional}: the point is that each thread's call
 * into {@link HypoEventService#confirm} commits on its own connection, the same way two concurrent
 * HTTP requests would, so the second caller genuinely blocks on the first's row lock instead of
 * both reading an uncommitted snapshot inside one shared test transaction.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@SuppressWarnings({"resource", "null"})
class HypoEventServiceConcurrencyIntegrationTest {

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

    private UUID userId;

    @BeforeEach
    void setUp() {
        hypoEventRepository.deleteAll();
        noteRepository.deleteAll();
        userRepository.deleteAll();
        userId = newUser("hyporace").getId();
    }

    @AfterEach
    void tearDown() {
        hypoEventRepository.deleteAll();
        noteRepository.deleteAll();
        userRepository.deleteAll();
    }

    /**
     * Two threads confirm the same OPEN event at (as close to) the same instant, synchronized by
     * a latch so both reach {@code confirm} before either commits. Without the pessimistic-write
     * lock, both read OPEN under READ COMMITTED, both pass the in-memory idempotency check, and
     * both write a {@code hypo_treatment} note - exactly the failure the reviewer flagged. With
     * the lock, the second caller blocks until the first commits CONFIRMED, then re-reads and
     * takes the idempotent early-return path.
     */
    @Test
    @DisplayName("Two concurrent confirms on the same event create exactly one hypo_treatment note")
    void concurrentConfirmsCreateExactlyOneNote() throws Exception {
        HypoEvent event = hypoEventRepository.saveAndFlush(HypoEvent.builder()
                .userId(userId).triggerGlucoseMmol(3.4).state(State.OPEN).build());
        UUID eventId = event.getId();

        int threadCount = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);

        List<Future<HypoEventDTO>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return hypoEventService.confirm(userId, eventId, 15.0);
            }));
        }

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();

        List<HypoEventDTO> results = new java.util.ArrayList<>();
        for (Future<HypoEventDTO> future : futures) {
            results.add(future.get(20, TimeUnit.SECONDS));
        }
        pool.shutdown();

        long hypoTreatmentNoteCount = noteRepository.findByUserIdOrderByTimestampDesc(userId)
                .stream()
                .filter(Note::isHypoTreatment)
                .count();
        assertThat(hypoTreatmentNoteCount)
                .as("exactly one rescue-carb note must exist regardless of the race")
                .isEqualTo(1);

        // Both callers must agree on the same note - the second one returned the winner's note,
        // not a phantom of its own.
        assertThat(results).extracting(HypoEventDTO::noteId).doesNotContainNull();
        assertThat(results.get(0).noteId()).isEqualTo(results.get(1).noteId());

        HypoEvent persisted = hypoEventRepository.findById(eventId).orElseThrow();
        assertThat(persisted.getState()).isEqualTo(State.CONFIRMED);
        assertThat(persisted.getResolvedAt()).isNotNull();
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
