package che.glucosemonitorbe.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for the refactored Flyway chain in {@code classpath:db/migration} (V1-V6).
 *
 * <p>Applies migrations on a fresh Postgres container and verifies schema integrity:
 * live tables exist, legacy dead tables are absent, CGM discriminator indexes, and seeds.</p>
 */
@Testcontainers
@SuppressWarnings("resource")
class BaselineSchemaMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("baselinetest")
                    .withUsername("test")
                    .withPassword("test");

    @Test
    @DisplayName("Flyway V1-V6 applies cleanly on a fresh database")
    void baselineApplies() throws SQLException {
        runFlyway();

        try (Connection c = jdbc()) {
            Set<String> tables = listTables(c);

            assertThat(tables).contains(
                    "users",
                    "cob_settings",
                    "notes",
                    "cgm_readings",
                    "user_data_source_config",
                    "insulin_catalog",
                    "user_insulin_preferences",
                    "user_glucose_sync_state",
                    "clinical_knowledge_chunk",
                    "ai_analysis_trace",
                    "glycemic_response_patterns",
                    "token_blacklist",
                    "experiments",
                    "experiment_readings",
                    "verification_events",
                    "verification_summary"
            );

            // dropped tables
            assertThat(tables).doesNotContain(
                    "nightscout_config",
                    "nightscout_chart_data",
                    "carbs_entries",
                    "glucose_readings",
                    "insulin_doses",
                    "user_configurations"
            );
        }
    }

    @Test
    @DisplayName("cgm_readings has a data_source column with the expected CHECK constraint")
    void cgmReadingsHasDataSourceDiscriminator() throws SQLException {
        runFlyway();

        try (Connection c = jdbc(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery(
                    "SELECT data_type FROM information_schema.columns "
                  + "WHERE table_name = 'cgm_readings' AND column_name = 'data_source'");
            assertThat(rs.next()).as("data_source column exists").isTrue();
            assertThat(rs.getString(1)).isEqualToIgnoringCase("character varying");

            ResultSet check = s.executeQuery(
                    "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                  + "WHERE conrelid = 'cgm_readings'::regclass AND contype = 'c'");
            boolean foundSourceCheck = false;
            while (check.next()) {
                String def = check.getString(1);
                if (def.contains("data_source") && def.contains("NIGHTSCOUT") && def.contains("LIBRE_LINK_UP")) {
                    foundSourceCheck = true;
                }
            }
            assertThat(foundSourceCheck)
                    .as("CHECK (data_source IN ('NIGHTSCOUT','LIBRE_LINK_UP')) is present")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("cgm_readings has per-source partial unique indexes")
    void cgmReadingsHasSourceScopedUniqueIndexes() throws SQLException {
        runFlyway();

        try (Connection c = jdbc(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery(
                    "SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'cgm_readings'");
            Set<String> seenUnique = new HashSet<>();
            while (rs.next()) {
                String def = rs.getString("indexdef");
                if (def.startsWith("CREATE UNIQUE INDEX") && def.contains("data_source")) {
                    seenUnique.add(rs.getString("indexname"));
                }
            }
            assertThat(seenUnique).contains(
                    "uk_cgm_readings_user_source_external",
                    "uk_cgm_readings_user_source_ts"
            );
        }
    }

    @Test
    @DisplayName("Insulin catalog seed produces the four expected reference insulins")
    void insulinCatalogSeed() throws SQLException {
        runFlyway();

        try (Connection c = jdbc(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT code FROM insulin_catalog ORDER BY code");
            Set<String> codes = new HashSet<>();
            while (rs.next()) codes.add(rs.getString(1));
            assertThat(codes).containsExactlyInAnyOrder("APIDRA", "FIASP", "LANTUS", "TRESIBA");
        }
    }

    @Test
    @DisplayName("Glycemic response patterns seed covers all seven Warsaw-Method tiers")
    void glycemicResponsePatternsSeed() throws SQLException {
        runFlyway();

        try (Connection c = jdbc(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery(
                    "SELECT pattern_name FROM glycemic_response_patterns ORDER BY pattern_name");
            Set<String> names = new HashSet<>();
            while (rs.next()) names.add(rs.getString(1));
            assertThat(names).containsExactlyInAnyOrder(
                    "Fast Spike", "Slow Climb", "Double Wave", "Flat Plateau",
                    "Blunted Curve", "Light FPU", "Moderate FPU"
            );
        }
    }

    @Test
    @DisplayName("cob_settings carries the positive carb_ratio check")
    void cobSettingsHasCarbRatioCheck() throws SQLException {
        runFlyway();

        try (Connection c = jdbc(); Statement s = c.createStatement()) {
            // Look up by constraint NAME — pg_get_constraintdef reformats inline anonymous
            // checks ("CHECK ((carb_ratio > (0)::double precision))"), which makes substring
            // matches brittle. Naming the constraint in the migration is the stable contract.
            ResultSet rs = s.executeQuery(
                    "SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint "
                  + "WHERE conrelid = 'cob_settings'::regclass AND contype = 'c' "
                  + "  AND conname = 'chk_cob_settings_carb_ratio_positive'");
            assertThat(rs.next())
                    .as("named CHECK constraint chk_cob_settings_carb_ratio_positive exists")
                    .isTrue();
            assertThat(rs.getString(2)).contains("carb_ratio");
        }
    }

    @Test
    @DisplayName("All timestamp columns are TIMESTAMPTZ (no naive TIMESTAMP left)")
    void allTimestampsAreTimestamptz() throws SQLException {
        runFlyway();

        try (Connection c = jdbc(); Statement s = c.createStatement()) {
            // Exclude Flyway's internal flyway_schema_history.installed_on, which Flyway itself
            // creates as a naive TIMESTAMP and we don't own.
            ResultSet rs = s.executeQuery(
                    "SELECT table_name, column_name, data_type "
                  + "FROM information_schema.columns "
                  + "WHERE table_schema = 'public' "
                  + "  AND table_name <> 'flyway_schema_history' "
                  + "  AND data_type IN ('timestamp without time zone', 'timestamp with time zone')");
            int naiveCount = 0;
            StringBuilder offenders = new StringBuilder();
            while (rs.next()) {
                if ("timestamp without time zone".equals(rs.getString("data_type"))) {
                    naiveCount++;
                    offenders.append(rs.getString("table_name"))
                            .append('.').append(rs.getString("column_name"))
                            .append(' ');
                }
            }
            assertThat(naiveCount)
                    .as("Naive TIMESTAMP columns remaining: " + offenders)
                    .isZero();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static void runFlyway() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    @DisplayName("Flyway history lists V1 through V6 with no version gaps")
    void flywayVersionChainIsContiguous() throws SQLException {
        runFlyway();

        try (Connection c = jdbc(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery(
                    "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank");
            java.util.List<String> versions = new java.util.ArrayList<>();
            while (rs.next()) {
                assertThat(rs.getBoolean("success")).isTrue();
                versions.add(rs.getString("version"));
            }
            assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6");
        }
    }

    private Connection jdbc() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private Set<String> listTables(Connection c) throws SQLException {
        Set<String> tables = new HashSet<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT table_name FROM information_schema.tables "
                   + "WHERE table_schema = 'public'")) {
            while (rs.next()) tables.add(rs.getString(1));
        }
        return tables;
    }
}
