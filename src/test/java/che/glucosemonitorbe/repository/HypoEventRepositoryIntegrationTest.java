package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.domain.User;
import che.glucosemonitorbe.entity.HypoEvent;
import che.glucosemonitorbe.entity.HypoEvent.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link HypoEventRepository} - the hypo prompt lifecycle table. Runs
 * against a real Postgres container (rather than H2) to match this repo's convention for
 * repository tests that exercise FK and check-constraint behaviour.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional
@SuppressWarnings({"resource", "null"})
class HypoEventRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("testdb")
                    .withUsername("test")
                    .withPassword("test");

    @Autowired private HypoEventRepository repository;
    @Autowired private UserRepository userRepository;

    private UUID userId;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        userRepository.deleteAll();
        userId = newUser("hypouser").getId();
    }

    @Test
    @DisplayName("Finds the open event for a user")
    void findsTheOpenEventForAUser() {
        repository.save(HypoEvent.builder()
                .userId(userId).triggerGlucoseMmol(3.4).state(State.OPEN).build());

        Optional<HypoEvent> open =
                repository.findFirstByUserIdAndStateOrderByDetectedAtDesc(userId, State.OPEN);

        assertThat(open).isPresent();
        assertThat(open.get().getTriggerGlucoseMmol()).isEqualTo(3.4);
    }

    @Test
    @DisplayName("Does not return a resolved event as open")
    void doesNotReturnAResolvedEventAsOpen() {
        repository.save(HypoEvent.builder()
                .userId(userId).triggerGlucoseMmol(3.4).state(State.CONFIRMED).build());

        assertThat(repository.findFirstByUserIdAndStateOrderByDetectedAtDesc(userId, State.OPEN))
                .isEmpty();
    }

    @Test
    @DisplayName("Most recent event is returned regardless of state")
    void mostRecentEventIsReturnedRegardlessOfState() {
        repository.save(HypoEvent.builder()
                .userId(userId).triggerGlucoseMmol(3.4).state(State.DISMISSED).build());

        assertThat(repository.findFirstByUserIdOrderByDetectedAtDesc(userId)).isPresent();
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
