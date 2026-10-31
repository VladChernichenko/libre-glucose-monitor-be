package che.glucosemonitorbe.assistant.episode;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.domain.GlucoseConversion;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MealEpisodeBuilderTest {

    private static final UUID USER = UUID.randomUUID();
    private static final LocalDateTime MEAL_AT = LocalDateTime.of(2026, 10, 1, 12, 0);
    private final MealEpisodeBuilder builder = new MealEpisodeBuilder();

    private static UserSettingsDTO settings() {
        UserSettingsDTO s = new UserSettingsDTO();
        s.setTimezone("UTC");
        return s;
    }

    private static Note note(LocalDateTime at, double carbs, double insulin, String meal) {
        Note n = new Note(USER, at, carbs, insulin, meal);
        n.setId(UUID.randomUUID());
        return n;
    }

    /** Curve in mg/dL by minute from the meal: up to 200 at +90, down to 62.5 at +200, then up 1/min. */
    private static int sgv(int m) {
        double v;
        if (m <= 0) v = 110;
        else if (m <= 90) v = 110 + m;
        else if (m <= 200) v = 200 - (m - 90) * 1.25;
        else v = 62.5 + (m - 200);
        return (int) Math.round(v);
    }

    private static List<CgmReading> curve() {
        List<CgmReading> out = new ArrayList<>();
        for (int m = -5; m <= 245; m += 5) {
            CgmReading r = new CgmReading();
            r.setUserId(USER);
            r.setSgv(sgv(m));
            r.setTrend(4);
            r.setDateTimestamp(MEAL_AT.plusMinutes(m).toInstant(ZoneOffset.UTC).toEpochMilli());
            out.add(r);
        }
        return out;
    }

    private static double mmol(int sgv) {
        return sgv / GlucoseConversion.MGDL_PER_MMOL;
    }

    private Optional<MealEpisode> build(Note meal, List<Note> others) {
        List<Note> all = new ArrayList<>(others);
        all.add(meal);
        return builder.build(meal, all, curve(), settings());
    }

    @Test
    @DisplayName("outcome fields come from the CGM curve")
    void outcomeFieldsFromCgm() {
        Note meal = note(MEAL_AT, 40, 4, "lunch");
        MealEpisode e = build(meal, List.of()).orElseThrow();

        assertThat(e.getStartGlucose()).isCloseTo(mmol(110), within(1e-9));
        assertThat(e.getStartTrend()).isEqualTo(4);
        assertThat(e.getPeak()).isCloseTo(mmol(200), within(1e-9));
        assertThat(e.getPeakMinute()).isEqualTo(90);
        assertThat(e.getNadir()).isCloseTo(mmol(sgv(200)), within(1e-9));
        assertThat(e.getNadirMinute()).isEqualTo(200);
        assertThat(e.getG60()).isCloseTo(mmol(sgv(60)), within(1e-9));
        assertThat(e.getG120()).isCloseTo(mmol(sgv(120)), within(1e-9));
        assertThat(e.getG240()).isCloseTo(mmol(sgv(240)), within(1e-9));
        assertThat(e.getMinutesBelow39()).isEqualTo(15);   // +195, +200, +205
        assertThat(e.getMinutesAbove10()).isEqualTo(35);   // +75..+90 and +95..+105
        assertThat(e.getLocalMinuteOfDay()).isEqualTo(720);
        assertThat(e.getWeekday()).isEqualTo(4);           // Thursday
        assertThat(e.getDoseUnits()).isEqualTo(4.0);
        assertThat(e.isContaminated()).isFalse();
    }

    @Test
    @DisplayName("an extra rapid dose inside the 4 h marks the episode contaminated")
    void contaminatedWhenExtraDoseWithin4h() {
        Note meal = note(MEAL_AT, 40, 4, "lunch");
        Note correction = note(MEAL_AT.plusMinutes(90), 0, 2, "correction");
        assertThat(build(meal, List.of(correction)).orElseThrow().isContaminated()).isTrue();
    }

    @Test
    @DisplayName("extra carbs inside the 4 h mark the episode contaminated")
    void contaminatedWhenExtraCarbsWithin4h() {
        Note meal = note(MEAL_AT, 40, 4, "lunch");
        Note snack = note(MEAL_AT.plusMinutes(120), 20, 0, "snack");
        assertThat(build(meal, List.of(snack)).orElseThrow().isContaminated()).isTrue();
    }

    @Test
    @DisplayName("long-acting insulin is not a dose that contaminates")
    void longActingIgnored() {
        Note meal = note(MEAL_AT, 40, 4, "lunch");
        Note basal = note(MEAL_AT.plusMinutes(60), 0, 18, "basal");
        basal.setType(Note.TYPE_LONG_ACTING);
        assertThat(build(meal, List.of(basal)).orElseThrow().isContaminated()).isFalse();
    }

    @Test
    @DisplayName("the meal's own dose logged up to 30 min before is the meal dose, not contamination")
    void notContaminatedByOwnDoseWithin30MinBefore() {
        Note meal = note(MEAL_AT, 40, 0, "lunch");
        Note preBolus = note(MEAL_AT.minusMinutes(20), 0, 5, "bolus");
        MealEpisode e = build(meal, List.of(preBolus)).orElseThrow();
        assertThat(e.isContaminated()).isFalse();
        assertThat(e.getDoseUnits()).isEqualTo(5.0);
        assertThat(e.getPauseMinutes()).isEqualTo(20);
    }

    @Test
    @DisplayName("a meal under 10 g carbs, or without a start reading, gives no episode")
    void under10gIgnored() {
        assertThat(build(note(MEAL_AT, 9, 1, "snack"), List.of())).isEmpty();
        Note meal = note(MEAL_AT, 40, 4, "lunch");
        assertThat(builder.build(meal, List.of(meal), List.of(), settings())).isEmpty();
    }

    @Test
    @DisplayName("food text carries the meal, the foods and a preceding activity")
    void foodTextIncludesActivity() {
        Note meal = note(MEAL_AT, 40, 4, "lunch");
        meal.setComment("chicken soup with potato");
        meal.setDetailedInput("rye bread 40 g");
        Note walk = note(MEAL_AT.minusMinutes(60), 0, 0, "walk");
        walk.setType(Note.TYPE_ACTIVITY);
        walk.setActivityType("WALKING");
        walk.setDurationMin(30);

        MealEpisode e = build(meal, List.of(walk)).orElseThrow();

        assertThat(e.getActivityType()).isEqualTo("WALKING");
        assertThat(e.getActivityMinutesAround()).isEqualTo(30);
        assertThat(MealEpisodeBuilder.foodText(e))
                .isEqualTo("lunch, chicken soup with potato, rye bread 40 g, after a walk");
    }
}
