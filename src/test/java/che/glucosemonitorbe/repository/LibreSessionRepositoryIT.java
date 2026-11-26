package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.domain.LibreSession;
import che.glucosemonitorbe.domain.User;
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

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** libre_session (V24) against real Postgres: round trip, token encrypted at rest, cascade on user delete. */
@SpringBootTest(properties = "app.glucose-sync.enabled=false")
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@SuppressWarnings({"resource", "null"})
class LibreSessionRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(che.glucosemonitorbe.TestPostgres.IMAGE)
                    .withDatabaseName("testdb").withUsername("test").withPassword("test");

    @Autowired private LibreSessionRepository repository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void roundTrip_tokenEncryptedAtRest_cascadesWithUser() {
        UUID userId = userRepository.save(User.builder().username("llu").email("llu@example.com")
                .password("hash").fullName("LLU").role(User.Role.USER).build()).getId();
        Instant expires = Instant.parse("2027-05-25T12:00:00Z");

        repository.save(new LibreSession(userId, "bearer-secret", "https://api-eu.libreview.io", "acct",
                "en-GB", expires, Instant.now()));

        LibreSession loaded = repository.findById(userId).orElseThrow();
        assertThat(loaded.getToken()).isEqualTo("bearer-secret");
        assertThat(loaded.getExpiresAt()).isEqualTo(expires);
        String raw = jdbc.queryForObject("SELECT token FROM libre_session WHERE user_id = ?", String.class, userId);
        assertThat(raw).isNotEqualTo("bearer-secret").doesNotContain("bearer-secret");

        userRepository.deleteById(userId);
        assertThat(repository.findById(userId)).isEmpty();
    }
}
