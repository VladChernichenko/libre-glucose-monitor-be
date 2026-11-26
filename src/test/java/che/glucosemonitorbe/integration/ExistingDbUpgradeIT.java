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
        assertThatThrownBy(current::validate).as("edited V1/V4/V5/V6 checksums (and pending V13-V24) fail validation before repair");

        // 2. the script, twice
        String script = Files.readString(Path.of("scripts/upgrade-existing-db.sql"));
        try (Connection c = connection(); Statement st = c.createStatement()) {
            st.execute(script);
            st.execute(script);
        }
        // 3. flyway repair; 4. the app start below runs V13-V24 and validates the schema
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
    @DisplayName("main's V12 database + script + repair: the app boots (Flyway V24, Hibernate validate) and keeps the data")
    void legacyDatabaseUpgradesAndBoots() {
        assertThat(jdbc.queryForObject(
                "SELECT max(version::int) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(24);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM insulin_catalog", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT dia_hours FROM insulin_catalog WHERE code = 'FIASP'", Double.class)).isEqualTo(5.0);
        assertThat(jdbc.queryForObject("SELECT dia_hours FROM insulin_catalog WHERE code = 'APIDRA'", Double.class)).isEqualTo(4.5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notes", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT target_glucose_mmol FROM user_settings", Double.class)).isEqualTo(5.55);
        assertThat(jdbc.queryForObject("SELECT rescue_carbs_g FROM user_settings", Integer.class)).isEqualTo(15);
        // main has no ratio in grams per unit: each window is derived once, 10 * isf 1.0 / carb_ratio 2.0
        assertThat(jdbc.queryForMap("SELECT grams_per_unit_breakfast b, grams_per_unit_lunch l, "
                + "grams_per_unit_dinner d, grams_per_unit_night n FROM user_settings"))
                .containsEntry("b", 5.0).containsEntry("l", 5.0).containsEntry("d", 5.0).containsEntry("n", 5.0);
        assertThat(column(jdbc, "grams_per_unit")).as("the single ratio is gone").isZero();
        assertThat(constraint(jdbc, "chk_user_settings_grams_per_unit")).isZero();
        assertThat(column(jdbc, "carb_ratio")).as("the legacy mmol/L per 10 g is gone").isZero();
        assertThat(constraint(jdbc, "chk_user_settings_carb_ratio_positive")).isZero();
        for (String w : new String[] {"breakfast", "lunch", "dinner", "night"}) {
            assertThat(column(jdbc, "grams_per_unit_" + w)).isEqualTo(1);
            assertThat(constraint(jdbc, "chk_user_settings_gpu_" + w)).isEqualTo(1);
        }
        assertThat(jdbc.queryForList("SELECT title FROM clinical_knowledge_chunk", String.class))
                .containsExactly("General safety disclaimer");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = "
                + "'idx_clinical_knowledge_chunk_embedding'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT to_regclass('experiments') IS NULL", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT to_regclass('meal_episode') IS NOT NULL", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT to_regclass('user_insulin_curve') IS NOT NULL", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT to_regclass('sick_segment') IS NOT NULL", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT to_regclass('carb_ratio_meal_window_snapshots') IS NOT NULL",
                Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("deployed at V22 from main (single grams_per_unit, no windows): the windows take the single ratio, then it is dropped")
    void mainV22DatabaseMovesTheSingleRatioIntoTheWindows() throws Exception {
        JdbcTemplate db = upgradedV22Database("deployedmain", false);

        assertThat(windows(db, USER_A)).as("every window takes the single ratio").containsExactly(12.0, 12.0, 12.0, 12.0);
        assertThat(windows(db, USER_C)).as("no single ratio stays no ratio, also on the second run")
                .containsExactly(null, null, null, null);
        assertThat(column(db, "grams_per_unit")).isZero();
        assertThat(constraint(db, "chk_user_settings_grams_per_unit")).isZero();
        assertThat(column(db, "carb_ratio")).isZero();
        assertThat(constraint(db, "chk_user_settings_carb_ratio_positive")).isZero();
    }

    @Test
    @DisplayName("deployed at V22 with this branch's window columns: set windows are kept, unset ones take the single ratio")
    void devV22DatabaseKeepsSetWindowsAndFillsTheRest() throws Exception {
        JdbcTemplate db = upgradedV22Database("deployeddev", true);

        assertThat(windows(db, USER_A)).containsExactly(12.0, 12.0, 12.0, 12.0);
        assertThat(windows(db, USER_B)).as("the breakfast override is kept").containsExactly(6.0, 8.0, 8.0, 8.0);
        assertThat(windows(db, USER_C)).containsExactly(null, null, null, null);
        assertThat(column(db, "grams_per_unit")).isZero();
        assertThat(constraint(db, "chk_user_settings_grams_per_unit")).isZero();
        assertThat(column(db, "carb_ratio")).isZero();
        assertThat(constraint(db, "chk_user_settings_carb_ratio_positive")).isZero();
    }

    // ---- a database deployed at V22 ------------------------------------------------------------

    private static final String USER_A = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String USER_B = "aaaaaaaa-0000-0000-0000-000000000002";
    private static final String USER_C = "aaaaaaaa-0000-0000-0000-000000000003";

    /**
     * A database at V22 as it was deployed: today's migrations up to V22, with user_settings put back
     * to the V1 it was created from ({@code carb_ratio} and the single {@code grams_per_unit}, each
     * with its CHECK; the window
     * columns only when {@code withWindowColumns}, as on a database already upgraded for this branch).
     * Then the script twice, flyway repair, and Flyway applies V23-V24 as the app start would.
     */
    private static JdbcTemplate upgradedV22Database(String name, boolean withWindowColumns) throws Exception {
        try (Connection c = connection(); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        }
        String url = postgres.getJdbcUrl().replace("/legacydb", "/" + name);
        Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").target("22").load().migrate();
        try (Connection c = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
             Statement st = c.createStatement()) {
            st.execute("ALTER TABLE user_settings DROP COLUMN IF EXISTS grams_per_unit, DROP COLUMN IF EXISTS carb_ratio");
            st.execute("ALTER TABLE user_settings ADD COLUMN carb_ratio DOUBLE PRECISION NOT NULL DEFAULT 2.0 "
                    + "CONSTRAINT chk_user_settings_carb_ratio_positive CHECK (carb_ratio > 0)");
            st.execute("ALTER TABLE user_settings ADD COLUMN grams_per_unit DOUBLE PRECISION "
                    + "CONSTRAINT chk_user_settings_grams_per_unit "
                    + "CHECK (grams_per_unit IS NULL OR (grams_per_unit >= 3 AND grams_per_unit <= 30))");
            if (!withWindowColumns) {
                st.execute("ALTER TABLE user_settings DROP COLUMN grams_per_unit_breakfast, "
                        + "DROP COLUMN grams_per_unit_lunch, DROP COLUMN grams_per_unit_dinner, "
                        + "DROP COLUMN grams_per_unit_night");
            }
            for (String id : new String[] {USER_A, USER_B, USER_C}) {
                st.execute("INSERT INTO users (id, username, email, password) VALUES ('" + id + "', '" + id
                        + "', '" + id + "@example.com', 'x')");
            }
            st.execute("INSERT INTO user_settings (user_id, isf, grams_per_unit) VALUES ('" + USER_A + "', 2.0, 12)");
            if (withWindowColumns) {
                st.execute("INSERT INTO user_settings (user_id, isf, grams_per_unit, grams_per_unit_breakfast) "
                        + "VALUES ('" + USER_B + "', 2.0, 8, 6)");
            }
            // No single ratio, though 10 * isf 3.0 / carb_ratio 2.0 = 15 g/U would be derivable: it stays unset.
            st.execute("INSERT INTO user_settings (user_id, isf, grams_per_unit) VALUES ('" + USER_C + "', 3.0, NULL)");

            String script = Files.readString(Path.of("scripts/upgrade-existing-db.sql"));
            st.execute(script);
            st.execute(script);
        }
        Flyway current = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load();
        current.repair();
        current.migrate();
        current.validate();
        JdbcTemplate db = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                url, postgres.getUsername(), postgres.getPassword()));
        assertThat(db.queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE success",
                Integer.class)).isEqualTo(24);
        return db;
    }

    private static java.util.List<Double> windows(JdbcTemplate db, String userId) {
        return db.queryForObject("SELECT grams_per_unit_breakfast, grams_per_unit_lunch, grams_per_unit_dinner, "
                        + "grams_per_unit_night FROM user_settings WHERE user_id = '" + userId + "'",
                (rs, i) -> java.util.Arrays.asList(
                        (Double) rs.getObject(1), (Double) rs.getObject(2),
                        (Double) rs.getObject(3), (Double) rs.getObject(4)));
    }

    private static int column(JdbcTemplate db, String name) {
        return db.queryForObject("SELECT count(*) FROM information_schema.columns "
                + "WHERE table_name = 'user_settings' AND column_name = ?", Integer.class, name);
    }

    private static int constraint(JdbcTemplate db, String name) {
        return db.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname = ?", Integer.class, name);
    }
}
