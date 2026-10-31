package che.glucosemonitorbe.assistant.chat;

import che.glucosemonitorbe.assistant.episode.EpisodeSearchService;
import che.glucosemonitorbe.assistant.episode.MealEpisode;
import che.glucosemonitorbe.assistant.episode.MealEpisodeRepository;
import che.glucosemonitorbe.assistant.episode.ScoredEpisode;
import che.glucosemonitorbe.assistant.episode.Situation;
import che.glucosemonitorbe.assistant.mealplan.MealMacros;
import che.glucosemonitorbe.assistant.snapshot.ForecastSnapshot;
import che.glucosemonitorbe.assistant.snapshot.ForecastSnapshotRepository;
import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.entity.HypoEvent;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.repository.HypoEventRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Past episodes for the facts payload and the {@code episodes} event (spec §6.7). Meal plans get the top
 * similar meals. Questions get up to {@link #PER_KIND} per kind, all scoped to the user and to the
 * classifier's validated {@link IntentClassifier.Filters}: meals from {@link EpisodeSearchService},
 * corrections from {@code forecast_snapshot} anchors (rapid dose, carbs under 10 g) with their notes,
 * activity from activity notes with the CGM trace after them, lows from {@code hypo_events}.
 * Without dates the window is the last {@link IntentClassifier#MAX_RANGE_DAYS} days.
 */
@Component
@RequiredArgsConstructor
public class EpisodeRetrieval {

    public static final int PER_KIND = 5;
    static final int MEAL_POOL = 50;
    static final double CORRECTION_MAX_CARBS = 10.0;
    static final int ACTIVITY_WINDOW_MIN = 120;
    static final int LOW_WINDOW_MIN = 60;
    /** Neutral start glucose for the situation term when no fresh reading exists [mmol/L]. */
    static final double NEUTRAL_GLUCOSE = 7.0;
    private static final double MGDL_PER_MMOL = 18.0;

    private final EpisodeSearchService search;
    private final MealEpisodeRepository mealEpisodes;
    private final ForecastSnapshotRepository snapshots;
    private final NoteRepository notes;
    private final HypoEventRepository hypos;
    private final CgmReadingRepository cgm;

    public List<FactsPayload.EpisodeFacts> forPlan(UUID userId, String description, MealMacros macros,
                                                   LocalDateTime now, Double glucose, double iob) {
        Situation s = new Situation(description, macros, minuteOfDay(now),
                glucose == null ? NEUTRAL_GLUCOSE : glucose, iob, 0);
        return search.similar(userId, s, PER_KIND).stream().map(EpisodeRetrieval::meal).toList();
    }

    public List<FactsPayload.EpisodeFacts> forQuestion(UUID userId, String question, IntentClassifier.Filters f,
                                                       FactsPayload.State state, LocalDateTime now, ZoneId zone) {
        IntentClassifier.Filters filters = f == null ? IntentClassifier.Filters.NONE : f;
        LocalDate toDate = filters.toDate() != null ? filters.toDate() : now.toLocalDate();
        LocalDate fromDate = filters.fromDate() != null ? filters.fromDate()
                : toDate.minusDays(IntentClassifier.MAX_RANGE_DAYS);
        LocalDateTime from = fromDate.atStartOfDay();
        LocalDateTime to = toDate.plusDays(1).atStartOfDay();
        List<String> words = filters.foodWords() == null ? List.of() : filters.foodWords();
        IntentClassifier.EventKind kind = filters.eventKind();

        List<FactsPayload.EpisodeFacts> out = new ArrayList<>();
        if (kind == null || kind == IntentClassifier.EventKind.MEAL) {
            out.addAll(meals(userId, question, words, state, now, from, to));
        }
        List<Note> rangeNotes = null;
        if (kind == null || kind == IntentClassifier.EventKind.CORRECTION) {
            rangeNotes = notesIn(userId, from, to);
            out.addAll(corrections(userId, rangeNotes, words));
        }
        if (kind == null || kind == IntentClassifier.EventKind.ACTIVITY) {
            if (rangeNotes == null) rangeNotes = notesIn(userId, from, to);
            out.addAll(activities(userId, rangeNotes, words, zone));
        }
        if (kind == null || kind == IntentClassifier.EventKind.LOW) {
            out.addAll(lows(userId, from, to, zone));
        }
        return out;
    }

    private List<FactsPayload.EpisodeFacts> meals(UUID userId, String question, List<String> words,
                                                  FactsPayload.State state, LocalDateTime now,
                                                  LocalDateTime from, LocalDateTime to) {
        String text = words.isEmpty() ? question : String.join(" ", words);
        Situation s = new Situation(text, new MealMacros(0, 0, 0, 0, null), minuteOfDay(now),
                state != null && state.glucose() != null ? state.glucose() : NEUTRAL_GLUCOSE,
                state != null && state.iob() != null ? state.iob() : 0.0, 0);
        Map<UUID, ScoredEpisode> candidates = new LinkedHashMap<>();
        search.similar(userId, s, MEAL_POOL).forEach(se -> candidates.put(se.episode().getId(), se));
        // Episodes in an explicit range may sit outside the similarity pool: add them, ranked after it.
        mealEpisodes.findByUserIdAndMealAtGreaterThanEqual(userId, from)
                .forEach(e -> candidates.putIfAbsent(e.getId(), new ScoredEpisode(e, 0.0)));
        List<ScoredEpisode> inRange = candidates.values().stream()
                .filter(se -> userId.equals(se.episode().getUserId()))
                .filter(se -> inRange(se.episode().getMealAt(), from, to))
                .toList();
        // Food words filter the pool; when none matches (e.g. a translated food name), the semantic
        // ranking of those same words decides instead.
        List<ScoredEpisode> matching = inRange.stream()
                .filter(se -> mentions(se.episode().getFoodText(), words)).toList();
        List<ScoredEpisode> chosen = matching.isEmpty() ? inRange : matching;
        return chosen.stream().limit(PER_KIND).map(EpisodeRetrieval::meal).toList();
    }

    private List<Note> notesIn(UUID userId, LocalDateTime from, LocalDateTime to) {
        List<Note> found = notes.findByUserIdAndTimestampBetween(userId, from, to);
        return found == null ? List.of() : found.stream()
                .filter(n -> userId.equals(n.getUserId()) && n.getTimestamp() != null).toList();
    }

    private List<FactsPayload.EpisodeFacts> corrections(UUID userId, List<Note> rangeNotes, List<String> words) {
        Map<UUID, Note> byId = rangeNotes.stream()
                .filter(EpisodeRetrieval::isCorrection)
                .filter(n -> mentions(noteText(n), words))
                .collect(Collectors.toMap(Note::getId, n -> n, (a, b) -> a, LinkedHashMap::new));
        if (byId.isEmpty()) return List.of();
        Map<UUID, ForecastSnapshot> anchors = new LinkedHashMap<>();
        for (ForecastSnapshot s : snapshots.findByNoteIdInAndExcludedFalse(byId.keySet())) {
            if (userId.equals(s.getUserId())) anchors.put(s.getNoteId(), s);
        }
        return byId.values().stream()
                .filter(n -> anchors.containsKey(n.getId()))
                .sorted((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()))
                .limit(PER_KIND)
                .map(n -> {
                    ForecastSnapshot s = anchors.get(n.getId());
                    return FactsPayload.EpisodeFacts.builder()
                            .id(n.getId().toString()).kind(IntentClassifier.EventKind.CORRECTION.name())
                            .at(FactsService.wall(n.getTimestamp())).text(blankToNull(noteText(n)))
                            .doseUnits(n.getInsulin()).carbs(positive(n.getCarbs()))
                            .startGlucose(FactsService.round1(s.getStartGlucose()))
                            .g60(FactsService.round1(s.getCheck60Actual()))
                            .g120(FactsService.round1(s.getCheck120Actual()))
                            .g240(FactsService.round1(s.getCheck240Actual()))
                            .build();
                }).toList();
    }

    private List<FactsPayload.EpisodeFacts> activities(UUID userId, List<Note> rangeNotes, List<String> words,
                                                       ZoneId zone) {
        return rangeNotes.stream()
                .filter(Note::isActivity)
                .filter(n -> mentions(noteText(n), words))
                .sorted((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()))
                .limit(PER_KIND)
                .map(n -> {
                    Trace t = trace(userId, n.getTimestamp(), zone, ACTIVITY_WINDOW_MIN);
                    return FactsPayload.EpisodeFacts.builder()
                            .id(n.getId().toString()).kind(IntentClassifier.EventKind.ACTIVITY.name())
                            .at(FactsService.wall(n.getTimestamp())).text(blankToNull(noteText(n)))
                            .activityMinutes(n.getDurationMin())
                            .startGlucose(t.start).g60(t.g60).g120(t.g120)
                            .nadir(t.nadir).nadirMinute(t.nadirMinute)
                            .build();
                }).toList();
    }

    private List<FactsPayload.EpisodeFacts> lows(UUID userId, LocalDateTime from, LocalDateTime to, ZoneId zone) {
        List<HypoEvent> events = hypos.findByUserIdOrderByDetectedAtDesc(userId);
        if (events == null) return List.of();
        return events.stream()
                .filter(e -> userId.equals(e.getUserId()) && e.getDetectedAt() != null)
                .map(e -> Map.entry(e, toUserWall(e.getDetectedAt(), zone)))
                .filter(en -> inRange(en.getValue(), from, to))
                .limit(PER_KIND)
                .map(en -> {
                    HypoEvent e = en.getKey();
                    Trace t = trace(userId, en.getValue(), zone, LOW_WINDOW_MIN);
                    Double rescue = e.getNoteId() == null ? null : notes.findByIdAndUserId(e.getNoteId(), userId)
                            .map(Note::getCarbs).filter(c -> c > 0).orElse(null);
                    return FactsPayload.EpisodeFacts.builder()
                            .id(e.getId().toString()).kind(IntentClassifier.EventKind.LOW.name())
                            .at(FactsService.wall(en.getValue()))
                            .text("hypo " + e.getState().name().toLowerCase(Locale.ROOT))
                            .startGlucose(FactsService.round1(e.getTriggerGlucoseMmol())).carbs(rescue)
                            .nadir(t.nadir).nadirMinute(t.nadirMinute)
                            .build();
                }).toList();
    }

    /** {@code hypo_events.detected_at} is a JVM-zone timestamp ({@code @CreationTimestamp}); move it to the user's wall clock. */
    private static LocalDateTime toUserWall(LocalDateTime jvmLocal, ZoneId zone) {
        return jvmLocal.atZone(ZoneId.systemDefault()).withZoneSameInstant(zone).toLocalDateTime();
    }

    private record Trace(Double start, Double g60, Double g120, Double nadir, Integer nadirMinute) {}

    private Trace trace(UUID userId, LocalDateTime startWall, ZoneId zone, int minutes) {
        long t0 = startWall.atZone(zone).toInstant().toEpochMilli();
        List<CgmReading> readings = cgm.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(
                userId, t0 - 10 * 60_000L, t0 + minutes * 60_000L);
        if (readings == null || readings.isEmpty()) return new Trace(null, null, null, null, null);
        List<CgmReading> valid = readings.stream()
                .filter(r -> r.getSgv() != null && r.getDateTimestamp() != null).toList();
        Double start = nearest(valid, t0);
        Double g60 = minutes >= 60 ? nearest(valid, t0 + 60 * 60_000L) : null;
        Double g120 = minutes >= 120 ? nearest(valid, t0 + 120 * 60_000L) : null;
        CgmReading low = valid.stream().filter(r -> r.getDateTimestamp() >= t0)
                .min((a, b) -> Integer.compare(a.getSgv(), b.getSgv())).orElse(null);
        return new Trace(start, g60, g120,
                low == null ? null : FactsService.round1(low.getSgv() / MGDL_PER_MMOL),
                low == null ? null : (int) ((low.getDateTimestamp() - t0) / 60_000L));
    }

    /** The reading within 10 min of {@code ts}, in mmol/L, or null. */
    private static Double nearest(List<CgmReading> readings, long ts) {
        return readings.stream()
                .filter(r -> Math.abs(r.getDateTimestamp() - ts) <= 10 * 60_000L)
                .min((a, b) -> Long.compare(Math.abs(a.getDateTimestamp() - ts), Math.abs(b.getDateTimestamp() - ts)))
                .map(r -> FactsService.round1(r.getSgv() / MGDL_PER_MMOL))
                .orElse(null);
    }

    static FactsPayload.EpisodeFacts meal(ScoredEpisode se) {
        MealEpisode e = se.episode();
        return FactsPayload.EpisodeFacts.builder()
                .id(e.getId().toString()).kind(IntentClassifier.EventKind.MEAL.name())
                .at(FactsService.wall(e.getMealAt())).text(e.getFoodText())
                .carbs(FactsService.round1(e.getCarbs())).fat(FactsService.round1(e.getFat()))
                .protein(FactsService.round1(e.getProtein()))
                .doseUnits(e.getDoseUnits()).pauseMinutes(e.getPauseMinutes())
                .startGlucose(FactsService.round1(e.getStartGlucose()))
                .peak(FactsService.round1(e.getPeak())).peakMinute(e.getPeakMinute())
                .nadir(FactsService.round1(e.getNadir())).nadirMinute(e.getNadirMinute())
                .g60(FactsService.round1(e.getG60())).g120(FactsService.round1(e.getG120()))
                .g240(FactsService.round1(e.getG240()))
                .minutesBelow39(e.getMinutesBelow39()).minutesAbove10(e.getMinutesAbove10())
                .activityMinutes(e.getActivityMinutesAround())
                .contaminated(e.isContaminated() ? Boolean.TRUE : null)
                .score(se.score() > 0 ? FactsService.round2(se.score()) : null)
                .build();
    }

    private static boolean isCorrection(Note n) {
        double insulin = n.getInsulin() == null ? 0 : n.getInsulin();
        double carbs = n.getCarbs() == null ? 0 : n.getCarbs();
        return insulin > 0 && !n.isLongActing() && !n.isHypoTreatment() && !n.isActivity()
                && carbs < CORRECTION_MAX_CARBS;
    }

    private static String noteText(Note n) {
        return Stream.of(n.getActivityType(), n.getIntensity(),
                        n.getMeal() == null || "none".equalsIgnoreCase(n.getMeal()) ? null : n.getMeal(),
                        n.getComment(), n.getDetailedInput())
                .filter(Objects::nonNull).map(String::strip).filter(Predicate.not(String::isBlank))
                .collect(Collectors.joining(", "));
    }

    private static boolean mentions(String text, List<String> words) {
        if (words.isEmpty()) return true;
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return words.stream().anyMatch(lower::contains);
    }

    private static boolean inRange(LocalDateTime t, LocalDateTime from, LocalDateTime to) {
        return t != null && !t.isBefore(from) && t.isBefore(to);
    }

    private static int minuteOfDay(LocalDateTime t) {
        return t.getHour() * 60 + t.getMinute();
    }

    private static Double positive(Double v) {
        return v == null || v <= 0 ? null : v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
