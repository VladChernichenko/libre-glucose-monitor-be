package che.glucosemonitorbe.service.libre;

import che.glucosemonitorbe.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * Decodes LibreLinkUp wire responses and maps their fields to domain values (BE-M5 decomposition):
 * gzip/BOM-aware JSON parsing, error-body summarising, timestamp/trend/status mapping, and the
 * account-id hash. Pure (apart from logging) and unit-testable without a network.
 */
@Component
public class LibreLinkUpResponseParser {

    private static final Logger logger = LoggerFactory.getLogger(LibreLinkUpResponseParser.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Parse a Libre response from raw bytes. Must use {@code byte[]} (not {@code String}) or gzip
     * payloads are corrupted by charset decoding (0x1F / JSON parse errors).
     */
    public JsonNode parseResponseBytes(byte[] body, HttpHeaders headers) throws Exception {
        if (body == null || body.length == 0) {
            throw new RuntimeException("Empty LibreLinkUp auth response body");
        }
        String contentEncoding = headers.getFirst(HttpHeaders.CONTENT_ENCODING);
        boolean headerSaysGzip = contentEncoding != null && contentEncoding.toLowerCase(Locale.ROOT).contains("gzip");
        boolean gzipMagic = body.length >= 2 && (body[0] & 0xFF) == 0x1f && (body[1] & 0xFF) == 0x8b;

        if (gzipMagic || headerSaysGzip) {
            try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(body))) {
                return objectMapper.readTree(gis);
            } catch (IOException e) {
                logger.warn("Libre auth gzip decompress failed ({}), trying plain UTF-8 JSON", e.getMessage());
            }
        }

