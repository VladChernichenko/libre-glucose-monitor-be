package che.glucosemonitorbe.service.libre;

import che.glucosemonitorbe.dto.LibreConnection;
import che.glucosemonitorbe.dto.LibreGlucoseData;
import che.glucosemonitorbe.dto.LibreGlucoseReading;
import che.glucosemonitorbe.dto.LibreSensorInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct unit tests for the LibreLinkUp wire/mapping helpers (previously tested via reflection on
 * the private methods of LibreLinkUpService). BE-M5 decomposition makes them first-class.
 */
class LibreLinkUpResponseParserTest {

    private LibreLinkUpResponseParser parser;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        parser = new LibreLinkUpResponseParser();
    }

    private JsonNode json(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── trendToArrow ──────────────────────────────────────────────────────────

    @ParameterizedTest(name = "trend {0} → {1}")
    @CsvSource({
        "1, ↑↑",
        "2, ↑",
        "3, ↗",
        "4, →",
        "5, ↘",
        "6, ↓",
        "7, ↓↓"
    })
    @DisplayName("trendToArrow — correct arrow for all 7 LLU trend codes")
    void trendToArrow_allSevenCodes(int trend, String expected) {
        assertThat(parser.trendToArrow(trend)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "unknown trend {0} defaults to →")
    @ValueSource(ints = {0, 8, -1, 99})
    @DisplayName("trendToArrow — unknown code defaults to flat arrow")
    void trendToArrow_unknownCode_defaultsToFlat(int trend) {
        assertThat(parser.trendToArrow(trend)).isEqualTo("→");
    }

    // ── glucoseStatus ─────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} mmol/L → {1}")
    @CsvSource({
        "2.0, low",
        "3.89, low",
        "3.9, normal",
        "7.8, normal",
        "9.99, normal",
        "10.0, high",
        "13.0, high",
        "13.9, critical",
        "14.0, critical",
        "20.0, critical"
    })
    @DisplayName("glucoseStatus — correct status for boundary values")
    void glucoseStatus_allRanges(double mmol, String expected) {
        assertThat(parser.glucoseStatus(mmol)).isEqualTo(expected);
    }

    // ── parseTimestamp ────────────────────────────────────────────────────────

    @Test
    @DisplayName("parseTimestamp — ISO-8601 with Z suffix")
    void parseTimestamp_iso8601WithZ() {
        Date d = parser.parseTimestamp("2025-01-14T22:22:33Z");
        assertThat(d).isNotNull();
        assertThat(d.getTime()).isEqualTo(1_736_893_353_000L);
    }

    @Test
    @DisplayName("parseTimestamp — epoch milliseconds string")
    void parseTimestamp_epochMilliseconds() {
        long epochMs = 1_716_471_000_000L;
        Date d = parser.parseTimestamp(String.valueOf(epochMs));
        assertThat(d).isNotNull();
        assertThat(d.getTime()).isEqualTo(epochMs);
    }

    @Test
    @DisplayName("parseTimestamp — US format M/d/yyyy h:mm:ss a")
    void parseTimestamp_usRegionalFormat() {
        Date d = parser.parseTimestamp("1/14/2026 10:22:33 PM");
        assertThat(d).isNotNull();
    }

    @Test
    @DisplayName("parseTimestamp — EU format dd/MM/yyyy HH:mm:ss")
    void parseTimestamp_euRegionalFormat() {
        Date d = parser.parseTimestamp("14/01/2026 22:22:33");
        assertThat(d).isNotNull();
    }

    @Test
    @DisplayName("parseTimestamp — null returns current time (not null)")
    void parseTimestamp_null_returnsFallback() {
        long before = System.currentTimeMillis();
        Date d = parser.parseTimestamp(null);
        long after = System.currentTimeMillis();
        assertThat(d).isNotNull();
        assertThat(d.getTime()).isBetween(before - 1000, after + 1000);
    }

    @Test
    @DisplayName("parseTimestamp — blank returns current time (not null)")
    void parseTimestamp_blank_returnsFallback() {
        long before = System.currentTimeMillis();
        Date d = parser.parseTimestamp("   ");
        long after = System.currentTimeMillis();
        assertThat(d).isNotNull();
        assertThat(d.getTime()).isBetween(before - 1000, after + 1000);
    }

    @Test
    @DisplayName("parseTimestamp — unparseable string returns current time (not null)")
    void parseTimestamp_unparseable_returnsFallback() {
        long before = System.currentTimeMillis();
        Date d = parser.parseTimestamp("not-a-date-at-all");
        long after = System.currentTimeMillis();
        assertThat(d).isNotNull();
        assertThat(d.getTime()).isBetween(before - 1000, after + 1000);
    }

    // ── toConnections (BE-2 envelope unwrap) ──────────────────────────────────

    @Test
    @DisplayName("toConnections — unwraps {\"data\":[...]} envelope, name stored in id field")
    void toConnections_unwrapsEnvelopeAndConcatenatesName() {
        List<LibreConnection> conns = parser.toConnections(json(
                "{\"data\":[{\"patientId\":\"p1\",\"firstName\":\"John\",\"lastName\":\"Doe\","
                        + "\"status\":\"active\",\"lastSync\":\"2025-01-01T00:00:00Z\"}]}"));

        assertThat(conns).hasSize(1);
        assertThat(conns.get(0).getId()).isEqualTo("John Doe");
        assertThat(conns.get(0).getPatientId()).isEqualTo("p1");
        assertThat(conns.get(0).getStatus()).isEqualTo("active");
        assertThat(conns.get(0).getLastSync()).isEqualTo("2025-01-01T00:00:00Z");
    }

    @Test
    @DisplayName("toConnections — missing/non-array data yields empty list (no NPE)")
    void toConnections_missingOrNonArrayData_returnsEmptyList() {
        assertThat(parser.toConnections(json("{}"))).isEmpty();
        assertThat(parser.toConnections(json("{\"data\":{}}"))).isEmpty();
    }

    // ── toGlucoseData (graph mapping + live-measurement merge) ─────────────────

    @Test
    @DisplayName("toGlucoseData — mg/dL → mmol/L (÷18), correct arrow and status")
    void toGlucoseData_mapsMgDlToMmolWithArrowAndStatus() {
        LibreGlucoseData data = parser.toGlucoseData(json(
                "{\"data\":{\"graphData\":[{\"ValueInMgPerDl\":180,"
                        + "\"FactoryTimestamp\":\"2025-01-14T22:22:33Z\",\"Trend\":3}]}}"), "p1");

        assertThat(data.getPatientId()).isEqualTo("p1");
        assertThat(data.getUnit()).isEqualTo("mmol/L");
        assertThat(data.getData()).hasSize(1);
        LibreGlucoseReading r = data.getData().get(0);
        assertThat(r.getValue()).isEqualTo(10.0);      // 180 / 18.0
        assertThat(r.getTrend()).isEqualTo(3);
        assertThat(r.getTrendArrow()).isEqualTo("↗");
        assertThat(r.getStatus()).isEqualTo("high");   // 10.0 mmol/L
        assertThat(r.getUnit()).isEqualTo("mmol/L");
    }

    @Test
    @DisplayName("toGlucoseData — appends live glucoseMeasurement when newer than last graph point")
    void toGlucoseData_appendsLiveMeasurementWhenNewer() {
        LibreGlucoseData data = parser.toGlucoseData(json(
                "{\"data\":{"
                        + "\"graphData\":[{\"ValueInMgPerDl\":90,\"FactoryTimestamp\":\"2025-01-14T22:00:00Z\",\"Trend\":4}],"
                        + "\"connection\":{\"glucoseMeasurement\":{\"ValueInMgPerDl\":126,"
                        + "\"FactoryTimestamp\":\"2025-01-14T22:15:00Z\",\"Trend\":2}}}}"), "p1");

        assertThat(data.getData()).hasSize(2);
        LibreGlucoseReading last = data.getData().get(1);
        assertThat(last.getValue()).isEqualTo(7.0);    // 126 / 18.0
        assertThat(last.getTrendArrow()).isEqualTo("↑");
    }

    @Test
    @DisplayName("toGlucoseData — does NOT append live measurement older than last graph point")
    void toGlucoseData_skipsLiveMeasurementWhenOlder() {
        LibreGlucoseData data = parser.toGlucoseData(json(
                "{\"data\":{"
                        + "\"graphData\":[{\"ValueInMgPerDl\":90,\"FactoryTimestamp\":\"2025-01-14T22:30:00Z\",\"Trend\":4}],"
                        + "\"connection\":{\"glucoseMeasurement\":{\"ValueInMgPerDl\":126,"
                        + "\"FactoryTimestamp\":\"2025-01-14T22:15:00Z\",\"Trend\":2}}}}"), "p1");

        assertThat(data.getData()).hasSize(1);
    }

    @Test
    @DisplayName("toGlucoseData — skips zero-valued / timestampless points")
    void toGlucoseData_skipsZeroValuedPoints() {
        LibreGlucoseData data = parser.toGlucoseData(json(
                "{\"data\":{\"graphData\":["
                        + "{\"ValueInMgPerDl\":0,\"FactoryTimestamp\":\"2025-01-14T22:22:33Z\"},"
                        + "{\"ValueInMgPerDl\":108,\"FactoryTimestamp\":\"2025-01-14T22:25:00Z\",\"Trend\":4}]}}"), "p1");

        assertThat(data.getData()).hasSize(1);
        assertThat(data.getData().get(0).getValue()).isEqualTo(6.0); // 108 / 18.0
    }

    // ── toSensorInfo (activeSensors mapping) ──────────────────────────────────

    @Test
    @DisplayName("toSensorInfo — no active sensor returns unknown placeholder")
    void toSensorInfo_noActiveSensors_returnsUnknown() {
        assertThat(parser.toSensorInfo(json("{\"data\":{}}"), "p1").getStatus()).isEqualTo("unknown");
        LibreSensorInfo empty = parser.toSensorInfo(json("{\"data\":{\"activeSensors\":[]}}"), "p1");
        assertThat(empty.getSensorModel()).isEqualTo("FreeStyle Libre");
        assertThat(empty.getStatus()).isEqualTo("unknown");
    }

    @Test
    @DisplayName("toSensorInfo — maps Libre 3 (dtid 40), serial, age/remaining from 14-day life")
    void toSensorInfo_mapsLibre3ModelSerialAndAge() {
        long activationEpochSec = System.currentTimeMillis() / 1000L - 3L * 86400L; // activated 3 days ago
        LibreSensorInfo info = parser.toSensorInfo(json(
                "{\"data\":{\"activeSensors\":[{"
                        + "\"sensor\":{\"sn\":\"ABC123\",\"a\":" + activationEpochSec + "},"
                        + "\"device\":{\"dtid\":40}}]}}"), "p1");

        assertThat(info.getSerialNumber()).isEqualTo("ABC123");
        assertThat(info.getSensorModel()).isEqualTo("FreeStyle Libre 3");
        assertThat(info.getSensorMaxDays()).isEqualTo(14);
        assertThat(info.getSensorAgeDays()).isEqualTo(3);
        assertThat(info.getDaysRemaining()).isEqualTo(11);
        assertThat(info.getStatus()).isEqualTo("active");
    }

    @Test
    @DisplayName("toSensorInfo — maps Libre 2 (dtid 30); no activation epoch → unknown status")
    void toSensorInfo_mapsLibre2Model_unknownStatusWithoutActivation() {
        LibreSensorInfo info = parser.toSensorInfo(json(
                "{\"data\":{\"activeSensors\":[{\"sensor\":{\"sn\":\"S2\"},\"device\":{\"dtid\":30}}]}}"), "p1");

        assertThat(info.getSensorModel()).isEqualTo("FreeStyle Libre 2");
        assertThat(info.getStatus()).isEqualTo("unknown");
        assertThat(info.getActivationDate()).isNull();
    }

}
