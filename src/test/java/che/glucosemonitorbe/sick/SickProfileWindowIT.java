package che.glucosemonitorbe.sick;

import che.glucosemonitorbe.domain.User;
import che.glucosemonitorbe.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The profile window against a real database: which stored segments reach the {@link SickProfile}. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional
@SuppressWarnings({"resource", "null"})
class SickProfileWindowIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(che.glucosemonitorbe.TestPostgres.IMAGE)
                    .withDatabaseName("testdb").withUsername("test").withPassword("test");

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 14, 0);
    private static final ZoneId ZONE = ZoneId.of("UTC");

    @Autowired SickSegmentRepository segments;
    @Autowired UserRepository users;

    private UUID userId;
    private SickProfileService service;

    @BeforeEach
    void setUp() {
        segments.deleteAll();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        userId = users.save(User.builder().username("sick_" + suffix).email("sick+" + suffix + "@example.com")
                .password("hash").fullName("sick").role(User.Role.USER).enabled(true).accountNonExpired(true)
                .credentialsNonExpired(true).accountNonLocked(true).build()).getId();
        service = new SickProfileService(segments, Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    }

    private void segment(LocalDateTime started, LocalDateTime ended, SickSeverity severity) {
        segments.save(SickSegment.builder().userId(userId).episodeId(UUID.randomUUID()).severity(severity)
                .startedAt(started).declaredAt(started).endedAt(ended)
                .lastConfirmedAt(ended == null ? NOW.minusHours(1) : ended).build());
        segments.flush();
    }

    @Test
    void anOpenSegmentIsInTheProfileHoweverLongAgoItStarted() {
        segment(NOW.minusDays(60), null, SickSeverity.MODERATE);

        assertThat(service.profile(userId, ZONE).spans()).hasSize(1);
    }

    @Test
    void aSegmentThatStartedLongAgoButEndedInsideTheWindowIsInTheProfile() {
        segment(NOW.minusDays(60), NOW.minusDays(10), SickSeverity.MILD);

        assertThat(service.profile(userId, ZONE).spans()).hasSize(1);
    }

    @Test
    void aSegmentThatEndedBeforeTheWindowIsNot() {
        segment(NOW.minusDays(60), NOW.minusDays(50), SickSeverity.MILD);

        assertThat(service.profile(userId, ZONE).spans()).isEmpty();
    }
}
