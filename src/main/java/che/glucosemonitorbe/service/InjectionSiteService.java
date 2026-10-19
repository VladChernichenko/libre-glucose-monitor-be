package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.InjectionSiteRotation;
import che.glucosemonitorbe.domain.InjectionSiteZone;
import che.glucosemonitorbe.domain.UserZones;
import che.glucosemonitorbe.dto.InjectionSitesDTO;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.NoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Injection-site rotation from the user's insulin notes (spec F7). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InjectionSiteService {

    /** History the rotation looks at [days]; older sites no longer colour the map. */
    static final int LOOKBACK_DAYS = 30;
    /** Sited doses returned in {@link InjectionSitesDTO#recent()}. */
    static final int RECENT_LIMIT = 10;

    private final NoteRepository noteRepository;
    private final UserSettingsService userSettingsService;

    /** @param forRapid the next dose is rapid-acting (buttocks are then never suggested) */
    public InjectionSitesDTO summary(UUID userId, boolean forRapid) {
        UserSettingsDTO s = userSettingsService.getUserSettings(userId);
        LocalDateTime now = LocalDateTime.now(
                s == null ? ZoneOffset.UTC : UserZones.resolve(s.getTimezone(), s.getUtcOffsetMinutes()));
        return summary(userId, forRapid, now);
    }

    InjectionSitesDTO summary(UUID userId, boolean forRapid, LocalDateTime now) {
        List<Note> sited = noteRepository.findByUserIdAndTimestampBetween(userId, now.minusDays(LOOKBACK_DAYS), now)
                .stream()
                .filter(n -> n.getTimestamp() != null && n.getInsulin() != null && n.getInsulin() > 0)
                .filter(n -> InjectionSiteZone.fromCode(n.getSiteZone()) != null)
                .sorted(Comparator.comparing(Note::getTimestamp).reversed())
                .toList();

        InjectionSiteRotation.Result r = InjectionSiteRotation.evaluate(
                sited.stream().map(n -> new InjectionSiteRotation.Injection(
                        n.getTimestamp(), InjectionSiteZone.fromCode(n.getSiteZone()))).toList(),
                now, forRapid);

        InjectionSitesDTO.Warning warning = r.repeat() == null ? null : new InjectionSitesDTO.Warning(
                r.repeat().zone().code(), r.repeat().count(),
                "Rotate: " + r.repeat().zone().label().toLowerCase() + " " + r.repeat().count()
                        + " times in a row. Repeated use causes lumps that absorb unpredictably.");

        return new InjectionSitesDTO(
                r.zones().stream().map(z -> new InjectionSitesDTO.Zone(
                        z.zone().code(), z.zone().label(), z.lastUsedAt(), z.status().name())).toList(),
                r.suggested() == null ? null : r.suggested().code(),
                warning,
                sited.stream().limit(RECENT_LIMIT).map(n -> new InjectionSitesDTO.Recent(
                        n.getId(), n.getTimestamp(), InjectionSiteZone.fromCode(n.getSiteZone()).code(),
                        n.getInsulin(), n.isLongActing())).toList());
    }
}