        int offset = 0;
        if (body.length >= 3 && (body[0] & 0xFF) == 0xef && (body[1] & 0xFF) == 0xbb && (body[2] & 0xFF) == 0xbf) {
            offset = 3;
        }
        return objectMapper.readTree(new String(body, offset, body.length - offset, StandardCharsets.UTF_8));
    }

    /** Short message for logs/API; avoids multi-KB Cloudflare HTML pages in exception text. */
    public static String formatErrorBody(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return "";
        }
        String s = new String(raw, StandardCharsets.UTF_8).strip();
        if (s.isEmpty() || "{}".equals(s)) {
            return "";
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (s.startsWith("<!DOCTYPE") || s.startsWith("<html") || lower.contains("cloudflare")) {
            if (lower.contains("1015") || lower.contains("rate limit") || lower.contains("being rate limited")) {
                return "Cloudflare rate limit (1015): too many requests from this IP; wait several minutes.";
            }
            return "CDN returned HTML (access blocked or challenge), not JSON.";
        }
        final int max = 400;
        if (s.length() > max) {
            return "Body: " + s.substring(0, max) + "...";
        }
        return "Body: " + s;
    }

    /** Hex-encoded SHA-256. Never throws — SHA-256 is always available on the JVM. */
    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Parse a timestamp string to a Date. Handles ISO-8601, Unix ms, and LibreLinkUp regional
     * formats (US "M/d/yyyy h:mm:ss a", EU "dd/MM/yyyy HH:mm:ss"); falls back to now on failure.
     */
    public Date parseTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return new Date();
        }
        if (timestamp.contains("T")) {
            try { return new Date(Instant.parse(timestamp).toEpochMilli()); } catch (Exception ignored) {}
        }
        try { return new Date(Long.parseLong(timestamp.trim())); } catch (Exception ignored) {}
        // Regional formats — FactoryTimestamp is always UTC, so parse as UTC explicitly.
        for (String pattern : new String[]{"M/d/yyyy h:mm:ss a", "M/d/yyyy H:mm:ss", "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm"}) {
            try {
                return Date.from(LocalDateTime.parse(
                                timestamp.trim(), DateTimeFormatter.ofPattern(pattern, Locale.US))
                        .atZone(ZoneOffset.UTC).toInstant());
            } catch (Exception ignored) {}
        }
        logger.warn("Failed to parse timestamp: {}, using current time", timestamp);
        return new Date();
    }

    /** LibreLinkUp 1–7 trend code to arrow symbol. */
    public String trendToArrow(int trend) {
        switch (trend) {
            case 1: return "↑↑";
            case 2: return "↑";
            case 3: return "↗";
            case 4: return "→";
            case 5: return "↘";
            case 6: return "↓";
            case 7: return "↓↓";
            default: return "→";
        }
    }

    /** Glucose status from a value in mmol/L. */
    public String glucoseStatus(double value) {
        if (value < 3.9) return "low";      // < 70 mg/dL
        if (value < 10.0) return "normal";  // 70-180 mg/dL
        if (value < 13.9) return "high";    // 180-250 mg/dL
        return "critical";                   // > 250 mg/dL
    }

    /** Null-safe sensor info when no active sensor is present. */
    public LibreSensorInfo unknownSensorInfo() {
        return new LibreSensorInfo(null, "FreeStyle Libre", null, null, null, 14, "unknown", null);
    }

    // ── wire → domain mapping (BE-M5 decomposition) ───────────────────────────

    /**
     * Map the {@code /llu/connections} success response to domain connections, unwrapping the
     * {@code {"data":[...]}} envelope (BE-2). The display name ("firstName lastName") is stored in
     * the connection's id field, consistent with the {@link LibreConnection} contract.
     */
    public List<LibreConnection> toConnections(JsonNode jsonResponse) {
        List<LibreConnection> connections = new ArrayList<>();
        JsonNode dataNode = jsonResponse.get("data");
        if (dataNode != null && dataNode.isArray()) {
            for (JsonNode connection : dataNode) {
                String patientId = connection.has("patientId") ? connection.get("patientId").asText() : "";
                String firstName = connection.has("firstName") ? connection.get("firstName").asText() : "";
                String lastName  = connection.has("lastName")  ? connection.get("lastName").asText()  : "";
                String status    = connection.has("status")    ? connection.get("status").asText()    : "active";
                String lastSync  = connection.has("lastSync")  ? connection.get("lastSync").asText()  : Instant.now().toString();

                connections.add(new LibreConnection(firstName + " " + lastName, patientId, status, lastSync));
            }
        }
        return connections;
    }

    /**
     * Map a {@code /llu/connections/{id}/graph} success response to domain glucose data. Reads
     * graphData (ValueInMgPerDl → mmol/L at the LLU 18.0 divisor, FactoryTimestamp preferred over
     * Timestamp) and appends the live {@code connection.glucoseMeasurement} when it is newer than
     * the last graph point.
     */
    public LibreGlucoseData toGlucoseData(JsonNode jsonResponse, String patientId) {
        List<LibreGlucoseReading> readings = new ArrayList<>();
        // LLU graph envelope: {"status":0,"data":{"graphData":[...],...}}
        JsonNode dataEnvelope = jsonResponse.get("data");
        JsonNode graphData = dataEnvelope != null ? dataEnvelope.get("graphData") : jsonResponse.get("graphData");
        logger.info("LLU graph response structure: dataEnvelope={}, graphData size={}",
                dataEnvelope != null ? "present" : "absent",
                graphData != null && graphData.isArray() ? graphData.size() : "null/not-array");
        if (graphData != null && graphData.isArray()) {
            for (JsonNode point : graphData) {
                // ValueInMgPerDl is always mg/dL regardless of account unit setting.
                double valueMgDl = 0;
                if (point.has("ValueInMgPerDl")) {
                    valueMgDl = point.get("ValueInMgPerDl").asDouble();
                } else if (point.has("value")) {
                    valueMgDl = point.get("value").asDouble();
                } else if (point.has("glucoseValue")) {
                    valueMgDl = point.get("glucoseValue").asDouble();
                }

                double valueMmolL = valueMgDl / 18.0;

                // FactoryTimestamp is UTC; Timestamp is local. Prefer FactoryTimestamp.
                String timestamp = "";
                if (point.has("FactoryTimestamp")) {
                    timestamp = point.get("FactoryTimestamp").asText();
                } else if (point.has("Timestamp")) {
                    timestamp = point.get("Timestamp").asText();
                } else if (point.has("timestamp")) {
                    timestamp = point.get("timestamp").asText();
                }

                int trend = point.has("Trend") ? point.get("Trend").asInt() : 0;
                if (valueMgDl > 0 && !timestamp.isEmpty()) {
                    readings.add(new LibreGlucoseReading(
                        parseTimestamp(timestamp),
                        valueMmolL,
                        trend,
                        trendToArrow(trend),
                        glucoseStatus(valueMmolL),
                        "mmol/L",
                        parseTimestamp(timestamp)
                    ));
                }
            }
        }

        // connection.glucoseMeasurement is the live reading and can be newer than the last graphData entry.
        if (dataEnvelope != null) {
            JsonNode conn = dataEnvelope.get("connection");
            JsonNode gm = conn != null ? conn.get("glucoseMeasurement") : null;
            if (gm != null) {
                double valueMgDl = gm.has("ValueInMgPerDl") ? gm.get("ValueInMgPerDl").asDouble()
                                 : gm.has("Value")          ? gm.get("Value").asDouble() : 0;
                String ts = gm.has("FactoryTimestamp") ? gm.get("FactoryTimestamp").asText()
                          : gm.has("Timestamp")        ? gm.get("Timestamp").asText() : "";
                int trend = gm.has("Trend") ? gm.get("Trend").asInt() : 0;
                if (valueMgDl > 0 && !ts.isEmpty()) {
                    Date gmDate = parseTimestamp(ts);
                    boolean isNewer = readings.isEmpty()
                            || gmDate.after(readings.get(readings.size() - 1).getTimestamp());
                    if (isNewer) {
                        double mmol = valueMgDl / 18.0;
                        readings.add(new LibreGlucoseReading(
                            gmDate, mmol, trend, trendToArrow(trend),
                            glucoseStatus(mmol), "mmol/L", gmDate
                        ));
                        logger.info("Added glucoseMeasurement ({} mg/dL, trend={}) as latest reading for patient {}",
                            valueMgDl, trend, patientId);
                    }
                }
            }
        }

        return new LibreGlucoseData(
            patientId,
            readings,
            Instant.now().minusSeconds(12 * 60 * 60).toString(), // Last 12 hours
            Instant.now().toString(),
            "mmol/L"
        );
    }

    /**
     * Map the {@code activeSensors[0]} node of a {@code /graph} response to sensor info (model from
     * {@code device.dtid}, age/expiry from the 14-day sensor life); returns
     * {@link #unknownSensorInfo()} when no active sensor is present.
     */
    public LibreSensorInfo toSensorInfo(JsonNode json, String patientId) {
        JsonNode data = json.get("data");
        JsonNode activeSensors = data != null ? data.get("activeSensors") : null;
        if (activeSensors == null || !activeSensors.isArray() || activeSensors.isEmpty()) {
            logger.warn("No activeSensors in graph response for patient {}", patientId);
            return unknownSensorInfo();
        }

        JsonNode sensorEntry = activeSensors.get(0);
        JsonNode sensor = sensorEntry.get("sensor");
        JsonNode device = sensorEntry.get("device");

        String sn = sensor != null && sensor.has("sn") ? sensor.get("sn").asText() : null;

        long activationEpoch = sensor != null && sensor.has("a") ? sensor.get("a").asLong(0) : 0;
        Date activationDate = activationEpoch > 0 ? new Date(activationEpoch * 1000L) : null;

        int sensorMaxDays = 14;
        Date expiryDate = activationDate != null
                ? new Date(activationDate.getTime() + (long) sensorMaxDays * 86400 * 1000L)
                : null;

        Integer ageDays = null;
        Integer remaining = null;
        if (activationDate != null) {
            long ageMs = System.currentTimeMillis() - activationDate.getTime();
            ageDays  = (int) (ageMs / (86400 * 1000L));
            remaining = sensorMaxDays - ageDays;
        }

        String status = "unknown";
        if (ageDays != null) {
            if (ageDays < 0) status = "warmup";
            else if (remaining != null && remaining >= 0) status = "active";
            else status = "expired";
        }

        String model = "FreeStyle Libre";
        if (device != null && device.has("dtid")) {
            int dtid = device.get("dtid").asInt();
            if (dtid == 40 || dtid == 41) model = "FreeStyle Libre 3";
            else if (dtid == 30 || dtid == 31) model = "FreeStyle Libre 2";
            else if (dtid == 5) model = "FreeStyle Libre";
        }

        logger.info("Sensor info for patient {}: model={}, sn={}, age={}d, remaining={}d, status={}",
                patientId, model, sn, ageDays, remaining, status);
        return new LibreSensorInfo(sn, model, activationDate, expiryDate,
                ageDays, sensorMaxDays, status, remaining);
    }

    /**
     * Map the {@code /llu/notifications/alarms} success response to domain alarms; thresholds are
     * mg/dL on the wire and converted to mmol/L (1 dp) at the LLU 18.0 divisor.
     */
    public LibreAlarms toAlarms(JsonNode json, UUID userId) {
        JsonNode data = json.has("data") ? json.get("data") : json;
        JsonNode low  = data.get("lowAlarm");
        JsonNode high = data.get("highAlarm");
        JsonNode sig  = data.get("signalLossAlarm");

        boolean lowEnabled  = low  != null && low.path("enabled").asBoolean(false);
        boolean highEnabled = high != null && high.path("enabled").asBoolean(false);
        boolean sigEnabled  = sig  != null && sig.path("enabled").asBoolean(false);

        Double lowMgDl  = low  != null && low.has("threshold")  ? low.get("threshold").asDouble()  : null;
        Double highMgDl = high != null && high.has("threshold") ? high.get("threshold").asDouble() : null;
        Integer lowSnooze  = low  != null && low.has("snooze")  ? low.get("snooze").asInt()  : null;
        Integer highSnooze = high != null && high.has("snooze") ? high.get("snooze").asInt() : null;

        Double lowMmol  = lowMgDl  != null ? Math.round(lowMgDl  / 18.0 * 10.0) / 10.0 : null;
        Double highMmol = highMgDl != null ? Math.round(highMgDl / 18.0 * 10.0) / 10.0 : null;

        logger.info("LLU alarms for user {}: low={} @{}mmol, high={} @{}mmol, signalLoss={}",
                userId, lowEnabled, lowMmol, highEnabled, highMmol, sigEnabled);
        return new LibreAlarms(lowEnabled, lowMgDl, lowMmol, lowSnooze,
                highEnabled, highMgDl, highMmol, highSnooze, sigEnabled);
    }
}
