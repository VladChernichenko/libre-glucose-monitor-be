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
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.service.GlucoseCalculationsService;
import che.glucosemonitorbe.service.HypoEventService;
import che.glucosemonitorbe.service.UserService;
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
    private CarbAdviceService service;

    @BeforeEach
    void setUp() {
        service = new CarbAdviceService(calculations, cgm, hypoEvents, notes, users, new CarbAdviceHold(),
                Clock.fixed(CLOCK_NOW, ZoneOffset.UTC));
        User user = new User();
        user.setUsername("vlad");
        when(users.getUserById(USER)).thenReturn(user);
        freshReadingAt(READING_0, 6.0);
        when(hypoEvents.findOpenEvent(USER)).thenReturn(Optional.empty());
        when(notes.findByUserIdAndTimestampBetween(eq(USER), any(), any())).thenReturn(List.of());
        // Default gram search: every amount lifts the 2 h minimum to 6.0.
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

    /** The gram search's forecast: minimum over 2 h for each candidate (default 6.0). */
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
    void triggersWhenForecastUnder4_5Within60() {
        Optional<CarbAdvice> advice = service.evaluate(USER, NOW, 6.0, 0.0, vShape(6.0, 4.3, 45));

        assertThat(advice).isPresent();
        assertThat(advice.get().state()).isEqualTo(CarbAdvice.State.TAKE_NOW);
        assertThat(advice.get().minWithout()).isEqualTo(4.3);
        assertThat(advice.get().minWithoutAt()).isEqualTo(NOW.plusMinutes(45));
        assertThat(advice.get().recheckAt()).isEqualTo(NOW.plusMinutes(15));
    }

    @Test
    void triggersOnFastFallWithForecastUnder5Within90() {
        // Stays above 4.5 inside 60 min (5.1 at +60), reaches 4.8 at +80.
        Optional<CarbAdvice> advice = service.evaluate(USER, NOW, 6.0, -0.15, vShape(6.0, 4.8, 80));

        assertThat(advice).isPresent();
        assertThat(advice.get().minWithout()).isEqualTo(4.8);
    }

    @Test
    void noTriggerOnSlowFallStayingAbove5() {
        // Same forecast as the fast-fall case, but the drop is slow: no fast-fall trigger,
        // and nothing under 4.5 within the hour.
        assertThat(service.evaluate(USER, NOW, 6.0, -0.05, vShape(6.0, 4.8, 80))).isEmpty();
        // A slow fall that never goes under 5.0.
        assertThat(service.evaluate(USER, NOW, 6.0, -0.05, vShape(6.0, 5.2, 80))).isEmpty();
        verify(calculations, never()).calculateGlucoseData(any());
    }

    // -- grams -------------------------------------------------------------------------------

    @Test
    void picksSmallestGramsKeepingMinAbove5() {
        gramMinimums(Map.of(5, 4.6, 10, 4.9, 15, 5.3));

        CarbAdvice advice = service.evaluate(USER, NOW, 6.0, 0.0, vShape(6.0, 4.3, 45)).orElseThrow();

        assertThat(advice.grams()).isEqualTo(15);
        assertThat(advice.minWith()).isEqualTo(5.3);
        assertThat(advice.curveWith()).isNotEmpty();
        assertThat(advice.curveWithout()).hasSize(48);

        // Each re-run is a what-if with a fast-sugar note taken now, for the user by username.
        ArgumentCaptor<GlucoseCalculationsRequest> req = ArgumentCaptor.forClass(GlucoseCalculationsRequest.class);
        verify(calculations, times(3)).calculateGlucoseData(req.capture());
        GlucoseCalculationsRequest first = req.getAllValues().get(0);
        assertThat(first.getUserId()).isEqualTo("vlad");
        assertThat(first.getCurrentGlucose()).isEqualTo(6.0);
        assertThat(first.getClientTimeInfo().toLocalDateTime()).isEqualTo(NOW);
        ProspectiveNoteDTO note = first.getProspectiveNotes().get(0);
        assertThat(note.getType()).isEqualTo(Note.TYPE_HYPO_TREATMENT);
        assertThat(note.getMinutesAgo()).isZero();
        assertThat(req.getAllValues()).extracting(r -> r.getProspectiveNotes().get(0).getCarbs())
                .containsExactly(5.0, 10.0, 15.0);
    }

    @Test
    void capsAt40WithRecheck10Min() {
        gramMinimums(Map.of(5, 4.0, 10, 4.1, 15, 4.2, 20, 4.3, 25, 4.4, 30, 4.5, 35, 4.6, 40, 4.7));

        CarbAdvice advice = service.evaluate(USER, NOW, 6.0, -0.15, vShape(6.0, 3.5, 40)).orElseThrow();

        assertThat(advice.grams()).isEqualTo(40);
        assertThat(advice.minWith()).isEqualTo(4.7);
        assertThat(advice.recheckAt()).isEqualTo(NOW.plusMinutes(10));
    }

    // -- no stacking, freshness, hypo flow ---------------------------------------------------

    @Test
    void stillLandingWithin15MinOfFastCarbs() {
        LocalDateTime tookAt = NOW.minusMinutes(10);
        when(notes.findByUserIdAndTimestampBetween(eq(USER), any(), any()))
                .thenReturn(List.of(fastCarbsAt(tookAt, 15.0)));

        CarbAdvice advice = service.evaluate(USER, NOW, 6.0, 0.0, vShape(6.0, 4.3, 45)).orElseThrow();

        assertThat(advice.state()).isEqualTo(CarbAdvice.State.STILL_LANDING);
        assertThat(advice.grams()).isNull();
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

        CarbAdvice advice = service.evaluate(USER, NOW, 6.0, 0.0, vShape(6.0, 4.3, 45)).orElseThrow();

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
        List<PredictionPointDTO> falling = vShape(6.0, 4.3, 45);
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
        List<PredictionPointDTO> falling = vShape(6.0, 4.3, 45);
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
        service.evaluate(USER, NOW, 6.0, 0.0, vShape(6.0, 4.3, 45));

        // The user took the carbs; the forecast with them no longer meets the trigger,
        // but the held card must not keep saying "take 15 g now".
        LocalDateTime tookAt = NOW.plusMinutes(2);
        when(notes.findByUserIdAndTimestampBetween(eq(USER), any(), any()))
                .thenReturn(List.of(fastCarbsAt(tookAt, 15.0)));
        freshReadingAt(READING_0.plusMinutes(5), 6.0);

        CarbAdvice held = service.evaluate(USER, NOW.plusMinutes(5), 6.0, 0.0, vShape(6.0, 5.8, 45)).orElseThrow();
        assertThat(held.state()).isEqualTo(CarbAdvice.State.STILL_LANDING);
        assertThat(held.grams()).isNull();
        assertThat(held.recheckAt()).isEqualTo(tookAt.plusMinutes(15));
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
        List<PredictionPointDTO> forecast = vShape(6.0, 4.3, 45);
        GlucoseCalculationsResponse forecastOnly = GlucoseCalculationsResponse.builder()
                .calculatedAt(NOW).predictionPath(forecast).build();
        doReturn(forecastOnly).when(calc).calculateGlucoseData(any());
        CarbAdvice shown = new CarbAdvice(CarbAdvice.State.TAKE_NOW, 15, 4.3, NOW.plusMinutes(45), 5.3,
                forecast, forecast, NOW.plusMinutes(15));
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
