package che.glucosemonitorbe.assistant.episode;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.domain.GlucoseConversion;
import che.glucosemonitorbe.domain.UserZones;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.service.nutrition.NutritionSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Derives a {@link MealEpisode} from a meal note, its neighbouring notes and the CGM (spec 6.7).
 * Pure: no I/O. Note timestamps are user wall time; CGM epoch-ms are converted with the user's zone.
 *
 * <p>Own dose = the meal note's insulin plus the latest rapid-only note in the 30 min before the meal
 * (a pre-bolus logged separately). Any other rapid dose or carbs in that 30 min or in the 4 h after
 * marks the episode contaminated. IOB and COB are linear-decay approximations (4 h and 3 h) of the
 * notes before the meal, good enough to describe the situation for similarity.
 */
@Slf4j
@Component
public class MealEpisodeBuilder {

    public static final double MIN_CARBS = 10.0;
    static final Duration OUTCOME_WINDOW = Duration.ofHours(4);
    static final Duration OWN_DOSE_LOOKBACK = Duration.ofMinutes(30);
    static final Duration ACTIVITY_WINDOW = Duration.ofHours(2);
    private static final long READING_TOLERANCE_MS = Duration.ofMinutes(10).toMillis();
    private static final long MAX_GAP_MS = Duration.ofMinutes(15).toMillis();
    private static final double LOW_MMOL = 3.9;
    private static final double HIGH_MMOL = 10.0;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param load IOB/COB at the meal excluding the meal's own notes (computed by the caller from the
     *             dashboard's single source); null leaves both unset
     * @return empty when carbs are below 10 g, the note is not an ordinary meal, or no CGM reading
     *         lies within 10 min of the meal
     */
    public Optional<MealEpisode> build(Note meal, List<Note> surrounding, List<CgmReading> cgm,
                                       UserSettingsDTO s, MealLoad load) {
        if (meal.getCarbs() == null || meal.getCarbs() < MIN_CARBS || meal.getTimestamp() == null) {
            return Optional.empty();
        }
        if (meal.isLongActing() || meal.isActivity() || meal.isHypoTreatment()) return Optional.empty();

        ZoneId zone = UserZones.resolve(s == null ? null : s.getTimezone(), s == null ? null : s.getUtcOffsetMinutes());
        LocalDateTime at = meal.getTimestamp();
        long mealMs = UserZones.toEpochMs(at, zone);
        List<CgmReading> readings = cgm.stream()
                .filter(r -> r.getDateTimestamp() != null && r.getSgv() != null)
                .sorted(Comparator.comparingLong(CgmReading::getDateTimestamp)).toList();
        CgmReading start = nearest(readings, mealMs);
        if (start == null) return Optional.empty();

        MealEpisode e = new MealEpisode();
        e.setUserId(meal.getUserId());
        e.setNoteId(meal.getId());
        e.setMealAt(at);
        e.setLocalMinuteOfDay(at.getHour() * 60 + at.getMinute());
        e.setWeekday(at.getDayOfWeek().getValue());
        e.setCarbs(meal.getCarbs());
        e.setSiteZone(meal.getSiteZone());
        e.setContextTags(meal.getContextTags());
        e.setStartGlucose(mmol(start.getSgv()));
        e.setStartTrend(start.getTrend());
        applyNutrition(e, meal);

        List<Note> others = surrounding.stream().filter(n -> n != meal && !sameNote(n, meal)).toList();
        applyDoseAndContamination(e, meal, others, at);
        if (load != null) {
            e.setIob(load.iob());
            e.setCob(load.cob());
        }
        applyActivity(e, others, at);
        applyOutcome(e, readings, mealMs);
        return Optional.of(e);
    }

    /** Short text rendering used for the embedding, e.g. {@code "lunch, chicken soup, after a walk"}. */
    public static String foodText(MealEpisode e) {
        List<String> parts = new ArrayList<>();
        if (e.getFoodText() != null && !e.getFoodText().isBlank()) parts.add(e.getFoodText().trim());
        if (e.getActivityType() != null) {
            String noun = activityNoun(e.getActivityType());
            parts.add(Boolean.FALSE.equals(e.getActivityBefore()) ? "before a " + noun : "after a " + noun);
        }
        if (parts.isEmpty()) parts.add(fallbackText(e.getLocalMinuteOfDay(), e.getCarbs()));
        return String.join(", ", parts);
    }

