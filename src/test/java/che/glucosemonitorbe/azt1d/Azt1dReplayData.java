package che.glucosemonitorbe.azt1d;

import che.glucosemonitorbe.backtest.BacktestHarness;
import che.glucosemonitorbe.backtest.MealReplayHarness;
import che.glucosemonitorbe.hovorka.BasalInsulinResolver;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One AZT1D subject as meal-replay input ({@code MealReplayHarness.Data}).
 *
 * <ul>
 *   <li>Carbs become normal notes ("Meal").</li>
 *   <li>Manual boluses become rapid notes labelled "Bolus" when carbs are logged within 15 min,
 *       else "Correction"; automatic (Control-IQ) boluses are labelled "Correction".</li>
 *   <li>Settings come from the subject's estimated profile: ISF and window ISFs, weight, and the
 *       carb ratio as every window's grams per unit. The record's times are local and parsed as UTC,
 *       so the zone is UTC.</li>
 *   <li>Insulin curve: 4.5 h / 55 min (as {@code ScenarioForecaster}).</li>
 * </ul>
 * Pump basal is not converted: with no long-acting notes the model holds EGP at steady state.
 */
public final class Azt1dReplayData {

    static final RapidInsulinIobParameters RAPID_IOB = new RapidInsulinIobParameters(4.5, 55.0);
    private static final long MEAL_BOLUS_WINDOW_MS = 15 * 60_000L;

    private Azt1dReplayData() {}

    public static MealReplayHarness.Data of(Azt1dDataset.Subject s, List<Azt1dDataset.BolusRecord> log) {
        UUID userId = UUID.nameUUIDFromBytes(s.id().getBytes());
        List<BacktestHarness.CgmPoint> cgm = s.cgm().stream()
                .map(r -> new BacktestHarness.CgmPoint(r.epochMs(), r.mmol())).toList();

        List<Note> notes = new ArrayList<>();
        for (Azt1dDataset.BolusRecord r : log) {
            LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochMilli(r.epochMs()), ZoneOffset.UTC);
            if (r.carbsG() > 0) notes.add(note(userId, t, r.carbsG(), 0.0, "Meal"));
            if (r.totalU() > 0) {
                boolean mealBolus = !Azt1dDataset.isAutomatic(r) && log.stream().anyMatch(o ->
                        o.carbsG() > 0 && Math.abs(o.epochMs() - r.epochMs()) <= MEAL_BOLUS_WINDOW_MS);
                notes.add(note(userId, t, 0.0, r.totalU(), mealBolus ? "Bolus" : "Correction"));
            }
        }
        notes.sort((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()));

        Azt1dDataset.Profile p = s.profile();
        UserSettingsDTO settings = new UserSettingsDTO();
        settings.setUserId(userId);
        settings.setIsf(p.isfMmolPerU());
        settings.setIsfBreakfast(p.isfBreakfast());
        settings.setIsfLunch(p.isfLunch());
        settings.setIsfDinner(p.isfDinner());
        settings.setGramsPerUnitBreakfast(p.carbRatioGPerU());
        settings.setGramsPerUnitLunch(p.carbRatioGPerU());
        settings.setGramsPerUnitDinner(p.carbRatioGPerU());
        settings.setGramsPerUnitNight(p.carbRatioGPerU());
        settings.setBodyWeightKg(p.weightKg());
        settings.setCarbHalfLife(45);
        settings.setMaxCOBDuration(240);
        settings.setTimezone("UTC");

        return new MealReplayHarness.Data(cgm, notes, settings, Map.of("default", RAPID_IOB),
                BasalInsulinResolver.DEFAULT_DURATION_HOURS, ZoneOffset.UTC);
    }

    private static Note note(UUID userId, LocalDateTime t, double carbs, double insulin, String meal) {
        Note n = new Note(userId, t, carbs, insulin, meal);
        n.setId(UUID.randomUUID());
        return n;
    }
}
