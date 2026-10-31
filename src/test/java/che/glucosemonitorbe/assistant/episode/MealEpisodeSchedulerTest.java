package che.glucosemonitorbe.assistant.episode;

import che.glucosemonitorbe.ai.embedding.EmbeddingGateway;
import che.glucosemonitorbe.ai.embedding.EmbeddingUnavailableException;
import che.glucosemonitorbe.assistant.snapshot.ForecastSnapshotRepository;
import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.CgmReadingRepository;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.repository.UserRepository;
import che.glucosemonitorbe.service.CarbsOnBoardService;
import che.glucosemonitorbe.service.GlucoseCalculationsService;
import che.glucosemonitorbe.service.GlucoseCalculationsService.ActiveCobIobInputs;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import che.glucosemonitorbe.service.UserSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MealEpisodeSchedulerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-01T20:00:00Z");
    private static final LocalDateTime MEAL_AT = LocalDateTime.of(2026, 10, 1, 12, 0);

    private final MealEpisodeRepository episodes = mock(MealEpisodeRepository.class);
    private final NoteRepository notes = mock(NoteRepository.class);
    private final CgmReadingRepository cgm = mock(CgmReadingRepository.class);
    private final UserSettingsService settingsService = mock(UserSettingsService.class);
    private final ForecastSnapshotRepository snapshots = mock(ForecastSnapshotRepository.class);
    private final EmbeddingGateway gateway = mock(EmbeddingGateway.class);
    private final GlucoseCalculationsService calculations = mock(GlucoseCalculationsService.class);
    private final InsulinCalculatorService insulin = mock(InsulinCalculatorService.class);
    private final CarbsOnBoardService cob = mock(CarbsOnBoardService.class);
    private MealEpisodeScheduler scheduler;
    private UserSettingsDTO settings;

    @BeforeEach
    void setUp() {
        settings = new UserSettingsDTO();
        settings.setTimezone("UTC");
        when(settingsService.getUserSettings(USER)).thenReturn(settings);
        when(snapshots.findByNoteId(any())).thenReturn(Optional.empty());
        when(calculations.activeCobIobInputs(any(), any())).thenReturn(inputs(List.of(), List.of()));
        scheduler = new MealEpisodeScheduler(episodes, new MealEpisodeBuilder(), notes, cgm,
                mock(UserRepository.class), settingsService, snapshots, gateway, calculations, insulin, cob,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ActiveCobIobInputs inputs(List<CarbsEntry> carbs, List<InsulinDose> doses) {
        return new ActiveCobIobInputs(List.of(), carbs, doses, settings, new RapidInsulinIobParameters(4.0, 75));
    }

    private static Note note(LocalDateTime at, double carbs, double units, String meal) {
        Note n = new Note(USER, at, carbs, units, meal);
        n.setId(UUID.randomUUID());
        n.setCreatedAt(at);
        n.setUpdatedAt(at);
        return n;
    }

    private static List<CgmReading> flatCgm() {
        List<CgmReading> out = new ArrayList<>();
        for (int m = -5; m <= 245; m += 5) {
            CgmReading r = new CgmReading();
            r.setSgv(110);
            r.setDateTimestamp(MEAL_AT.plusMinutes(m).toInstant(ZoneOffset.UTC).toEpochMilli());
            out.add(r);
        }
        return out;
    }

    @Test
    @DisplayName("IOB and COB exclude the meal note and its pre-bolus note")
    void loadExcludesOwnNotes() {
        Note meal = note(MEAL_AT, 40, 0, "lunch");
        Note pre = note(MEAL_AT.minusMinutes(20), 0, 5, "bolus");
        Note earlier = note(MEAL_AT.minusHours(2), 0, 2, "bolus");
        Note snack = note(MEAL_AT.minusHours(1), 20, 0, "snack");
        when(calculations.activeCobIobInputs(USER, MEAL_AT)).thenReturn(inputs(
                List.of(carb(meal), carb(snack)),
                List.of(dose(pre), dose(earlier))));
        when(insulin.calculateTotalActiveInsulin(anyList(), eq(MEAL_AT), anyDouble(), anyDouble())).thenReturn(0.8);
        when(cob.calculateTotalCarbsOnBoard(anyList(), eq(MEAL_AT), any(UserSettingsDTO.class))).thenReturn(9.0);

        MealLoad load = scheduler.loadAt(USER, meal, List.of(pre, earlier, snack));

        assertThat(load).isEqualTo(new MealLoad(0.8, 9.0));
        @SuppressWarnings("unchecked") ArgumentCaptor<List<InsulinDose>> doses = ArgumentCaptor.forClass(List.class);
        verify(insulin).calculateTotalActiveInsulin(doses.capture(), eq(MEAL_AT), eq(4.0), eq(75.0));
        assertThat(doses.getValue()).extracting(InsulinDose::getId).containsExactly(earlier.getId());
        @SuppressWarnings("unchecked") ArgumentCaptor<List<CarbsEntry>> carbs = ArgumentCaptor.forClass(List.class);
        verify(cob).calculateTotalCarbsOnBoard(carbs.capture(), eq(MEAL_AT), any(UserSettingsDTO.class));
        assertThat(carbs.getValue()).extracting(CarbsEntry::getId).containsExactly(snack.getId());
    }

    private static CarbsEntry carb(Note n) {
        return CarbsEntry.builder().id(n.getId()).timestamp(n.getTimestamp()).carbs(n.getCarbs()).build();
    }

    private static InsulinDose dose(Note n) {
        return InsulinDose.builder().id(n.getId()).timestamp(n.getTimestamp()).units(n.getInsulin()).build();
    }

    @Test
    @DisplayName("a correction logged after the build makes the rebuilt episode contaminated")
    void lateCorrectionRebuildsEpisode() {
        Note meal = note(MEAL_AT, 40, 4, "lunch");
        MealEpisode old = new MealEpisode();
        old.setId(UUID.randomUUID());
        old.setNoteId(meal.getId());
        old.setUserId(USER);
        old.setMealAt(MEAL_AT);
        old.setFoodText("lunch");
        old.setCarbs(40);
        old.setLocalMinuteOfDay(720);
        old.setUpdatedAt(Instant.parse("2026-10-01T17:00:00Z"));
        LocalDateTime builtLocal = LocalDateTime.ofInstant(old.getUpdatedAt(), ZoneId.systemDefault());
        Note correction = note(MEAL_AT.plusMinutes(90), 0, 2, "correction");
        correction.setCreatedAt(builtLocal.plusHours(1));
        correction.setUpdatedAt(builtLocal.plusHours(1));

        when(episodes.existsByUserId(USER)).thenReturn(true);
        when(notes.findByUserIdAndTimestampBetween(eq(USER), any(), any())).thenReturn(List.of(meal, correction));
        when(episodes.findByUserIdAndMealAtGreaterThanEqual(eq(USER), any())).thenReturn(List.of(old));
        when(cgm.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(eq(USER), anyLong(), anyLong()))
                .thenReturn(flatCgm());
        when(insulin.calculateTotalActiveInsulin(anyList(), any(), anyDouble(), anyDouble())).thenReturn(0.0);

        assertThat(scheduler.buildForUser(USER)).isEqualTo(1);

        ArgumentCaptor<MealEpisode> saved = ArgumentCaptor.forClass(MealEpisode.class);
        verify(episodes).save(saved.capture());
        assertThat(saved.getValue().isContaminated()).isTrue();
        assertThat(saved.getValue().getId()).isEqualTo(old.getId());
        verify(episodes, never()).clearEmbedding(any());   // food text unchanged
    }

    @Test
    @DisplayName("an episode with no later note changes is left alone")
    void unchangedEpisodeNotRebuilt() {
        Note meal = note(MEAL_AT, 40, 4, "lunch");
        MealEpisode old = new MealEpisode();
        old.setNoteId(meal.getId());
        old.setUpdatedAt(Instant.parse("2026-10-01T17:00:00Z"));
        when(episodes.existsByUserId(USER)).thenReturn(true);
        when(notes.findByUserIdAndTimestampBetween(eq(USER), any(), any())).thenReturn(List.of(meal));
        when(episodes.findByUserIdAndMealAtGreaterThanEqual(eq(USER), any())).thenReturn(List.of(old));

        assertThat(scheduler.buildForUser(USER)).isZero();
        verify(episodes, never()).save(any());
    }

    private MealEpisode pending(String text) {
        MealEpisode e = new MealEpisode();
        e.setId(UUID.randomUUID());
        e.setFoodText(text);
        e.setCarbs(30);
        e.setLocalMinuteOfDay(720);
        return e;
    }

    @Test
    @DisplayName("a batch failure retries item by item and marks only the row that fails alone")
    void badRowDoesNotStallQueue() {
        MealEpisode good = pending("lunch, soup");
        MealEpisode bad = pending("lunch, bad");
        when(episodes.findWithoutEmbedding(eq(50), any())).thenReturn(List.of(good, bad)).thenReturn(List.of());
        when(gateway.embed(anyList())).thenAnswer(inv -> {
            List<String> t = inv.getArgument(0);
            if (t.contains("lunch, bad")) throw new EmbeddingUnavailableException("rejected");
            return t.stream().map(x -> new float[]{1f}).toList();
        });

        scheduler.fillEmbeddings();

        verify(episodes).setEmbedding(eq(good.getId()), anyString());
        verify(episodes).markEmbeddingFailed(eq(bad.getId()), any());
        verify(episodes, never()).markEmbeddingFailed(eq(good.getId()), any());
    }

    @Test
    @DisplayName("when every item fails (outage) nothing is marked and the run stops")
    void outageMarksNothing() {
        MealEpisode a = pending("a");
        MealEpisode b = pending("b");
        when(episodes.findWithoutEmbedding(eq(50), any())).thenReturn(List.of(a, b));
        when(gateway.embed(anyList())).thenThrow(new EmbeddingUnavailableException("down"));

        scheduler.fillEmbeddings();

        verify(episodes, never()).markEmbeddingFailed(any(), any());
        verify(episodes, never()).setEmbedding(any(), anyString());
        verify(episodes, times(1)).findWithoutEmbedding(anyInt(), any());
    }
}
