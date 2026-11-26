package che.glucosemonitorbe.backtest;

import che.glucosemonitorbe.backtest.MealReplayHarness.Bucket;
import che.glucosemonitorbe.backtest.MealReplayHarness.Data;
import che.glucosemonitorbe.backtest.MealReplayHarness.Forecaster;
import che.glucosemonitorbe.backtest.MealReplayHarness.LowOutcome;
import che.glucosemonitorbe.backtest.MealReplayHarness.Mode;
import che.glucosemonitorbe.backtest.MealReplayHarness.Options;
import che.glucosemonitorbe.backtest.MealReplayHarness.Report;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntToDoubleFunction;
import java.util.function.ToDoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;

class MealReplayHarnessTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final UUID USER = UUID.randomUUID();
    private static final LocalDateTime T = LocalDateTime.of(2026, 11, 10, 12, 0);
    private static final Options AS_LOGGED = new Options(Mode.AS_LOGGED,
            LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1), MealReplayHarness.MealContextFn.NONE);

    /** CGM every 5 min over [T − 2 h, T + 72 h], value as a function of wall time. */
    private static List<BacktestHarness.CgmPoint> cgm(ToDoubleFunction<LocalDateTime> f) {
        List<BacktestHarness.CgmPoint> out = new ArrayList<>();
        for (LocalDateTime t = T.minusHours(2); !t.isAfter(T.plusHours(72)); t = t.plusMinutes(5)) {
            out.add(new BacktestHarness.CgmPoint(t.atZone(UTC).toInstant().toEpochMilli(), f.applyAsDouble(t)));
        }
        return out;
    }

    private static Data data(List<BacktestHarness.CgmPoint> cgm, List<Note> notes) {
        UserSettingsDTO s = new UserSettingsDTO();
        s.setUserId(USER);
        return new Data(cgm, notes, s, Map.of("default", new RapidInsulinIobParameters(4.5, 55)), 42, UTC);
    }

    private static Note note(LocalDateTime t, double carbs, double insulin, String meal) {
        Note n = new Note(USER, t, carbs, insulin, meal);
        n.setId(UUID.randomUUID());
        return n;
    }

    private static List<PredictionPointDTO> path(LocalDateTime now, IntToDoubleFunction g) {
        List<PredictionPointDTO> out = new ArrayList<>();
        for (int m = 5; m <= 240; m += 5) {
            out.add(PredictionPointDTO.builder().timestamp(now.plusMinutes(m)).predictedGlucose(g.applyAsDouble(m)).build());
        }
        return out;
    }

    /** A forecast that falls to 3.0 at +40 and stays there: the advice fires. */
    private static final Forecaster FALLING = (now, g0, trend, v) -> path(now, m -> m >= 40 ? 3.0 : g0 - (g0 - 3.0) * m / 40);
    private static final Forecaster FLAT = (now, g0, trend, v) -> path(now, m -> g0);

    private static double minutesFrom(LocalDateTime t) {
        return java.time.Duration.between(T, t).toMinutes();
    }

    @Test
    void countsFalseAdviceWhenCgmStaysUp() {
        Data d = data(cgm(t -> 7.0), List.of(note(T, 40, 0, "Lunch")));
        Report r = MealReplayHarness.run(d, FALLING, AS_LOGGED);
        assertThat(r.adviceAnchors()).isPositive();
        assertThat(r.falseAdvice()).isEqualTo(r.adviceAnchors());
    }

    @Test
    void noFalseAdviceWhenCgmReallyFalls() {
        Data d = data(cgm(t -> {
            double m = minutesFrom(t);
            return m <= 0 ? 7.0 : m >= 50 ? 3.5 : 7.0 - 3.5 * m / 50;
        }), List.of(note(T, 40, 0, "Lunch")));
        Report r = MealReplayHarness.run(d, FALLING, AS_LOGGED);
        assertThat(r.adviceAnchors()).isPositive();
        assertThat(r.falseAdvice()).isZero();
    }

    @Test
    void lowLeadTimeIsMeasuredOnTheFiveMinuteGrid() {
        LocalDateTime lowAt = T.plusHours(5);
        Data d = data(cgm(t -> t.isBefore(lowAt) ? 7.0 : 3.5), List.of(note(T, 40, 0, "Lunch")));
        Forecaster firesFrom25Before = (now, g0, trend, v) ->
                now.isBefore(lowAt.minusMinutes(25)) ? FLAT.forecast(now, g0, trend, v) : FALLING.forecast(now, g0, trend, v);
        List<LowOutcome> lows = MealReplayHarness.run(d, firesFrom25Before, AS_LOGGED).lows();
        assertThat(lows).hasSize(1);
        assertThat(lows.get(0).lowAt()).isEqualTo(lowAt);
        assertThat(lows.get(0).leadMin()).isEqualTo(25);
        assertThat(lows.get(0).firstFireAt()).isEqualTo(lowAt.minusMinutes(25));
    }

    @Test
    void missedLowHasNullLead() {
        LocalDateTime lowAt = T.plusHours(5);
        Data d = data(cgm(t -> t.isBefore(lowAt) ? 7.0 : 3.5), List.of(note(T, 40, 0, "Lunch")));
        List<LowOutcome> lows = MealReplayHarness.run(d, FLAT, AS_LOGGED).lows();
        assertThat(lows).hasSize(1);
        assertThat(lows.get(0).firstFireAt()).isNull();
        assertThat(lows.get(0).leadMin()).isNull();
    }

    @Test
    void fullKnowledgeSeesNotesUpTo120MinAhead() {
        Note first = note(T, 30, 0, "Lunch");
        Note second = note(T.plusMinutes(30), 20, 0, "Dessert");
        Note later = note(T.plusMinutes(150), 20, 0, "Snack");
        Data d = data(cgm(t -> 7.0), List.of(first, second, later));
        List<List<Note>> seen = new ArrayList<>();
        Forecaster capture = (now, g0, trend, v) -> {
            if (now.equals(T)) seen.add(v);
            return FLAT.forecast(now, g0, trend, v);
        };
        MealReplayHarness.run(d, capture, new Options(Mode.FULL_KNOWLEDGE,
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1), MealReplayHarness.MealContextFn.NONE));
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0)).contains(first, second).doesNotContain(later);

        seen.clear();
        MealReplayHarness.run(d, capture, AS_LOGGED);
        assertThat(seen.get(0)).containsExactly(first);
    }

    @Test
    void bucketsFollowTheSpecOrder() {
        Note meal = note(T, 30, 3, "Lunch");
        Note preBolus = note(T.plusHours(6), 0, 4, "Pre-bolus");
        Note correction = note(T.plusHours(24), 0, 2, "Correction");
        Note rescue = note(T.plusHours(48), 15, 0, "Hypo treatment");
        rescue.setType(Note.TYPE_HYPO_TREATMENT);
        Data d = data(cgm(t -> 7.0), List.of(meal, preBolus, correction, rescue));

        assertThat(MealReplayHarness.bucket(d, T)).isEqualTo(Bucket.MEAL);
        assertThat(MealReplayHarness.bucket(d, T.plusHours(6))).isEqualTo(Bucket.PRE_BOLUS);
        assertThat(MealReplayHarness.bucket(d, T.plusHours(24))).isEqualTo(Bucket.CORRECTION);
        assertThat(MealReplayHarness.bucket(d, T.plusHours(48))).isEqualTo(Bucket.FASTING);

        Report r = MealReplayHarness.run(d, FLAT, AS_LOGGED);
        for (Bucket b : Bucket.values()) assertThat(r.metrics(b, 30).n()).as(b.name()).isPositive();
        assertThat(r.rescueMetrics(30).n()).isEqualTo(1);
        assertThat(r.metrics(Bucket.MEAL, 30).bias()).isZero();
    }

    @Test
    void notesAreReadInTheUsersWallTime(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("settings.properties"), "timezone=Europe/Moscow\nisf=2.0\ncurve.default=5/120\n");
        Files.writeString(dir.resolve("cgm.csv"), "date_timestamp,sgv\n1795600000000,126\n");
        Files.writeString(dir.resolve("notes.csv"), "timestamp,carbs,insulin,type,meal,insulin_code,nutrition_profile\n"
                + "2026-11-19T19:44:26+03:00,30,0,normal,Dinner,,\"{\"\"fat\"\": 1.0}\"\n");
        Data d = MealReplayHarness.load(dir);
        assertThat(d.notes()).hasSize(1);
        assertThat(d.notes().get(0).getTimestamp()).isEqualTo(LocalDateTime.of(2026, 11, 19, 19, 44, 26));
        assertThat(d.notes().get(0).getNutritionProfile()).isEqualTo("{\"fat\": 1.0}");
        assertThat(d.curves().get("default").peakMinutes()).isEqualTo(120.0);
        assertThat(d.cgm().get(0).mmol()).isCloseTo(7.0, org.assertj.core.api.Assertions.within(0.01));
    }
}
