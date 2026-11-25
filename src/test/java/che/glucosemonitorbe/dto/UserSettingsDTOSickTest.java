package che.glucosemonitorbe.dto;

import che.glucosemonitorbe.sick.SickProfile;
import che.glucosemonitorbe.sick.SickSeverity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class UserSettingsDTOSickTest {

    private static final LocalDateTime SICK_FROM = LocalDateTime.of(2026, 11, 10, 8, 0);
    private static final LocalDateTime INSIDE = LocalDateTime.of(2026, 11, 10, 19, 0);   // dinner window
    private static final LocalDateTime OUTSIDE = LocalDateTime.of(2026, 11, 5, 19, 0);   // dinner, before the span

    private static SickProfile moderate() {
        return new SickProfile(List.of(new SickProfile.Span(SICK_FROM, null,
                SICK_FROM.plusHours(20), SickSeverity.MODERATE)), null);
    }

    private static UserSettingsDTO settings() {
        UserSettingsDTO s = new UserSettingsDTO(UUID.randomUUID(), UUID.randomUUID(), 2.0, 1.0, 45, 240);
        s.setIsfDinner(0.85);
        TestWindowRatios.setAll(s, 10.0);
        return s;
    }

    @Test
    void isfIsScaledOnlyInsideASickSpan() {
        UserSettingsDTO sick = settings().withSickProfile(moderate());

        assertThat(sick.getEffectiveIsf(INSIDE)).isCloseTo(0.595, within(1e-9));
        assertThat(sick.getEffectiveIsf(OUTSIDE)).isEqualTo(0.85);
    }

    @Test
    void gramsPerUnitScalesByTheSameFactor() {
        UserSettingsDTO sick = settings().withSickProfile(moderate());

        assertThat(sick.getEffectiveGramsPerUnit(INSIDE)).isCloseTo(7.0, within(1e-9));
        assertThat(sick.getEffectiveGramsPerUnit(OUTSIDE)).isEqualTo(10.0);
    }

    @Test
    void nullBaseValuesStayNull() {
        UserSettingsDTO s = new UserSettingsDTO();
        s.setSickProfile(moderate());

        assertThat(s.getEffectiveIsf(INSIDE)).isNull();
        assertThat(s.getEffectiveGramsPerUnit(INSIDE)).isNull();
    }

    @Test
    void withSickProfileLeavesTheOriginalUntouched() {
        UserSettingsDTO original = settings();

        UserSettingsDTO sick = original.withSickProfile(moderate());

        assertThat(sick).isNotSameAs(original);
        assertThat(original.getEffectiveIsf(INSIDE)).isEqualTo(0.85);
        assertThat(sick.getIsfDinner()).isEqualTo(0.85);
        assertThat(sick.getGramsPerUnitDinner()).isEqualTo(10.0);
        assertThat(sick.getUserId()).isEqualTo(original.getUserId());
    }

    @Test
    void theProfileIsNotSerialisedNorReadFromClients() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(settings().withSickProfile(moderate()));

        assertThat(json).doesNotContain("sickProfile");

        UserSettingsDTO read = mapper.readValue("{\"isf\":1.0,\"sickProfile\":{\"spans\":[]}}", UserSettingsDTO.class);
        assertThat(read.getSickProfile()).isEqualTo(SickProfile.EMPTY);
    }
}
