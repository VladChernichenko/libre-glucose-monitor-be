package che.glucosemonitorbe.dto;

import che.glucosemonitorbe.domain.MealWindow;
import che.glucosemonitorbe.sick.SickProfile;
import che.glucosemonitorbe.sick.SickSeverity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class UserSettingsDTOWindowRatioTest {

    private static final LocalDateTime SICK_FROM = LocalDateTime.of(2026, 11, 10, 8, 0);
    private static final LocalDateTime INSIDE_AT_9 = LocalDateTime.of(2026, 11, 10, 9, 0);   // breakfast, inside the span
    private static final LocalDateTime OUTSIDE_AT_8 = LocalDateTime.of(2026, 11, 5, 8, 0);   // breakfast, before the span

    private static UserSettingsDTO settings() {
        UserSettingsDTO s = new UserSettingsDTO();
        s.setGramsPerUnit(10.0);
        s.setGramsPerUnitBreakfast(8.0);
        s.setGramsPerUnitDinner(12.0);
        return s;
    }

    @Test
    void windowRatioOverridesTheSingleRatio() {
        UserSettingsDTO s = settings();

        assertThat(s.getWindowGramsPerUnit(LocalDateTime.of(2026, 11, 25, 8, 0))).isEqualTo(8.0);
        assertThat(s.getWindowGramsPerUnit(LocalDateTime.of(2026, 11, 25, 12, 0))).isEqualTo(10.0);
        assertThat(s.getWindowGramsPerUnit(MealWindow.DINNER)).isEqualTo(12.0);
        assertThat(s.getWindowGramsPerUnit(MealWindow.NIGHT)).isEqualTo(10.0);
    }

    @Test
    void effectiveRatioIsTheWindowRatioScaledWhileSick() {
        UserSettingsDTO sick = settings().withSickProfile(new SickProfile(List.of(new SickProfile.Span(
                SICK_FROM, null, SICK_FROM.plusHours(20), SickSeverity.MODERATE)), null));

        assertThat(sick.getEffectiveGramsPerUnit(INSIDE_AT_9)).isCloseTo(5.6, within(1e-9));
        assertThat(sick.getEffectiveGramsPerUnit(OUTSIDE_AT_8)).isEqualTo(8.0);
    }

    @Test
    void byWindowMapCarriesEveryWindow() {
        assertThat(settings().getEffectiveGramsPerUnitByWindow(LocalDateTime.of(2026, 11, 25, 8, 0)))
                .containsEntry(MealWindow.BREAKFAST, 8.0).containsEntry(MealWindow.LUNCH, 10.0)
                .containsEntry(MealWindow.DINNER, 12.0).containsEntry(MealWindow.NIGHT, 10.0);
    }

    @Test
    void noRatioAnywhereStaysNull() {
        UserSettingsDTO bare = new UserSettingsDTO();

        assertThat(bare.getWindowGramsPerUnit(MealWindow.LUNCH)).isNull();
        assertThat(bare.getEffectiveGramsPerUnit(LocalDateTime.of(2026, 11, 25, 12, 0))).isNull();
        assertThat(bare.getEffectiveGramsPerUnitByWindow(LocalDateTime.of(2026, 11, 25, 12, 0))).isEmpty();
    }

    @Test
    void windowRatiosAreSerialisedButTheMapIsNot() throws Exception {
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(settings());

        assertThat(json).contains("\"gramsPerUnitBreakfast\":8.0").doesNotContain("effectiveGramsPerUnitByWindow");
    }
}
