package che.glucosemonitorbe.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Injection-site rotation for the body map (spec F7). Times are the user's local wall time.
 *
 * @param zones     every body zone with when it was last used and its colour band
 * @param suggested zone code to suggest next
 * @param warning   set when the latest doses keep using one zone
 * @param recent    the latest insulin notes with a recorded site, newest first
 */
public record InjectionSitesDTO(
        List<Zone> zones,
        String suggested,
        Warning warning,
        List<Recent> recent
) {
    /** @param status USED_24H (red), USED_72H (amber) or FREE */
    public record Zone(
            String zone,
            String label,
            @JsonFormat(pattern = GlycemicEventDTO.TIME) LocalDateTime lastUsedAt,
            String status
    ) {}

    public record Warning(String zone, int count, String message) {}

    /** @param zone a zone code, or {@code unknown} */
    public record Recent(
            UUID noteId,
            @JsonFormat(pattern = GlycemicEventDTO.TIME) LocalDateTime time,
            String zone,
            double units,
            boolean basal
    ) {}
}
