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
        s.setGramsPerUnitBreakfast(8.0);
        s.setGramsPerUnitLunch(10.0);
        s.setGramsPerUnitDinner(12.0);
        s.setGramsPerUnitNight(10.0);
        return s;
    }

    @Test
    void eachWindowHasItsOwnRatio() {
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
    void anUnsetWindowHasNoRatioAndNoOtherWindowStandsIn() {
        UserSettingsDTO s = settings();
        s.setGramsPerUnitLunch(null);
        LocalDateTime noon = LocalDateTime.of(2026, 11, 25, 12, 0);

        assertThat(s.getWindowGramsPerUnit(MealWindow.LUNCH)).isNull();
        assertThat(s.getWindowGramsPerUnit(noon)).isNull();
        assertThat(s.getEffectiveGramsPerUnit(noon)).isNull();
        assertThat(s.getEffectiveGramsPerUnitByWindow(noon)).doesNotContainKey(MealWindow.LUNCH).hasSize(3);
        assertThat(s.getWindowGramsPerUnit((LocalDateTime) null)).as("no time, no window").isNull();
    }

    @Test
    void noRatioAnywhereStaysNull() {
        UserSettingsDTO bare = new UserSettingsDTO();

        assertThat(bare.getWindowGramsPerUnit(MealWindow.LUNCH)).isNull();
        assertThat(bare.getEffectiveGramsPerUnit(LocalDateTime.of(2026, 11, 25, 12, 0))).isNull();
        assertThat(bare.getEffectiveGramsPerUnitByWindow(LocalDateTime.of(2026, 11, 25, 12, 0))).isEmpty();
    }

    @Test
    void olderIphoneBuildsStillGetTheCarbRatioTheyDecode() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        assertThat(mapper.writeValueAsString(settings())).contains("\"carbRatio\":2.0");
        UserSettingsDTO read = mapper.readValue("{\"carbRatio\":7.0,\"isf\":1.0}", UserSettingsDTO.class);
        assertThat(mapper.writeValueAsString(read)).as("not settable: the input 7.0 is ignored")
                .contains("\"carbRatio\":2.0").doesNotContain("7.0");
    }

    @Test
    void olderIphoneBuildsSeeTheRatioWhenEveryWindowSharesIt() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        UserSettingsDTO same = TestWindowRatios.setAll(new UserSettingsDTO(), 8.5);

        assertThat(mapper.readTree(mapper.writeValueAsString(same)).path("gramsPerUnit").asDouble()).isEqualTo(8.5);
    }

    @Test
    void olderIphoneBuildsGetNoSingleRatioWhenTheWindowsDiffer() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        UserSettingsDTO oneUnset = TestWindowRatios.setAll(new UserSettingsDTO(), 8.5);
        oneUnset.setGramsPerUnitNight(null);

        assertThat(mapper.readTree(mapper.writeValueAsString(settings())).path("gramsPerUnit").asText(null))
                .as("absent or null").isNull();
        assertThat(mapper.readTree(mapper.writeValueAsString(oneUnset)).path("gramsPerUnit").asText(null))
                .as("an unset window is not equal to the others").isNull();
    }

    @Test
    void anIncomingSingleRatioIsIgnored() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        UserSettingsDTO read = mapper.readValue("{\"gramsPerUnit\":12.0,\"gramsPerUnitBreakfast\":8.0,"
                + "\"gramsPerUnitLunch\":8.0,\"gramsPerUnitDinner\":8.0,\"gramsPerUnitNight\":8.0}", UserSettingsDTO.class);

        assertThat(read.getGramsPerUnitLunch()).isEqualTo(8.0);
        assertThat(mapper.readTree(mapper.writeValueAsString(read)).path("gramsPerUnit").asDouble())
                .as("reports the windows' shared value, not the 12 sent").isEqualTo(8.0);
    }

    @Test
    void windowRatiosAreSerialisedButTheMapIsNot() throws Exception {
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(settings());

        assertThat(json).contains("\"gramsPerUnitBreakfast\":8.0").doesNotContain("effectiveGramsPerUnitByWindow");
    }
}
