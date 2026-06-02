package che.glucosemonitorbe.service.libre;

import che.glucosemonitorbe.dto.LibreSensorInfo;
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
import java.util.Date;
import java.util.Locale;
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
}
