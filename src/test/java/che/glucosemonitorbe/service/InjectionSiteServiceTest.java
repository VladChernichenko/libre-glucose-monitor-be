package che.glucosemonitorbe.service;

import che.glucosemonitorbe.dto.InjectionSitesDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.NoteRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InjectionSiteServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 12, 0);

    private final NoteRepository notes = mock(NoteRepository.class);
    private final InjectionSiteService service = new InjectionSiteService(notes, mock(UserSettingsService.class));

    private static Note note(int hoursAgo, double insulin, String zone, String type) {
        Note n = new Note(USER, NOW.minusHours(hoursAgo), 0.0, insulin, "x");
        n.setId(UUID.randomUUID());
        n.setType(type);
        n.setSiteZone(zone);
        return n;
    }

    @Test
    void summarisesTheSitedInsulinNotes() {
        Note latest = note(1, 2.0, "abd_ur", Note.TYPE_NORMAL);
        when(notes.findByUserIdAndTimestampBetween(eq(USER), any(), any())).thenReturn(List.of(
                note(2, 0.0, null, Note.TYPE_NORMAL),           // a meal: ignored
                note(3, 1.0, null, Note.TYPE_NORMAL),           // dose with no site: ignored
                note(5, 2.0, "abd_ur", Note.TYPE_NORMAL),
                latest,
                note(14, 20.0, "abd_ur", Note.TYPE_LONG_ACTING),
                note(40, 1.0, "thigh_l", Note.TYPE_NORMAL)));

        InjectionSitesDTO dto = service.summary(USER, true, NOW);

        assertThat(dto.recent()).extracting(InjectionSitesDTO.Recent::noteId).first().isEqualTo(latest.getId());
        assertThat(dto.recent()).hasSize(4);
        assertThat(dto.recent().get(2).basal()).isTrue();
        assertThat(dto.zones()).filteredOn(z -> z.zone().equals("abd_ur"))
                .singleElement().satisfies(z -> {
                    assertThat(z.status()).isEqualTo("USED_24H");
                    assertThat(z.lastUsedAt()).isEqualTo(latest.getTimestamp());
                });
        assertThat(dto.zones()).filteredOn(z -> z.zone().equals("thigh_l"))
                .singleElement().extracting(InjectionSitesDTO.Zone::status).isEqualTo("USED_72H");
        assertThat(dto.suggested()).isEqualTo("abd_ul");
        assertThat(dto.warning()).isNotNull();
        assertThat(dto.warning().zone()).isEqualTo("abd_ur");
        assertThat(dto.warning().count()).isEqualTo(3);
        assertThat(dto.warning().message()).startsWith("Rotate: belly, upper right 3 times in a row");
    }

    @Test
    void anEmptyHistorySuggestsTheFirstZoneWithoutAWarning() {
        when(notes.findByUserIdAndTimestampBetween(eq(USER), any(), any())).thenReturn(List.of());

        InjectionSitesDTO dto = service.summary(USER, true, NOW);

        assertThat(dto.suggested()).isEqualTo("abd_ur");
        assertThat(dto.warning()).isNull();
        assertThat(dto.recent()).isEmpty();
        assertThat(dto.zones()).allMatch(z -> z.status().equals("FREE"));
    }
}