    /** Never-empty rendering from time of day and carbs, e.g. {@code "lunch, 45 g carbs"}. */
    static String fallbackText(int minuteOfDay, double carbs) {
        int h = minuteOfDay / 60;
        String band = h >= 5 && h < 11 ? "breakfast" : h >= 11 && h < 16 ? "lunch" : h >= 16 && h < 22 ? "dinner" : "night";
        return band + ", " + Math.round(carbs) + " g carbs";
    }

    private static String activityNoun(String type) {
        return switch (type.toUpperCase()) {
            case "WALKING" -> "walk";
            case "RUNNING" -> "run";
            case "CYCLING" -> "bike ride";
            case "STRENGTH" -> "strength workout";
            default -> "workout";
        };
    }

    private static boolean sameNote(Note a, Note b) {
        return a.getId() != null && a.getId().equals(b.getId());
    }

    private void applyNutrition(MealEpisode e, Note meal) {
        List<String> food = new ArrayList<>();
        addIfText(food, meal.getMeal());
        addIfText(food, meal.getComment());
        addIfText(food, meal.getDetailedInput());
        String profile = meal.getNutritionProfile();
        if (profile != null && !profile.isBlank()) {
            try {
                NutritionSnapshot n = objectMapper.readValue(profile, NutritionSnapshot.class);
                e.setFat(n.getFat());
                e.setProtein(n.getProtein());
                e.setFiber(n.getFiber());
                e.setGi(n.getEstimatedGi());
                boolean hasText = meal.getDetailedInput() != null && !meal.getDetailedInput().isBlank();
                if (!hasText && n.getFoodMassBreakdown() != null) {
                    n.getFoodMassBreakdown().forEach(f -> addIfText(food, f.getMassG() > 0
                            ? f.getLabel() + " " + Math.round(f.getMassG()) + " g" : f.getLabel()));
                }
            } catch (Exception ex) {
                log.debug("Unreadable nutrition profile on note {}: {}", meal.getId(), ex.getMessage());
            }
        }
        String text = food.stream().distinct().collect(Collectors.joining(", "));
        e.setFoodText(text.isBlank() ? fallbackText(e.getLocalMinuteOfDay(), e.getCarbs()) : text);
    }

    private static void addIfText(List<String> out, String t) {
        if (t != null && !t.isBlank()) out.add(t.trim());
    }

    private static boolean isRapidDose(Note n) {
        return n.getInsulin() != null && n.getInsulin() > 0 && !n.isLongActing();
    }

    private static boolean hasCarbs(Note n) {
        return n.getCarbs() != null && n.getCarbs() > 0;
    }

    /** The meal's separately logged pre-bolus: latest rapid-only note in the 30 min up to the meal. */
    static Optional<Note> ownPreDose(List<Note> others, LocalDateTime at) {
        LocalDateTime windowStart = at.minus(OWN_DOSE_LOOKBACK);
        return others.stream()
                .filter(n -> n.getTimestamp() != null && isRapidDose(n) && !hasCarbs(n) && !n.isActivity()
                        && !n.getTimestamp().isBefore(windowStart) && !n.getTimestamp().isAfter(at))
                .max(Comparator.comparing(Note::getTimestamp));
    }

    private void applyDoseAndContamination(MealEpisode e, Note meal, List<Note> others, LocalDateTime at) {
        double dose = meal.getInsulin() == null || meal.isLongActing() ? 0 : meal.getInsulin();
        Integer pause = null;
        LocalDateTime windowStart = at.minus(OWN_DOSE_LOOKBACK);
        LocalDateTime windowEnd = at.plus(OUTCOME_WINDOW);

        Note pre = ownPreDose(others, at).orElse(null);
        if (pre != null) {
            dose += pre.getInsulin();
            pause = (int) Duration.between(pre.getTimestamp(), at).toMinutes();
        }

        boolean contaminated = false;
        for (Note n : others) {
            if (n == pre || n.isActivity() || n.getTimestamp() == null) continue;
            boolean inWindow = !n.getTimestamp().isBefore(windowStart) && !n.getTimestamp().isAfter(windowEnd);
            if (inWindow && (isRapidDose(n) || hasCarbs(n))) {
                contaminated = true;
                break;
            }
        }
        e.setDoseUnits(dose > 0 ? dose : null);
        e.setPauseMinutes(dose > 0 ? (pause != null ? pause : 0) : null);
        e.setContaminated(contaminated);
    }

