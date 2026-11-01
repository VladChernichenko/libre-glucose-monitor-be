package che.glucosemonitorbe.watch;

import che.glucosemonitorbe.assistant.advice.CarbAdvice;
import che.glucosemonitorbe.assistant.common.CgmFreshnessService;
import che.glucosemonitorbe.assistant.common.CgmReadingDTO;
import che.glucosemonitorbe.dto.GlucoseCalculationsResponse;
import che.glucosemonitorbe.dto.HypoEventDTO;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.repository.NoteRepository;
import che.glucosemonitorbe.exception.DosingRefusalReason;
import che.glucosemonitorbe.exception.DosingRefusedException;
import che.glucosemonitorbe.service.HypoEventService;
import che.glucosemonitorbe.service.InsulinCalculatorService;
import che.glucosemonitorbe.service.UserSettingsService;
import che.glucosemonitorbe.watch.WatchStrip.Kind;
import che.glucosemonitorbe.watch.WatchStrip.Reason;
import che.glucosemonitorbe.watch.WatchStrip.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntToDoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WatchStripServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 31, 19, 0);
    private static final Instant CLOCK_NOW = Instant.parse("2026-10-31T19:00:00Z");

    private final CgmFreshnessService cgm = mock(CgmFreshnessService.class);
    private final HypoEventService hypoEvents = mock(HypoEventService.class);
    private final NoteRepository notes = mock(NoteRepository.class);
    private final UserSettingsService settings = mock(UserSettingsService.class);
    private final InsulinCalculatorService calculator = mock(InsulinCalculatorService.class);
    private final TwinIsfService twinIsf = mock(TwinIsfService.class);
    private WatchStripService service;

    @BeforeEach
    void setUp() {
        service = new WatchStripService(cgm, hypoEvents, notes, settings, calculator, twinIsf,
                new WatchStateHold(), Clock.fixed(CLOCK_NOW, ZoneOffset.UTC));
        reading(6.0);
        when(hypoEvents.findOpenEvent(USER)).thenReturn(Optional.empty());
        when(hypoEvents.findUnrecoveredConfirmed(USER)).thenReturn(Optional.empty());
        when(notes.findByUserIdAndTimestampBetween(eq(USER), any(), any())).thenReturn(List.of());
        UserSettingsDTO s = new UserSettingsDTO();
        s.setRescueCarbsG(20);
        when(settings.getUserSettings(USER)).thenReturn(s);
        when(twinIsf.effectiveIsf(eq(USER), any(), any())).thenReturn(2.0);
    }

    // -- helpers -----------------------------------------------------------------------------

    private void readingStoredAt(double mmol, int minutesAgo) {
        when(cgm.freshReading(eq(USER), eq(CLOCK_NOW)))
                .thenReturn(Optional.of(new CgmReadingDTO(mmol, NOW.minusMinutes(minutesAgo), 3, "Flat")));
    }

    private void reading(double mmol) {
        when(cgm.freshReading(eq(USER), eq(CLOCK_NOW)))
                .thenReturn(Optional.of(new CgmReadingDTO(mmol, NOW.minusMinutes(2), 3, "Flat")));
    }

    /** 5-minute grid from +5 to +240 min, glucose = f(minute). */
    private static List<PredictionPointDTO> path(IntToDoubleFunction f) {
        List<PredictionPointDTO> out = new ArrayList<>();
        for (int m = 5; m <= 240; m += 5) {
            out.add(PredictionPointDTO.builder().timestamp(NOW.plusMinutes(m)).predictedGlucose(f.applyAsDouble(m)).build());
        }
        return out;
    }

    private static GlucoseCalculationsResponse resp(List<PredictionPointDTO> forecast, Double iob, Double cob) {
        return GlucoseCalculationsResponse.builder()
                .calculatedAt(NOW).predictionPath(forecast)
                .activeInsulinOnBoard(iob).activeCarbsOnBoard(cob)
                .currentGlucose(6.0)
                .build();
    }

    private static GlucoseCalculationsResponse flat() {
        return resp(path(m -> 6.0), 0.0, 0.0);
    }

    private static Note note(double carbs, double insulin, String type, int minutesAgo) {
        Note n = new Note();
        n.setTimestamp(NOW.minusMinutes(minutesAgo));
        n.setCarbs(carbs);
        n.setInsulin(insulin);
        n.setType(type);
        return n;
    }

    private void notesAre(Note... n) {
        when(notes.findByUserIdAndTimestampBetween(eq(USER), any(), any())).thenReturn(List.of(n));
    }

    private static HypoEventDTO confirmed() {
        return new HypoEventDTO(UUID.randomUUID(), 3.5, "CONFIRMED", UUID.randomUUID(), NOW.minusMinutes(20), NOW.minusMinutes(18));
    }

    // -- rules -------------------------------------------------------------------------------

    @Test
    void noFreshReading_noStrip() {
        when(cgm.freshReading(eq(USER), eq(CLOCK_NOW))).thenReturn(Optional.empty());
        assertThat(service.evaluate(USER, flat())).isEmpty();
    }

    @Test
    void readingBelow39_lowNow_rescueGrams() {
        reading(3.6);
        WatchStrip strip = service.evaluate(USER, flat()).orElseThrow();
        assertThat(strip.state()).isEqualTo(State.URGENT_LOW);
        assertThat(strip.reason()).isEqualTo(Reason.LOW_NOW);
        assertThat(strip.suggestion().kind()).isEqualTo(Kind.CARBS);
        assertThat(strip.suggestion().grams()).isEqualTo(20);
        assertThat(strip.nextCheckAt()).isEqualTo(NOW.plusMinutes(15));
    }

    @Test
    void openHypoEvent_isLowNowEvenAtNormalReading() {
        when(hypoEvents.findOpenEvent(USER)).thenReturn(Optional.of(confirmed()));
        assertThat(service.evaluate(USER, flat()).orElseThrow().reason()).isEqualTo(Reason.LOW_NOW);
    }

    @Test
    void clientGlucoseIgnored_storedReadingWins() {
        GlucoseCalculationsResponse r = flat();
        r.setCurrentGlucose(3.0);
        assertThat(service.evaluate(USER, r)).isEmpty();
    }

    @Test
    void confirmedUnrecovered_staysUntilRecoveredAndHolding() {
        when(hypoEvents.findUnrecoveredConfirmed(USER)).thenReturn(Optional.of(confirmed()));
        reading(4.6);

        WatchStrip strip = service.evaluate(USER, resp(path(m -> m == 40 ? 4.3 : 5.0), 0.0, 0.0)).orElseThrow();
        assertThat(strip.state()).isEqualTo(State.URGENT_LOW);
        assertThat(strip.reason()).isEqualTo(Reason.TREATING_LOW);
        assertThat(strip.suggestion().kind()).isEqualTo(Kind.NO_ACTION);
        assertThat(strip.nextCheckAt()).isEqualTo(NOW.plusMinutes(15));

        assertThat(service.evaluate(USER, resp(path(m -> 5.0), 0.0, 0.0))).isEmpty();
    }

    @Test
    void confirmedUnrecovered_readingStillUnder45_staysEvenWithGoodForecast() {
        when(hypoEvents.findUnrecoveredConfirmed(USER)).thenReturn(Optional.of(confirmed()));
        reading(4.2);
        assertThat(service.evaluate(USER, resp(path(m -> 5.0), 0.0, 0.0)).orElseThrow().reason())
                .isEqualTo(Reason.TREATING_LOW);
    }

    @Test
    void forecastBeyondSixtyMinutesDoesNotKeepTreatingLow() {
        when(hypoEvents.findUnrecoveredConfirmed(USER)).thenReturn(Optional.of(confirmed()));
        reading(4.6);
        assertThat(service.evaluate(USER, resp(path(m -> m == 65 ? 4.0 : 5.0), 0.0, 0.0))).isEmpty();
    }

    @Test
    void staleConfirmedEvent_ignored() {
        // findUnrecoveredConfirmed returns empty past MAX_OPEN_MINUTES
        when(hypoEvents.findUnrecoveredConfirmed(USER)).thenReturn(Optional.empty());
        reading(4.2);
        assertThat(service.evaluate(USER, flat())).isEmpty();
    }

    @Test
    void carbAdviceTakeNow_copiedVerbatim() {
        LocalDateTime lowAt = NOW.plusMinutes(50);
        LocalDateTime recheck = NOW.plusMinutes(15);
        CarbAdvice advice = new CarbAdvice(CarbAdvice.State.TAKE_NOW, 15, 3.8, lowAt, 5.2, List.of(), List.of(), recheck, null);
        GlucoseCalculationsResponse r = flat();
        r.setCarbAdvice(advice);
        r.setActiveInsulinOnBoard(2.1);
        r.setActiveCarbsOnBoard(12.0);

        WatchStrip strip = service.evaluate(USER, r).orElseThrow();
        assertThat(strip.state()).isEqualTo(State.URGENT_LOW);
        assertThat(strip.reason()).isEqualTo(Reason.FORECAST_LOW);
        assertThat(strip.suggestion().kind()).isEqualTo(Kind.CARBS);
        assertThat(strip.suggestion().grams()).isEqualTo(15);
        assertThat(strip.suggestion().minWithCarbs()).isEqualTo(5.2);
        assertThat(strip.low()).isEqualTo(new WatchStrip.GlucosePoint(lowAt, 3.8));
        assertThat(strip.nextCheckAt()).isEqualTo(recheck);
        assertThat(strip.iob()).isEqualTo(2.1);
        assertThat(strip.cob()).isEqualTo(12.0);
        assertThat(strip.activityEstimate()).isEqualTo(WatchStrip.ActivityEstimate.DEFAULT);
    }

    @Test
    void carbAdviceStillLanding_noCarbs() {
        LocalDateTime lowAt = NOW.plusMinutes(40);
        LocalDateTime recheck = NOW.plusMinutes(10);
        CarbAdvice advice = new CarbAdvice(CarbAdvice.State.STILL_LANDING, null, 4.0, lowAt, null, List.of(), null, recheck, 15);
        GlucoseCalculationsResponse r = flat();
        r.setCarbAdvice(advice);

        WatchStrip strip = service.evaluate(USER, r).orElseThrow();
        assertThat(strip.state()).isEqualTo(State.URGENT_LOW);
        assertThat(strip.reason()).isEqualTo(Reason.CARBS_LANDING);
        assertThat(strip.suggestion().kind()).isEqualTo(Kind.NO_ACTION);
        assertThat(strip.suggestion().grams()).isEqualTo(15);
        assertThat(strip.low()).isEqualTo(new WatchStrip.GlucosePoint(lowAt, 4.0));
        assertThat(strip.nextCheckAt()).isEqualTo(recheck);
    }

    @Test
    void calm_afterDoseWithIob_watchingWithLanding() {
        notesAre(note(0, 4.0, Note.TYPE_NORMAL, 90));
        // rises to a peak at +100 (8.0), then settles to 6.5
        WatchStrip strip = service.evaluate(USER,
                resp(path(m -> m <= 100 ? 6.0 + m * 0.02 : 8.0 - (m - 100) * 0.0107), 1.5, 0.0)).orElseThrow();
        assertThat(strip.state()).isEqualTo(State.CALM);
        assertThat(strip.reason()).isEqualTo(Reason.WATCHING);
        assertThat(strip.suggestion().kind()).isEqualTo(Kind.NO_ACTION);
        assertThat(strip.low()).isNull();
        assertThat(strip.landing().at()).isEqualTo(NOW.plusMinutes(240));
        assertThat(strip.nextCheckAt()).isEqualTo(NOW.plusMinutes(60));
    }

    @Test
    void calm_watching_nextCheckIsPeakWhenSooner() {
        notesAre(note(0, 4.0, Note.TYPE_NORMAL, 30));
        WatchStrip strip = service.evaluate(USER,
                resp(path(m -> m <= 20 ? 6.0 + m * 0.1 : 8.0 - (m - 20) * 0.005), 1.5, 0.0)).orElseThrow();
        assertThat(strip.reason()).isEqualTo(Reason.WATCHING);
        assertThat(strip.nextCheckAt()).isEqualTo(NOW.plusMinutes(20));
    }

    @Test
    void calm_dipAhead_whenMinUnder45() {
        notesAre(note(0, 4.0, Note.TYPE_NORMAL, 60));
        // minimum 4.2 at +90, never within the hypo trigger window of carbAdvice (absent here)
        WatchStrip strip = service.evaluate(USER,
                resp(path(m -> m == 90 ? 4.2 : 5.5), 1.5, 0.0)).orElseThrow();
        assertThat(strip.state()).isEqualTo(State.CALM);
        assertThat(strip.reason()).isEqualTo(Reason.DIP_AHEAD);
        assertThat(strip.low()).isEqualTo(new WatchStrip.GlucosePoint(NOW.plusMinutes(90), 4.2));
        assertThat(strip.nextCheckAt()).isEqualTo(NOW.plusMinutes(60));
    }

    @Test
    void calm_dipAhead_nextCheckIsDipWhenSooner() {
        notesAre(note(0, 4.0, Note.TYPE_NORMAL, 60));
        WatchStrip strip = service.evaluate(USER,
                resp(path(m -> m == 30 ? 4.2 : 5.5), 1.5, 0.0)).orElseThrow();
        assertThat(strip.reason()).isEqualTo(Reason.DIP_AHEAD);
        assertThat(strip.nextCheckAt()).isEqualTo(NOW.plusMinutes(30));
    }

    @Test
    void calm_requiresIobOrCob() {
        notesAre(note(0, 3.0, Note.TYPE_NORMAL, 180));
        assertThat(service.evaluate(USER, resp(path(m -> 6.0), 0.2, 0.0))).isEmpty();
    }

    @Test
    void calm_cobAloneWithMealIsEnough() {
        notesAre(note(40, 0, Note.TYPE_NORMAL, 30));
        assertThat(service.evaluate(USER, resp(path(m -> 6.0), 0.0, 6.0)).orElseThrow().state()).isEqualTo(State.CALM);
    }

    @Test
    void calm_activityNoteIsATrigger() {
        notesAre(note(0, 0, Note.TYPE_ACTIVITY, 30));
        assertThat(service.evaluate(USER, resp(path(m -> 6.0), 0.5, 0.0)).orElseThrow().state()).isEqualTo(State.CALM);
    }

    @Test
    void longActingAloneIsNotADose() {
        notesAre(note(0, 20.0, Note.TYPE_LONG_ACTING, 60));
        assertThat(service.evaluate(USER, resp(path(m -> 6.0), 1.0, 0.0))).isEmpty();
    }

    @Test
    void rescueCarbsAloneAreNotACalmTrigger() {
        notesAre(note(15, 0, Note.TYPE_HYPO_TREATMENT, 20));
        assertThat(service.evaluate(USER, resp(path(m -> 6.0), 0.0, 8.0))).isEmpty();
    }

    @Test
    void smallMealUnderTenGramsIsNotATrigger() {
        notesAre(note(8, 0, Note.TYPE_NORMAL, 20));
        assertThat(service.evaluate(USER, resp(path(m -> 6.0), 0.0, 8.0))).isEmpty();
    }

    @Test
    void lowBeatsCalm() {
        notesAre(note(0, 4.0, Note.TYPE_NORMAL, 60));
        reading(3.5);
        assertThat(service.evaluate(USER, resp(path(m -> 6.0), 2.0, 5.0)).orElseThrow().reason())
                .isEqualTo(Reason.LOW_NOW);
    }

    @Test
    void calm_fallingForecast_nextCheckIsAnHour() {
        notesAre(note(0, 4.0, Note.TYPE_NORMAL, 60));
        WatchStrip strip = service.evaluate(USER, resp(path(m -> 6.0 - m * 0.005), 1.5, 0.0)).orElseThrow();
        assertThat(strip.reason()).isEqualTo(Reason.WATCHING);
        assertThat(strip.landing().mmol()).isGreaterThan(4.5);
        assertThat(strip.nextCheckAt()).isEqualTo(NOW.plusMinutes(60));
    }

    @Test
    void calm_flatForecast_nextCheckIsAnHour() {
        notesAre(note(0, 4.0, Note.TYPE_NORMAL, 60));
        assertThat(service.evaluate(USER, resp(path(m -> 6.0), 1.5, 0.0)).orElseThrow().nextCheckAt())
                .isEqualTo(NOW.plusMinutes(60));
    }

    @Test
    void confirmedUnrecovered_noForecastEvidence_staysTreatingLow() {
        when(hypoEvents.findUnrecoveredConfirmed(USER)).thenReturn(Optional.of(confirmed()));
        reading(4.6);
        assertThat(service.evaluate(USER, resp(null, 0.0, 0.0)).orElseThrow().reason()).isEqualTo(Reason.TREATING_LOW);
        assertThat(service.evaluate(USER, resp(List.of(), 0.0, 0.0)).orElseThrow().reason()).isEqualTo(Reason.TREATING_LOW);
    }

    // -- URGENT_HIGH -------------------------------------------------------------------------

    private static GlucoseCalculationsResponse high(double twoHour, double fourHour) {
        GlucoseCalculationsResponse r = flat();
        r.setTwoHourPrediction(twoHour);
        r.setFourHourPrediction(fourHour);
        return r;
    }

    @Test
    void high_correctionFromLanding_roundedDown() {
        when(calculator.calculateForecastCorrection(USER.toString(), 6.0, 11.0, 5.55, 2.0)).thenReturn(2.75);
        WatchStrip strip = service.evaluate(USER, high(10.5, 11.0)).orElseThrow();
        assertThat(strip.state()).isEqualTo(State.URGENT_HIGH);
        assertThat(strip.reason()).isEqualTo(Reason.HIGH_FORECAST);
        assertThat(strip.suggestion().kind()).isEqualTo(Kind.INSULIN);
        assertThat(strip.suggestion().units()).isEqualTo(2.5);
        assertThat(strip.suggestion().targetMmol()).isEqualTo(5.55);
        assertThat(strip.suggestion().isf()).isEqualTo(2.0);
        assertThat(strip.landing()).isEqualTo(new WatchStrip.GlucosePoint(NOW.plusMinutes(240), 11.0));
        assertThat(strip.nextCheckAt()).isEqualTo(NOW.plusMinutes(60));
    }

    @Test
    void high_doseWithin2h_insulinLanding() {
        notesAre(note(0, 3.0, Note.TYPE_NORMAL, 100));
        WatchStrip strip = service.evaluate(USER, high(10.5, 11.0)).orElseThrow();
        assertThat(strip.state()).isEqualTo(State.URGENT_HIGH);
        assertThat(strip.reason()).isEqualTo(Reason.INSULIN_LANDING);
        assertThat(strip.suggestion().kind()).isEqualTo(Kind.NO_ACTION);
        assertThat(strip.landing().mmol()).isEqualTo(11.0);
        assertThat(strip.nextCheckAt()).isEqualTo(NOW.plusMinutes(60));
    }

    @Test
    void high_under05_smallRise() {
        when(calculator.calculateForecastCorrection(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(0.49);
        WatchStrip strip = service.evaluate(USER, high(10.5, 10.6)).orElseThrow();
        assertThat(strip.reason()).isEqualTo(Reason.SMALL_RISE);
        assertThat(strip.suggestion().kind()).isEqualTo(Kind.NO_ACTION);
    }

    @Test
    void high_refusal_surfacesCode() {
        when(calculator.calculateForecastCorrection(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenThrow(new DosingRefusedException(DosingRefusalReason.DOSE_EXCEEDS_MAX_BOLUS, "too big"));
        WatchStrip strip = service.evaluate(USER, high(12.0, 16.0)).orElseThrow();
        assertThat(strip.state()).isEqualTo(State.URGENT_HIGH);
        assertThat(strip.reason()).isEqualTo(Reason.HIGH_FORECAST);
        assertThat(strip.suggestion().kind()).isEqualTo(Kind.REFUSED);
        assertThat(strip.suggestion().refusalCode()).isEqualTo("DOSE_EXCEEDS_MAX_BOLUS");
        assertThat(strip.suggestion().units()).isNull();
    }

    @Test
    void high_needsBoth2hAnd4hAbove10() {
        assertThat(service.evaluate(USER, high(10.5, 9.0))).isEmpty();
    }

    @Test
    void high_usesStoredReadingForRefusalCheck() {
        reading(7.2);
        GlucoseCalculationsResponse r = high(10.5, 11.0);
        r.setCurrentGlucose(3.0);
        when(calculator.calculateForecastCorrection(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(1.0);
        service.evaluate(USER, r);
        verify(calculator).calculateForecastCorrection(eq(USER.toString()), eq(7.2), eq(11.0), anyDouble(), eq(2.0));
    }

    @Test
    void high_doseLoggedAheadOfClock_stillBlocksCorrection() {
        notesAre(note(0, 3.0, Note.TYPE_NORMAL, -5));
        assertThat(service.evaluate(USER, high(10.5, 11.0)).orElseThrow().reason()).isEqualTo(Reason.INSULIN_LANDING);
    }

    @Test
    void hold_calmSurvivesOneWeakerNewReading_thenDrops() {
        notesAre(note(0, 4.0, Note.TYPE_NORMAL, 60));
        readingStoredAt(6.0, 2);
        assertThat(service.evaluate(USER, resp(path(m -> 6.0), 1.5, 0.0)).orElseThrow().state()).isEqualTo(State.CALM);

        notesAre();
        readingStoredAt(6.0, -3);
        assertThat(service.evaluate(USER, flat()).orElseThrow().state()).isEqualTo(State.CALM);

        readingStoredAt(6.0, -8);
        assertThat(service.evaluate(USER, flat())).isEmpty();
    }

    @Test
    void hold_missingFreshReadingClearsIt() {
        notesAre(note(0, 4.0, Note.TYPE_NORMAL, 60));
        assertThat(service.evaluate(USER, resp(path(m -> 6.0), 1.5, 0.0))).isPresent();

        when(cgm.freshReading(eq(USER), eq(CLOCK_NOW))).thenReturn(Optional.empty());
        assertThat(service.evaluate(USER, flat())).isEmpty();

        notesAre();
        readingStoredAt(6.0, -3);
        assertThat(service.evaluate(USER, flat())).isEmpty();
    }

    @Test
    void hold_doseStripNotKeptAfterForecastDrops() {
        when(calculator.calculateForecastCorrection(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(2.75);
        assertThat(service.evaluate(USER, high(10.5, 11.0)).orElseThrow().suggestion().kind()).isEqualTo(Kind.INSULIN);
        readingStoredAt(6.0, -3);
        assertThat(service.evaluate(USER, flat())).isEmpty();
    }
}
