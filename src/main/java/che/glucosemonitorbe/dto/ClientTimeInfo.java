package che.glucosemonitorbe.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Client-side time information to replace server-side LocalDateTime.now()
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClientTimeInfo {
    
    /**
     * Client timestamp in ISO format
     */
    private String timestamp;
    
    /**
     * Client timezone (e.g., "America/New_York")
     */
    private String timezone;
    
    /**
     * Client locale (e.g., "en-US")
     */
    private String locale;
    
    /**
     * Minutes offset from UTC (positive = behind UTC)
     */
    private Integer timezoneOffset;
    
    /**
     * Convert to LocalDateTime using client's timezone context
     */
    public LocalDateTime toLocalDateTime() {
        if (timestamp == null) {
            return LocalDateTime.now();
        }
        
        try {
            // Parse the timestamp and convert to LocalDateTime
            // The timestamp from client is already in their local time
            return LocalDateTime.parse(timestamp.replace("Z", ""));
        } catch (Exception e) {
            // Fallback to current time if parsing fails
            return LocalDateTime.now();
        }
    }
    
    /**
     * Convert to ZonedDateTime using client's timezone
     */
    public ZonedDateTime toZonedDateTime() {
        if (timestamp == null || timezone == null) {
            return ZonedDateTime.now();
        }
        
        try {
            ZoneId zoneId = ZoneId.of(timezone);
            LocalDateTime localDateTime = toLocalDateTime();
            return localDateTime.atZone(zoneId);
        } catch (Exception e) {
            // Fallback to current time if parsing fails
            return ZonedDateTime.now();
        }
    }
    
    /**
     * Get current time in client's timezone
     */
    public static LocalDateTime getCurrentTimeInTimezone(String timezone) {
        if (timezone == null) {
            return LocalDateTime.now();
        }
        
        try {
            ZoneId zoneId = ZoneId.of(timezone);
            return ZonedDateTime.now(zoneId).toLocalDateTime();
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }
    
    /**
     * Create default client time info using server time (fallback)
     */
    public static ClientTimeInfo createDefault() {
        return ClientTimeInfo.builder()
                .timestamp(LocalDateTime.now().toString())
                .timezone("UTC")
                .locale("en-US")
                .timezoneOffset(0)
                .build();
    }
}
