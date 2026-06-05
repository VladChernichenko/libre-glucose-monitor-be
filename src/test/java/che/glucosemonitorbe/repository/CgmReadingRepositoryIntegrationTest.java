package che.glucosemonitorbe.repository;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link CgmReadingRepository} — the shared CGM cache used by both
 * Nightscout and LibreLinkUp. These tests run against a real Postgres container because the
 * source-scoped unique constraints rely on partial indexes which H2 does not fully emulate.
 *
 * <p>Focus areas:
 * <ul>
 *   <li>per-source uniqueness — same external id under different data sources must coexist</li>
 *   <li>fallback uniqueness on {@code (user_id, data_source, date_timestamp)} when external_id is null</li>
 *   <li>scoped queries — {@code findExisting*} only return matches for the given source</li>
 *   <li>scoped delete — {@code deleteByUserIdAndExternalIds} respects the data source</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional  // wraps each test in a tx so @Modifying delete queries (deleteOlderThan,
                // deleteByUserIdAndExternalIds) and the derived deleteByUserId have an
                // enclosing transaction. Rolls back at end of each test for isolation.
@SuppressWarnings({"resource", "null"})
class CgmReadingRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("testdb")
                    .withUsername("test")
                    .withPassword("test");

    @Autowired private CgmReadingRepository repository;
    @Autowired private UserRepository userRepository;
    @PersistenceContext private EntityManager em;

    private UUID userId;
    private UUID otherUserId;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        userRepository.deleteAll();
        userId = newUser("cgmuser").getId();
        otherUserId = newUser("cgmuser2").getId();
    }

    // ── per-source uniqueness ────────────────────────────────────────────────────

    @Test
    @DisplayName("Same external_id under different data sources is allowed")
    void sameExternalIdAcrossSources_ok() {
        repository.save(reading(userId, CgmReading.DataSource.NIGHTSCOUT, "shared-123", 1_700_000_000L, 120));
        repository.save(reading(userId, CgmReading.DataSource.LIBRE_LINK_UP, "shared-123", 1_700_000_300L, 130));

        assertThat(repository.findByUserIdOrderByDateTimestampAsc(userId)).hasSize(2);
    }

    @Test
    @DisplayName("Same external_id under the SAME data source fails the unique constraint")
    void sameExternalIdSameSource_violates() {
        repository.save(reading(userId, CgmReading.DataSource.NIGHTSCOUT, "dup-1", 1_700_000_000L, 120));

        assertThatThrownBy(() ->
                repository.saveAndFlush(reading(userId, CgmReading.DataSource.NIGHTSCOUT, "dup-1", 1_700_000_300L, 130))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Same date_timestamp with null external_id is unique per source")
    void nullExternalId_sameTimestampPerSource() {
        // First row in each source — both should succeed
        repository.save(reading(userId, CgmReading.DataSource.NIGHTSCOUT,   null, 1_700_000_000L, 120));
        repository.save(reading(userId, CgmReading.DataSource.LIBRE_LINK_UP, null, 1_700_000_000L, 121));

        // Second row in the same source with the same timestamp — must fail
        assertThatThrownBy(() ->
                repository.saveAndFlush(reading(userId, CgmReading.DataSource.NIGHTSCOUT, null, 1_700_000_000L, 122))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── scoped queries ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("findByUserIdAndDataSourceAndExternalId returns only the row for the requested source")
    void findByUserIdAndDataSourceAndExternalId_isSourceScoped() {
        repository.save(reading(userId, CgmReading.DataSource.NIGHTSCOUT,   "ext-A", 1L, 100));
        repository.save(reading(userId, CgmReading.DataSource.LIBRE_LINK_UP, "ext-A", 2L, 200));

        Optional<CgmReading> ns =
                repository.findByUserIdAndDataSourceAndExternalId(userId, CgmReading.DataSource.NIGHTSCOUT, "ext-A");
        Optional<CgmReading> llu =
                repository.findByUserIdAndDataSourceAndExternalId(userId, CgmReading.DataSource.LIBRE_LINK_UP, "ext-A");

        assertThat(ns).isPresent();
        assertThat(ns.get().getSgv()).isEqualTo(100);
        assertThat(llu).isPresent();
        assertThat(llu.get().getSgv()).isEqualTo(200);
    }

    @Test
    @DisplayName("findExistingExternalIds returns ids only for the requested source")
    void findExistingExternalIds_filtersBySource() {
        repository.save(reading(userId, CgmReading.DataSource.NIGHTSCOUT,    "ns-1", 1L, 100));
        repository.save(reading(userId, CgmReading.DataSource.NIGHTSCOUT,    "ns-2", 2L, 110));
        repository.save(reading(userId, CgmReading.DataSource.LIBRE_LINK_UP, "ns-1", 3L, 120)); // same id, other source

        List<String> ns = repository.findExistingExternalIds(
                userId, CgmReading.DataSource.NIGHTSCOUT, List.of("ns-1", "ns-2", "missing"));

        assertThat(ns).containsExactlyInAnyOrder("ns-1", "ns-2");
    }

    @Test
    @DisplayName("findExistingDateTimestamps is scoped per source")
    void findExistingDateTimestamps_filtersBySource() {
        repository.save(reading(userId, CgmReading.DataSource.NIGHTSCOUT,    null, 1_000L, 100));
        repository.save(reading(userId, CgmReading.DataSource.LIBRE_LINK_UP, null, 2_000L, 110));

        List<Long> ns = repository.findExistingDateTimestamps(
                userId, CgmReading.DataSource.NIGHTSCOUT, List.of(1_000L, 2_000L, 3_000L));

        assertThat(ns).containsExactly(1_000L);
    }

    // ── scoped delete ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("deleteByUserIdAndExternalIds removes only rows for the given source")
    void deleteByUserIdAndExternalIds_isSourceScoped() {
        repository.save(reading(userId, CgmReading.DataSource.NIGHTSCOUT,    "doomed", 1L, 100));
        repository.save(reading(userId, CgmReading.DataSource.LIBRE_LINK_UP, "doomed", 2L, 110));

        int affected = repository.deleteByUserIdAndExternalIds(
                userId, CgmReading.DataSource.NIGHTSCOUT, List.of("doomed"));

        assertThat(affected).isEqualTo(1);
        assertThat(repository.findByUserIdOrderByDateTimestampAsc(userId)).hasSize(1);
        assertThat(repository.findByUserIdOrderByDateTimestampAsc(userId).get(0).getDataSource())
                .isEqualTo(CgmReading.DataSource.LIBRE_LINK_UP);
    }

    @Test
    @DisplayName("deleteByUserId cascades regardless of source")
    void deleteByUserId_removesAllSources() {
        repository.save(reading(userId, CgmReading.DataSource.NIGHTSCOUT,    "a", 1L, 100));
        repository.save(reading(userId, CgmReading.DataSource.LIBRE_LINK_UP, "b", 2L, 110));
        repository.save(reading(otherUserId, CgmReading.DataSource.NIGHTSCOUT, "c", 3L, 120)); // other user

        repository.deleteByUserId(userId);

        assertThat(repository.countByUserId(userId)).isZero();
        assertThat(repository.countByUserId(otherUserId)).isEqualTo(1);
    }

    @Test
    @DisplayName("deleteOlderThan removes rows by lastUpdated only")
    void deleteOlderThan_byTimestamp() {
        // Save both rows normally (@PrePersist stamps lastUpdated = now for both).
        CgmReading old   = repository.saveAndFlush(reading(userId, CgmReading.DataSource.NIGHTSCOUT, "x", 1L, 100));
        CgmReading fresh = repository.saveAndFlush(reading(userId, CgmReading.DataSource.NIGHTSCOUT, "y", 2L, 110));

        // Back-date "old" via JPQL bulk update — @PrePersist/@PreUpdate do not fire for bulk
        // statements, so this is the only way to inject a historical lastUpdated without
        // removing the lifecycle hook from the production entity.
        em.createQuery("UPDATE CgmReading r SET r.lastUpdated = :ts WHERE r.id = :id")
                .setParameter("ts", LocalDateTime.now().minusDays(30))
                .setParameter("id", old.getId())
                .executeUpdate();
        em.flush();

        int deleted = repository.deleteOlderThan(LocalDateTime.now().minusDays(7));

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.countByUserId(userId)).isEqualTo(1);
        // The remaining row must be the fresh one
        assertThat(repository.findByUserIdOrderByDateTimestampAsc(userId).get(0).getExternalId())
                .isEqualTo(fresh.getExternalId());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

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

    private CgmReading reading(UUID userId,
                               CgmReading.DataSource source,
                               String externalId,
                               Long dateTimestamp,
                               Integer sgv) {
        return CgmReading.builder()
                .userId(userId)
                .dataSource(source)
                .externalId(externalId)
                .dateTimestamp(dateTimestamp)
                .sgv(sgv)
                .lastUpdated(LocalDateTime.now())
                .build();
    }
}
