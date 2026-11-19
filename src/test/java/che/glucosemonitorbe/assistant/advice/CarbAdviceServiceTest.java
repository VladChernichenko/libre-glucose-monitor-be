package che.glucosemonitorbe.assistant.advice;

import che.glucosemonitorbe.assistant.common.CgmFreshnessService;
import che.glucosemonitorbe.assistant.common.CgmReadingDTO;
import che.glucosemonitorbe.domain.User;
import che.glucosemonitorbe.dto.GlucoseCalculationsRequest;
import che.glucosemonitorbe.dto.GlucoseCalculationsResponse;
import che.glucosemonitorbe.dto.HypoEventDTO;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.ProspectiveNoteDTO;
import che.glucosemonitorbe.dto.UserDto;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.service.GlucoseCalculationsService;
import che.glucosemonitorbe.service.HypoEventService;
import che.glucosemonitorbe.service.UserService;
import che.glucosemonitorbe.service.UserSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntToDoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CarbAdviceServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 31, 19, 0);
    private static final Instant CLOCK_NOW = Instant.parse("2026-10-31T19:00:00Z");
    private static final LocalDateTime READING_0 = LocalDateTime.of(2026, 10, 31, 18, 58);

    private final GlucoseCalculationsService calculations = mock(GlucoseCalculationsService.class);
    private final CgmFreshnessService cgm = mock(CgmFreshnessService.class);
    private final HypoEventService hypoEvents = mock(HypoEventService.class);
    private final NoteRepository notes = mock(NoteRepository.class);
    private final UserService users = mock(UserService.class);
    private final UserSettingsService settings = mock(UserSettingsService.class);
    private CarbAdviceService service;

    @BeforeEach
    void setUp() {
        service = new CarbAdviceService(calculations, cgm, hypoEvents, notes, users, settings, new CarbAdviceHold(),
                Clock.fixed(CLOCK_NOW, ZoneOffset.UTC));
        User user = new User();
        user.setUsername("vlad");
        when(users.getUserById(USER)).thenReturn(user);
        freshReadingAt(READING_0, 6.0);
        when(hypoEvents.findOpenEvent(USER)).thenReturn(Optional.empty());
        when(notes.findByUserIdAndTimestampBetween(eq(USER), any(), any())).thenReturn(List.of());
        // Default rescue-dose forecast: the 2 h minimum comes back at 6.0.
        gramMinimums(Map.of());
    }

    // -- helpers -----------------------------------------------------------------------------

    private void freshReadingAt(LocalDateTime at, double mmol) {
        when(cgm.freshReading(eq(USER), eq(CLOCK_NOW))).thenReturn(Optional.of(new CgmReadingDTO(mmol, at, 3, "FortyFiveDown")));
    }

    /** 5-minute grid from +5 to +240 min, glucose = f(minute). */
    private static List<PredictionPointDTO> path(IntToDoubleFunction f) {
        List<PredictionPointDTO> out = new ArrayList<>();
        for (int m = 5; m <= 240; m += 5) {
            out.add(PredictionPointDTO.builder().timestamp(NOW.plusMinutes(m)).predictedGlucose(f.applyAsDouble(m)).build());
        }
        return out;
    }

    /** V-shaped forecast: from {@code start} down to {@code min} at {@code minAt} minutes, then back up. */
    private static List<PredictionPointDTO> vShape(double start, double min, int minAt) {
        return path(m -> m <= minAt
                ? start + (min - start) * m / minAt
                : min + (m - minAt) * 0.02);
    }

    /** The rescue-dose forecast: 2 h minimum for the grams asked (default 6.0). */
    private void gramMinimums(Map<Integer, Double> minByGrams) {
        doAnswer(inv -> {
            GlucoseCalculationsRequest req = inv.getArgument(0);
            int grams = (int) Math.round(req.getProspectiveNotes().get(0).getCarbs());
            double min = minByGrams.getOrDefault(grams, 6.0);
            GlucoseCalculationsResponse resp = new GlucoseCalculationsResponse();
            resp.setPredictionPath(vShape(6.0, min, 60));
            return resp;
        }).when(calculations).calculateGlucoseData(any());
    }

    private Note fastCarbsAt(LocalDateTime t, double grams) {
        Note n = new Note(USER, t, grams, 0.0, "Hypo treatment");
        n.setType(Note.TYPE_HYPO_TREATMENT);
        return n;
    }

    // -- trigger -----------------------------------------------------------------------------

    @Test
    void triggersWhenForecastGoesUnderHypoWithin60() {
        Optional<CarbAdvice> advice = service.evaluate(USER, NOW, 6.0, 0.0, vShape(6.0, 3.4, 45));

        assertThat(advice).isPresent();
        assertThat(advice.get().state()).isEqualTo(CarbAdvice.State.TAKE_NOW);
        assertThat(advice.get().minWithout()).isEqualTo(3.4);
        assertThat(advice.get().minWithoutAt()).isEqualTo(NOW.plusMinutes(45));
        assertThat(advice.get().recheckAt()).isEqualTo(NOW.plusMinutes(15));
    }

    @Test
    void triggersOnFastFallIntoHypoWithin90() {
        // At +60 the path is still above 3.9 (4.1); it reaches 3.5 at +80, and the fall is fast.
        Optional<CarbAdvice> advice = service.evaluate(USER, NOW, 6.0, -0.15, vShape(6.0, 3.5, 80));

        assertThat(advice).isPresent();
        assertThat(advice.get().minWithout()).isEqualTo(3.5);
    }

    @Test
    void noLowWhenForecastSettlesAboveHypo() {
        // The reported case: 4.9 now, forecast settles at 4.4. Above 3.9, so not a low,
        // whether the drop is slow or fast.
        assertThat(service.evaluate(USER, NOW, 4.9, 0.0, vShape(4.9, 4.4, 50))).isEmpty();
        assertThat(service.evaluate(USER, NOW, 6.0, -0.15, vShape(6.0, 4.4, 50))).isEmpty();
        // A slow fall that only reaches hypo after the hour is not imminent.
        assertThat(service.evaluate(USER, NOW, 6.0, -0.05, vShape(6.0, 3.5, 80))).isEmpty();
        assertThat(service.evaluate(USER, NOW, 6.0, -0.05, vShape(6.0, 5.2, 80))).isEmpty();
        verify(calculations, never()).calculateGlucoseData(any());
    }

    // -- grams -------------------------------------------------------------------------------

    @Test
    void offersRescueDoseFromSettings() {
        UserSettingsDTO saved = new UserSettingsDTO();
        saved.setRescueCarbsG(10);
        when(settings.getUserSettings(USER)).thenReturn(saved);
        gramMinimums(Map.of(10, 4.8));

        CarbAdvice advice = service.evaluate(USER, NOW, 6.0, 0.0, vShape(6.0, 3.4, 45)).orElseThrow();

        assertThat(advice.grams()).isEqualTo(10);
        assertThat(advice.minWith()).isEqualTo(4.8);
        assertThat(advice.curveWith()).isNotEmpty();
        assertThat(advice.curveWithout()).hasSize(48);
        assertThat(advice.recheckAt()).isEqualTo(NOW.plusMinutes(15));

        ArgumentCaptor<GlucoseCalculationsRequest> req = ArgumentCaptor.forClass(GlucoseCalculationsRequest.class);
        verify(calculations, times(1)).calculateGlucoseData(req.capture());
        GlucoseCalculationsRequest only = req.getValue();
        assertThat(only.getUserId()).isEqualTo("vlad");
        assertThat(only.getCurrentGlucose()).isEqualTo(6.0);
        assertThat(only.getClientTimeInfo().toLocalDateTime()).isEqualTo(NOW);
        ProspectiveNoteDTO note = only.getProspectiveNotes().get(0);
        assertThat(note.getType()).isEqualTo(Note.TYPE_HYPO_TREATMENT);
        assertThat(note.getMinutesAgo()).isZero();
        assertThat(note.getCarbs()).isEqualTo(10.0);
    }

    @Test
    void defaultsTo15gWhenRescueSettingIsMissing() {
        when(settings.getUserSettings(USER)).thenReturn(new UserSettingsDTO());

        CarbAdvice advice = service.evaluate(USER, NOW, 6.0, -0.15, vShape(6.0, 3.2, 40)).orElseThrow();

        assertThat(advice.grams()).isEqualTo(15);
        assertThat(advice.recheckAt()).isEqualTo(NOW.plusMinutes(15));
        verify(calculations, times(1)).calculateGlucoseData(any());
    }

    @Test
    void stepsUpUntilForecastStaysAboveSafeMin() {
        // The reported case: 10 g still forecast 2.2; heavy IOB needs more.
        UserSettingsDTO saved = new UserSettingsDTO();
        saved.setRescueCarbsG(10);
        when(settings.getUserSettings(USER)).thenReturn(saved);
        gramMinimums(Map.of(10, 2.2, 15, 3.0, 20, 3.8, 25, 4.3, 30, 4.7));

        CarbAdvice advice = service.evaluate(USER, NOW, 7.0, -0.15, vShape(7.0, 1.0, 50)).orElseThrow();

        assertThat(advice.grams()).isEqualTo(30);
        assertThat(advice.minWith()).isEqualTo(4.7);
        verify(calculations, times(5)).calculateGlucoseData(any());
    }

    @Test
    void capsAt60gWhenNothingIsEnough() {
        when(settings.getUserSettings(USER)).thenReturn(new UserSettingsDTO());
        java.util.Map<Integer, Double> allLow = new java.util.HashMap<>();
        for (int g = 15; g <= 60; g += 5) allLow.put(g, 2.0);
        gramMinimums(allLow);

        CarbAdvice advice = service.evaluate(USER, NOW, 6.0, -0.15, vShape(6.0, 1.0, 40)).orElseThrow();

        assertThat(advice.grams()).isEqualTo(60);
        assertThat(advice.minWith()).isEqualTo(2.0);
        verify(calculations, times(10)).calculateGlucoseData(any());
    }

    // -- no stacking, freshness, hypo flow ---------------------------------------------------

    @Test
    void stillLandingWithin15MinOfFastCarbs() {
        LocalDateTime tookAt = NOW.minusMinutes(10);
        when(notes.findByUserIdAndTimestampBetween(eq(USER), any(), any()))
                .thenReturn(List.of(fastCarbsAt(NOW.minusMinutes(14), 10.0), fastCarbsAt(tookAt, 15.0)));

        CarbAdvice advice = service.evaluate(USER, NOW, 6.0, 0.0, vShape(6.0, 3.4, 45)).orElseThrow();

        assertThat(advice.state()).isEqualTo(CarbAdvice.State.STILL_LANDING);
        assertThat(advice.grams()).isNull();
        // "the 25 g is still landing": all fast carbs in the window; recheck from the newest.
        assertThat(advice.landingGrams()).isEqualTo(25);
        assertThat(advice.minWith()).isNull();
        assertThat(advice.curveWith()).isNull();
        assertThat(advice.recheckAt()).isEqualTo(tookAt.plusMinutes(15));
        verify(calculations, never()).calculateGlucoseData(any());
    }

    @Test
    void fastCarbsMoreThan15MinAgoDoNotBlockGrams() {
        // A normal meal note inside the window is not fast carbs, and the repository window is [now-15, now].
        when(notes.findByUserIdAndTimestampBetween(eq(USER), any(), any()))
                .thenReturn(List.of(new Note(USER, NOW.minusMinutes(5), 40.0, 4.0, "Dinner")));

        CarbAdvice advice = service.evaluate(USER, NOW, 6.0, 0.0, vShape(6.0, 3.4, 45)).orElseThrow();

        assertThat(advice.state()).isEqualTo(CarbAdvice.State.TAKE_NOW);
        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(notes).findByUserIdAndTimestampBetween(eq(USER), from.capture(), eq(NOW));
        assertThat(from.getValue()).isEqualTo(NOW.minusMinutes(15));
    }

    @Test
    void staleCgmNoAdvice() {
        when(cgm.freshReading(eq(USER), any())).thenReturn(Optional.empty());

        assertThat(service.evaluate(USER, NOW, 6.0, -0.15, vShape(6.0, 3.5, 40))).isEmpty();
        verify(calculations, never()).calculateGlucoseData(any());
    }

    @Test
    void under3_9DefersToHypoFlow() {
        freshReadingAt(READING_0, 3.7);

        assertThat(service.evaluate(USER, NOW, 3.7, -0.15, vShape(3.7, 2.9, 30))).isEmpty();
        verify(calculations, never()).calculateGlucoseData(any());
    }

    @Test
    void openHypoEventDefersToHypoFlow() {
        when(hypoEvents.findOpenEvent(USER)).thenReturn(Optional.of(
                new HypoEventDTO(UUID.randomUUID(), 3.6, "OPEN", null, NOW.minusMinutes(5), null)));

        assertThat(service.evaluate(USER, NOW, 4.2, -0.15, vShape(4.2, 3.5, 30))).isEmpty();
    }

    // -- hold --------------------------------------------------------------------------------

    @Test
    void holdKeepsAdviceUntilTwoNewReadingsClear() {
        List<PredictionPointDTO> falling = vShape(6.0, 3.4, 45);
        List<PredictionPointDTO> fine = vShape(6.0, 5.8, 45);

        assertThat(service.evaluate(USER, NOW, 6.0, 0.0, falling)).isPresent();

        // First new reading that no longer meets the trigger: still shown.
        freshReadingAt(READING_0.plusMinutes(5), 6.0);
        assertThat(service.evaluate(USER, NOW.plusMinutes(5), 6.0, 0.0, fine)).isPresent();
        // Same stored reading polled again: not a new reading, still shown.
        assertThat(service.evaluate(USER, NOW.plusMinutes(5), 6.0, 0.0, fine)).isPresent();

        // Second new clearing reading: the advice goes.
        freshReadingAt(READING_0.plusMinutes(10), 6.0);
        assertThat(service.evaluate(USER, NOW.plusMinutes(10), 6.0, 0.0, fine)).isEmpty();
        assertThat(service.evaluate(USER, NOW.plusMinutes(10), 6.0, 0.0, fine)).isEmpty();
    }

    @Test
    void triggerAgainResetsTheClearCount() {
        List<PredictionPointDTO> falling = vShape(6.0, 3.4, 45);
        List<PredictionPointDTO> fine = vShape(6.0, 5.8, 45);

        service.evaluate(USER, NOW, 6.0, 0.0, falling);
        freshReadingAt(READING_0.plusMinutes(5), 6.0);
        service.evaluate(USER, NOW, 6.0, 0.0, fine);
        freshReadingAt(READING_0.plusMinutes(10), 6.0);
        assertThat(service.evaluate(USER, NOW, 6.0, 0.0, falling)).isPresent();
        freshReadingAt(READING_0.plusMinutes(15), 6.0);
        assertThat(service.evaluate(USER, NOW, 6.0, 0.0, fine)).isPresent();
    }

    @Test
    void heldAdviceNeverRepeatsGramsWhileFastCarbsAreLanding() {
        service.evaluate(USER, NOW, 6.0, 0.0, vShape(6.0, 3.4, 45));

        // The user took the carbs; the forecast with them no longer meets the trigger,
        // but the held card must not keep saying "take 15 g now".
        LocalDateTime tookAt = NOW.plusMinutes(2);
        when(notes.findByUserIdAndTimestampBetween(eq(USER), any(), any()))
                .thenReturn(List.of(fastCarbsAt(tookAt, 15.0)));
        freshReadingAt(READING_0.plusMinutes(5), 6.0);

        CarbAdvice held = service.evaluate(USER, NOW.plusMinutes(5), 6.0, 0.0, vShape(6.0, 5.8, 45)).orElseThrow();
        assertThat(held.state()).isEqualTo(CarbAdvice.State.STILL_LANDING);
        assertThat(held.grams()).isNull();
        assertThat(held.landingGrams()).isEqualTo(15);
        assertThat(held.recheckAt()).isEqualTo(tookAt.plusMinutes(15));
    }

    @Test
    void staleCgmEndsTheHold() {
        service.evaluate(USER, NOW, 6.0, 0.0, vShape(6.0, 3.4, 45));

        when(cgm.freshReading(eq(USER), any())).thenReturn(Optional.empty());
        assertThat(service.evaluate(USER, NOW.plusMinutes(30), 6.0, 0.0, vShape(6.0, 5.8, 45))).isEmpty();

        // Data back, first fresh reading does not trigger: the old card must not come back.
        freshReadingAt(READING_0.plusMinutes(60), 6.0);
        assertThat(service.evaluate(USER, NOW.plusMinutes(60), 6.0, 0.0, vShape(6.0, 5.8, 45))).isEmpty();
    }

    @Test
    void heldAdviceFromBeforeAReadingGapIsDropped() {
        // No stale call in between (e.g. no dashboard poll during the gap), but the next stored
        // reading is more than 15 min after the one the advice was held on.
        service.evaluate(USER, NOW, 6.0, 0.0, vShape(6.0, 3.4, 45));

        freshReadingAt(READING_0.plusMinutes(16), 6.0);
        assertThat(service.evaluate(USER, NOW.plusMinutes(16), 6.0, 0.0, vShape(6.0, 5.8, 45))).isEmpty();
    }

    @Test
    void takeNowCarriesNoLandingGrams() {
        assertThat(service.evaluate(USER, NOW, 6.0, 0.0, vShape(6.0, 3.4, 45)).orElseThrow().landingGrams()).isNull();
    }

    // -- the calculations hook ---------------------------------------------------------------

    @Test
    void prospectiveRequestDoesNotTouchHold() {
        UserService calcUsers = mock(UserService.class);
        when(calcUsers.getUserByUsername("vlad")).thenReturn(UserDto.builder().id(USER).username("vlad").build());
        GlucoseCalculationsService calc = spy(new GlucoseCalculationsService(
                null, null, null, calcUsers, null, null, null, null, null));
        CarbAdviceService advice = mock(CarbAdviceService.class);
        ReflectionTestUtils.setField(calc, "carbAdviceService", advice);
        List<PredictionPointDTO> forecast = vShape(6.0, 3.4, 45);
        GlucoseCalculationsResponse forecastOnly = GlucoseCalculationsResponse.builder()
                .calculatedAt(NOW).predictionPath(forecast).build();
        doReturn(forecastOnly).when(calc).calculateGlucoseData(any());
        CarbAdvice shown = new CarbAdvice(CarbAdvice.State.TAKE_NOW, 15, 4.3, NOW.plusMinutes(45), 5.3,
                forecast, forecast, NOW.plusMinutes(15), null);
        when(advice.evaluate(USER, NOW, 6.0, -0.12, forecast)).thenReturn(Optional.of(shown));

        // What-if request (Nutrition screen, or the gram search itself): advice is never evaluated.
        GlucoseCalculationsRequest whatIf = GlucoseCalculationsRequest.builder()
                .userId("vlad").currentGlucose(6.0).currentTrendMmolPerMin(-0.12)
                .prospectiveNotes(List.of(ProspectiveNoteDTO.builder().carbs(40.0).build())).build();
        assertThat(calc.calculateDashboard(whatIf).getCarbAdvice()).isNull();
        verifyNoInteractions(advice);

        // Plain dashboard request: evaluated after the forecast, on that forecast.
        GlucoseCalculationsRequest dashboard = GlucoseCalculationsRequest.builder()
                .userId("vlad").currentGlucose(6.0).currentTrendMmolPerMin(-0.12).build();
        assertThat(calc.calculateDashboard(dashboard).getCarbAdvice()).isEqualTo(shown);
    }
}