    private void applyActivity(MealEpisode e, List<Note> others, LocalDateTime at) {
        Note best = null;
        int total = 0;
        for (Note n : others) {
            if (!n.isActivity() || n.getTimestamp() == null) continue;
            long delta = Duration.between(at, n.getTimestamp()).toMinutes();
            if (Math.abs(delta) > ACTIVITY_WINDOW.toMinutes()) continue;
            int minutes = n.getDurationMin() == null ? 0 : n.getDurationMin();
            total += minutes;
            if (best == null || minutes > (best.getDurationMin() == null ? 0 : best.getDurationMin())) best = n;
        }
        if (best == null) return;
        e.setActivityType(best.getActivityType() != null ? best.getActivityType() : "OTHER");
        e.setActivityMinutesAround(total);
        e.setActivityBefore(!best.getTimestamp().isAfter(at));
    }

    private void applyOutcome(MealEpisode e, List<CgmReading> readings, long mealMs) {
        long endMs = mealMs + OUTCOME_WINDOW.toMillis();
        List<CgmReading> window = readings.stream()
                .filter(r -> r.getDateTimestamp() >= mealMs && r.getDateTimestamp() <= endMs).toList();

        CgmReading peak = window.stream().max(Comparator.comparingInt(CgmReading::getSgv)).orElse(null);
        CgmReading nadir = window.stream().min(Comparator.comparingInt(CgmReading::getSgv)).orElse(null);
        if (peak != null) {
            e.setPeak(mmol(peak.getSgv()));
            e.setPeakMinute(minutesFrom(mealMs, peak));
            e.setNadir(mmol(nadir.getSgv()));
            e.setNadirMinute(minutesFrom(mealMs, nadir));
        }
        e.setG60(valueAt(readings, mealMs + Duration.ofMinutes(60).toMillis()));
        e.setG120(valueAt(readings, mealMs + Duration.ofMinutes(120).toMillis()));
        e.setG240(valueAt(readings, endMs));

        long below = 0;
        long above = 0;
        for (int i = 0; i < window.size() - 1; i++) {
            long gap = Math.min(window.get(i + 1).getDateTimestamp() - window.get(i).getDateTimestamp(), MAX_GAP_MS);
            double g = mmol(window.get(i).getSgv());
            if (g < LOW_MMOL) below += gap;
            if (g > HIGH_MMOL) above += gap;
        }
        e.setCgmMaxGapMin(maxGapMinutes(window, mealMs, endMs));
        e.setMinutesBelow39((int) Math.round(below / 60_000.0));
        e.setMinutesAbove10((int) Math.round(above / 60_000.0));
    }

    /** Largest gap in [meal, meal+4h], counting from the meal to the first and from the last to +4h. */
    private static int maxGapMinutes(List<CgmReading> window, long startMs, long endMs) {
        long prev = startMs;
        long max = 0;
        for (CgmReading r : window) {
            max = Math.max(max, r.getDateTimestamp() - prev);
            prev = r.getDateTimestamp();
        }
        max = Math.max(max, endMs - prev);
        return (int) Math.round(max / 60_000.0);
    }

    private static int minutesFrom(long mealMs, CgmReading r) {
        return (int) Math.round((r.getDateTimestamp() - mealMs) / 60_000.0);
    }

    private static Double valueAt(List<CgmReading> readings, long targetMs) {
        CgmReading r = nearest(readings, targetMs);
        return r == null ? null : mmol(r.getSgv());
    }

    private static CgmReading nearest(List<CgmReading> readings, long targetMs) {
        return readings.stream()
                .filter(r -> Math.abs(r.getDateTimestamp() - targetMs) <= READING_TOLERANCE_MS)
                .min(Comparator.comparingLong(r -> Math.abs(r.getDateTimestamp() - targetMs)))
                .orElse(null);
    }

    private static double mmol(int sgv) {
        return sgv / GlucoseConversion.MGDL_PER_MMOL;
    }
}
