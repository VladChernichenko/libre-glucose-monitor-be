package che.glucosemonitorbe.assistant.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import che.glucosemonitorbe.assistant.episode.EpisodeSearchService;
import che.glucosemonitorbe.assistant.episode.MealEpisode;
import che.glucosemonitorbe.assistant.episode.MealEpisodeRepository;
import che.glucosemonitorbe.assistant.episode.ScoredEpisode;
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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EpisodeRetrievalTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 30, 12, 0);
    private static final ZoneId ZONE = ZoneId.of("UTC");

    private final EpisodeSearchService search = mock(EpisodeSearchService.class);
    private final MealEpisodeRepository episodes = mock(MealEpisodeRepository.class);
    private final ForecastSnapshotRepository snapshots = mock(ForecastSnapshotRepository.class);
    private final NoteRepository notes = mock(NoteRepository.class);
    private final HypoEventRepository hypos = mock(HypoEventRepository.class);
    private final CgmReadingRepository cgm = mock(CgmReadingRepository.class);
    private final UUID user = UUID.randomUUID();
    private EpisodeRetrieval retrieval;

    @BeforeEach
    void setUp() {
        retrieval = new EpisodeRetrieval(search, episodes, snapshots, notes, hypos, cgm);
    }

    private static FactsPayload.State state() {
        return FactsPayload.State.builder().glucose(7.0).iob(1.0).cob(0.0).build();
    }

    private MealEpisode meal(String food, LocalDateTime at) {
        MealEpisode e = new MealEpisode();
        e.setId(UUID.randomUUID());
        e.setUserId(user);
        e.setFoodText(food);
        e.setMealAt(at);
        e.setCarbs(40);
        e.setStartGlucose(6.5);
        e.setPeak(11.2);
        e.setPeakMinute(75);
        return e;
    }

    private Note note(LocalDateTime at, double carbs, double insulin, String type) {
        Note n = new Note(user, at, carbs, insulin, "none");
        n.setId(UUID.randomUUID());
        n.setType(type);
        return n;
    }

    @Test
    void planUsesTopFiveSimilarMeals() {
        List<ScoredEpisode> seven = new ArrayList<>();
        for (int i = 0; i < 5; i++) seven.add(new ScoredEpisode(meal("soup " + i, NOW.minusDays(i + 1)), 0.9 - i * 0.1));
        when(search.similar(eq(user), any(), eq(5))).thenReturn(seven);

        List<FactsPayload.EpisodeFacts> out = retrieval.forPlan(user, "soup",
                new che.glucosemonitorbe.assistant.mealplan.MealMacros(40, 10, 10, 2, null), NOW, 7.0, 1.0);

        assertEquals(5, out.size());
        assertEquals("MEAL", out.get(0).kind());
        assertEquals("soup 0", out.get(0).text());
        assertEquals(0.9, out.get(0).score(), 1e-9);
        assertEquals(11.2, out.get(0).peak());
        assertEquals("2026-10-29T12:00", out.get(0).at());
    }

    @Test
    void questionMealsAreFilteredByDateAndFoodWordsAndCapped() {
        List<ScoredEpisode> pool = new ArrayList<>();
        for (int i = 0; i < 8; i++) pool.add(new ScoredEpisode(meal("pork chop " + i, NOW.minusDays(i + 1)), 0.9));
        pool.add(new ScoredEpisode(meal("rice", NOW.minusDays(2)), 0.95));
        pool.add(new ScoredEpisode(meal("pork stew", NOW.minusDays(60)), 0.95)); // outside the range
        when(search.similar(eq(user), any(), anyInt())).thenReturn(pool);
        IntentClassifier.Filters f = new IntentClassifier.Filters(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 29),
                List.of("pork"), IntentClassifier.EventKind.MEAL);

        List<FactsPayload.EpisodeFacts> out = retrieval.forQuestion(user, "why high after pork?", f, state(), NOW, ZONE);

        assertEquals(EpisodeRetrieval.PER_KIND, out.size());
        assertTrue(out.stream().allMatch(e -> e.text().startsWith("pork chop")), out.toString());
        verifyNoInteractions(hypos);
        verify(search).similar(eq(user), argThat(s -> s.foodText().equals("pork")), anyInt());
    }

    @Test
    void correctionsComeFromSnapshotAnchors() {
        Note correction = note(NOW.minusDays(1), 0, 2.0, Note.TYPE_NORMAL);
        correction.setComment("correction after pizza");
        Note meal = note(NOW.minusDays(1), 40, 4.0, Note.TYPE_NORMAL);
        Note basal = note(NOW.minusDays(1), 0, 14.0, Note.TYPE_LONG_ACTING);
        when(notes.findByUserIdAndTimestampBetween(eq(user), any(), any())).thenReturn(List.of(correction, meal, basal));
        ForecastSnapshot s = new ForecastSnapshot();
        s.setUserId(user);
        s.setNoteId(correction.getId());
        s.setStartGlucose(12.4);
        s.setCheck60Actual(10.1);
        s.setCheck120Actual(8.3);
        ForecastSnapshot foreign = new ForecastSnapshot();
        foreign.setUserId(UUID.randomUUID());
        foreign.setNoteId(meal.getId());
        when(snapshots.findByNoteIdInAndExcludedFalse(anyCollection())).thenReturn(List.of(s, foreign));
        IntentClassifier.Filters f = new IntentClassifier.Filters(null, null, List.of(), IntentClassifier.EventKind.CORRECTION);

        List<FactsPayload.EpisodeFacts> out = retrieval.forQuestion(user, "corrections?", f, state(), NOW, ZONE);

        assertEquals(1, out.size());
        FactsPayload.EpisodeFacts c = out.get(0);
        assertEquals("CORRECTION", c.kind());
        assertEquals(correction.getId().toString(), c.id());
        assertEquals(2.0, c.doseUnits());
        assertEquals(12.4, c.startGlucose());
        assertEquals(10.1, c.g60());
        assertEquals(8.3, c.g120());
        assertEquals("correction after pizza", c.text());
        verifyNoInteractions(search);
    }

    @Test
    void activityComesFromActivityNotesWithCgmOutcome() {
        Note walk = note(NOW.minusHours(5), 0, 0, Note.TYPE_ACTIVITY);
        walk.setActivityType("WALK");
        walk.setDurationMin(40);
        when(notes.findByUserIdAndTimestampBetween(eq(user), any(), any())).thenReturn(List.of(walk));
        long t0 = walk.getTimestamp().atZone(ZONE).toInstant().toEpochMilli();
        when(cgm.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(eq(user), anyLong(), anyLong()))
                .thenReturn(List.of(reading(t0, 144), reading(t0 + 60 * 60_000L, 99), reading(t0 + 120 * 60_000L, 108)));
        IntentClassifier.Filters f = new IntentClassifier.Filters(null, null, List.of(), IntentClassifier.EventKind.ACTIVITY);

        List<FactsPayload.EpisodeFacts> out = retrieval.forQuestion(user, "walks?", f, state(), NOW, ZONE);

        assertEquals(1, out.size());
        FactsPayload.EpisodeFacts a = out.get(0);
        assertEquals("ACTIVITY", a.kind());
        assertEquals(40, a.activityMinutes());
        assertEquals(8.0, a.startGlucose(), 0.05);
        assertEquals(5.5, a.nadir(), 0.05);
        assertEquals(60, a.nadirMinute());
        assertTrue(a.text().contains("WALK"));
    }

    @Test
    void lowsComeFromHypoEventsInRangeCappedAtFive() {
        List<HypoEvent> events = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            events.add(HypoEvent.builder().id(UUID.randomUUID()).userId(user).triggerGlucoseMmol(3.5)
                    .detectedAt(NOW.minusDays(i + 1)).build());
        }
        events.add(HypoEvent.builder().id(UUID.randomUUID()).userId(user).triggerGlucoseMmol(3.1)
                .detectedAt(NOW.minusDays(200)).build());
        when(hypos.findByUserIdOrderByDetectedAtDesc(user)).thenReturn(events);
        IntentClassifier.Filters f = new IntentClassifier.Filters(null, null, List.of(), IntentClassifier.EventKind.LOW);

        List<FactsPayload.EpisodeFacts> out = retrieval.forQuestion(user, "lows?", f, state(), NOW, ZONE);

        assertEquals(EpisodeRetrieval.PER_KIND, out.size());
        assertTrue(out.stream().allMatch(e -> "LOW".equals(e.kind()) && e.startGlucose() == 3.5));
    }

    @Test
    void datesWithoutKindMeanAllKinds() {
        when(search.similar(eq(user), any(), anyInt())).thenReturn(List.of(new ScoredEpisode(meal("soup", NOW.minusDays(1)), 0.8)));
        when(hypos.findByUserIdOrderByDetectedAtDesc(user)).thenReturn(List.of(HypoEvent.builder()
                .id(UUID.randomUUID()).userId(user).triggerGlucoseMmol(3.4).detectedAt(NOW.minusDays(1)).build()));
        IntentClassifier.Filters yesterday = new IntentClassifier.Filters(
                NOW.toLocalDate().minusDays(1), NOW.toLocalDate().minusDays(1), List.of(), null);

        List<FactsPayload.EpisodeFacts> out = retrieval.forQuestion(user, "what happened yesterday?",
                yesterday, state(), NOW, ZONE);

        assertEquals(List.of("MEAL", "LOW"), out.stream().map(FactsPayload.EpisodeFacts::kind).toList());
    }

    @Test
    void aMessageNotAboutThePastGetsNoEpisodes() {
        List<FactsPayload.EpisodeFacts> out = retrieval.forQuestion(user, "2u (testing feature)",
                IntentClassifier.Filters.NONE, state(), NOW, ZONE);

        assertTrue(out.isEmpty());
        verifyNoInteractions(search, episodes, notes, hypos, snapshots, cgm);
    }

    @Test
    void unmatchedFoodWordsShowOnlyCloseSemanticMatches() {
        when(search.similar(eq(user), any(), anyInt())).thenReturn(List.of(
                new ScoredEpisode(meal("борщ", NOW.minusDays(1)), 0.82),
                new ScoredEpisode(meal("dried mango", NOW.minusDays(2)), 0.40)));
        IntentClassifier.Filters f = new IntentClassifier.Filters(null, null, List.of("beet soup"), null);

        List<FactsPayload.EpisodeFacts> out = retrieval.forQuestion(user, "beet soup last time?", f, state(), NOW, ZONE);

        assertEquals(List.of("борщ"), out.stream().filter(e -> "MEAL".equals(e.kind()))
                .map(FactsPayload.EpisodeFacts::text).toList());
    }

    private CgmReading reading(long ts, int sgv) {
        CgmReading r = new CgmReading();
        r.setUserId(user);
        r.setDateTimestamp(ts);
        r.setSgv(sgv);
        return r;
    }
}
