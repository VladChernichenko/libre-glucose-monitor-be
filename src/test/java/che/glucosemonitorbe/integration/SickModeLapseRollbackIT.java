package che.glucosemonitorbe.integration;

import che.glucosemonitorbe.dto.RegisterRequest;
import che.glucosemonitorbe.repository.UserRepository;
import che.glucosemonitorbe.sick.SickModeService;
import che.glucosemonitorbe.sick.SickSegment;
import che.glucosemonitorbe.sick.SickSegmentRepository;
import che.glucosemonitorbe.sick.SickSeverity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A lapse closed inside a command that is then rejected must stay closed (no rollback). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@SuppressWarnings("resource")
class SickModeLapseRollbackIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(che.glucosemonitorbe.TestPostgres.IMAGE)
                    .withDatabaseName("testdb").withUsername("test").withPassword("test");

    @Autowired TestRestTemplate rest;
    @Autowired UserRepository userRepository;
    @Autowired SickSegmentRepository segments;
    @Autowired SickModeService service;

    @Test
    void confirmAfterLapseIsRejectedButTheSegmentStaysClosedAsLapsed() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest r = new RegisterRequest();
        r.setUsername("sick_" + suffix);
        r.setEmail("sick+" + suffix + "@example.com");
        r.setFullName("Sick User");
        r.setPassword("testpass123");
        rest.postForEntity("/api/auth/register", r, String.class);
        UUID userId = userRepository.findByUsername(r.getUsername()).orElseThrow().getId();

        LocalDateTime confirmed = LocalDateTime.now().minusDays(5);
        SickSegment stale = segments.save(SickSegment.builder().userId(userId).episodeId(UUID.randomUUID())
                .severity(SickSeverity.MILD).startedAt(confirmed.minusHours(2))
                .declaredAt(confirmed.minusHours(2)).lastConfirmedAt(confirmed).build());

        assertThatThrownBy(() -> service.confirm(userId)).isInstanceOf(IllegalArgumentException.class);

        assertThat(segments.findByUserIdAndEndedAtIsNull(userId)).isEmpty();
        SickSegment closed = segments.findById(stale.getId()).orElseThrow();
        assertThat(closed.getEndReason()).isEqualTo("LAPSED");
        assertThat(closed.getEndedAt()).isEqualToIgnoringNanos(confirmed.plusHours(48));
    }
}
