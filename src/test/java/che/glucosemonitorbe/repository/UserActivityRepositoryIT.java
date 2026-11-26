package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.domain.User;
import che.glucosemonitorbe.domain.UserDataSourceConfig;
import che.glucosemonitorbe.domain.UserDataSourceConfig.DataSourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** users.is_active against real Postgres: the activity queries and the sync-lane membership filter. */
@SpringBootTest(properties = "app.glucose-sync.enabled=false")
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@SuppressWarnings({"resource", "null"})
class UserActivityRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(che.glucosemonitorbe.TestPostgres.IMAGE)
                    .withDatabaseName("testdb").withUsername("test").withPassword("test");

    @Autowired private UserRepository userRepository;
    @Autowired private UserDataSourceConfigRepository configRepository;

    private static final Instant T = Instant.parse("2026-11-26T06:00:00Z");

    @BeforeEach
    void clean() {
        configRepository.deleteAll();
        userRepository.deleteAll();
    }

    private User user(String name) {
        return userRepository.save(User.builder().username(name).email(name + "@example.com").password("hash")
                .fullName(name).role(User.Role.USER).build());
    }

    private void libre(User u) {
        UserDataSourceConfig c = new UserDataSourceConfig();
        c.setUser(u);
        c.setDataSource(DataSourceType.LIBRE_LINK_UP);
        c.setLibreEmail("x@example.com");
        c.setLibrePassword("secret");
        c.setLibrePatientId("patient");
        c.setIsActive(true);
        configRepository.save(c);
    }

    private void nightscout(User u) {
        UserDataSourceConfig c = new UserDataSourceConfig();
        c.setUser(u);
        c.setDataSource(DataSourceType.NIGHTSCOUT);
        c.setNightscoutUrl("https://ns.example.com");
        c.setIsActive(true);
        configRepository.save(c);
    }

    @Test
    void newUser_isActiveWithLastActivity() {
        User u = userRepository.findById(user("fresh").getId()).orElseThrow();
        assertThat(u.isActive()).isTrue();
        assertThat(u.getLastActivityAt()).isNotNull();
    }

    @Test
    void inactiveUsers_areLeftOutOfBothSyncLanes() {
        User active = user("active");
        User idle = user("idle");
        libre(active);
        libre(idle);
        nightscout(active);
        nightscout(idle);

        userRepository.touch(idle.getId(), T);
        assertThat(userRepository.deactivateIfIdleSince(idle.getId(), T)).isEqualTo(1);

        assertThat(configRepository.findActiveLibreUserIdsWithCredentials()).containsExactly(active.getId());
        assertThat(configRepository.findDistinctUserIdsByDataSourceAndIsActiveTrue(DataSourceType.NIGHTSCOUT))
                .containsExactly(active.getId());

        assertThat(userRepository.reactivate(idle.getId(), T.plusSeconds(60))).isEqualTo(1);
        assertThat(userRepository.reactivate(idle.getId(), T.plusSeconds(61))).isZero();
        assertThat(configRepository.findActiveLibreUserIdsWithCredentials())
                .containsExactlyInAnyOrder(active.getId(), idle.getId());
    }

    @Test
    void rejectedLibreCredentials_leaveTheLane_untilCleared() {
        User u = user("badcreds");
        libre(u);

        assertThat(configRepository.markLibreCredentialsRejected(u.getId(), T)).isEqualTo(1);
        assertThat(configRepository.markLibreCredentialsRejected(u.getId(), T.plusSeconds(1))).isZero();
        assertThat(configRepository.findActiveLibreUserIdsWithCredentials()).doesNotContain(u.getId());

        assertThat(configRepository.clearLibreCredentialsRejected(u.getId())).isEqualTo(1);
        assertThat(configRepository.findActiveLibreUserIdsWithCredentials()).contains(u.getId());
    }

    @Test
    void idleCandidates_carryZoneAndRespectCutoff() {
        User old = user("old");
        User recent = user("recent");
        userRepository.touch(old.getId(), T);
        userRepository.touch(recent.getId(), T.plus(5, ChronoUnit.HOURS));

        List<IdleUserCandidate> c = userRepository.findActiveIdleSince(T.plus(1, ChronoUnit.HOURS));

        assertThat(c).extracting(IdleUserCandidate::id).containsExactly(old.getId());
        assertThat(c.get(0).lastActivityAt()).isEqualTo(T);
        assertThat(c.get(0).timezone()).isNull();
    }

    @Test
    void deactivation_losesToNewerActivity() {
        UUID id = user("racer").getId();
        userRepository.touch(id, T);
        userRepository.touch(id, T.plusSeconds(120)); // activity after the candidate was read

        assertThat(userRepository.deactivateIfIdleSince(id, T)).isZero();
        assertThat(userRepository.findById(id).orElseThrow().isActive()).isTrue();
    }
}
