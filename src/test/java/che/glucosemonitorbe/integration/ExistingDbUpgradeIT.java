package che.glucosemonitorbe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import che.glucosemonitorbe.TestPostgres;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A database created from main (Flyway V1-V12, fixtures in {@code db/legacy-main-v12}) upgrades with
 * the documented order: scripts/upgrade-existing-db.sql (run twice: idempotent) -> flyway repair ->
 * app start. The Spring context below boots on that database with Flyway enabled and Hibernate
 * {@code ddl-auto: validate}, exactly as production does.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@SuppressWarnings({"resource", "null"})
class ExistingDbUpgradeIT {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(TestPostgres.IMAGE)
            .withDatabaseName("legacydb").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void prepareLegacyDatabase(DynamicPropertyRegistry registry) throws Exception {
        postgres.start();
        upgradeLegacyDatabase();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    private static void upgradeLegacyDatabase() throws Exception {
        // 1. the database as main left it, with a user and a note in it
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/legacy-main-v12").load().migrate();
        try (Connection c = connection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO users (id, username, email, password, full_name, role, enabled, "
                    + "account_non_expired, account_non_locked, credentials_non_expired) VALUES "
                    + "('11111111-1111-1111-1111-111111111111', 'legacy', 'legacy@example.com', 'x', 'Legacy', "
                    + "'USER', true, true, true, true)");
            st.execute("INSERT INTO user_settings (user_id) VALUES ('11111111-1111-1111-1111-111111111111')");
            st.execute("INSERT INTO notes (id, user_id, timestamp, carbs, insulin, meal) VALUES "
                    + "(gen_random_uuid(), '11111111-1111-1111-1111-111111111111', now(), 40, 3, 'Lunch')");
            assertThat(count(st, "SELECT count(*) FROM clinical_knowledge_chunk")).isEqualTo(9);
        }
        Flyway current = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load();
        assertThatThrownBy(current::validate).as("edited V1/V4/V5/V6 checksums (and pending V13-V16) fail validation before repair");

        // 2. the script, twice
        String script = Files.readString(Path.of("scripts/upgrade-existing-db.sql"));
        try (Connection c = connection(); Statement st = c.createStatement()) {
            st.execute(script);
            st.execute(script);
        }
        // 3. flyway repair; 4. the app start below runs V13-V16 and validates the schema
        current.repair();
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static long count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("main's V12 database + script + repair: the app boots (Flyway V16, Hibernate validate) and keeps the data")
    void legacyDatabaseUpgradesAndBoots() {
        assertThat(jdbc.queryForObject(
                "SELECT max(version::int) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(16);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notes", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT target_glucose_mmol FROM user_settings", Double.class)).isEqualTo(5.55);
        assertThat(jdbc.queryForObject("SELECT rescue_carbs_g FROM user_settings", Integer.class)).isEqualTo(15);
        assertThat(jdbc.queryForList("SELECT title FROM clinical_knowledge_chunk", String.class))
                .containsExactly("General safety disclaimer");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = "
                + "'idx_clinical_knowledge_chunk_embedding'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT to_regclass('experiments') IS NULL", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT to_regclass('meal_episode') IS NOT NULL", Boolean.class)).isTrue();
    }
}
