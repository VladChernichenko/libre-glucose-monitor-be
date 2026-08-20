package che.glucosemonitorbe.hovorka.learning;

import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.hovorka.BasalInsulinResolver;
import che.glucosemonitorbe.hovorka.DallaManGutModel;
import che.glucosemonitorbe.hovorka.HovorkaGlucosePredictionService;
import che.glucosemonitorbe.hovorka.HovorkaOdeSolver;
import che.glucosemonitorbe.hovorka.HovorkaParameters;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Plumbing test: the replay engine drives the real Hovorka ODE over a synthetic CGM trace and
 * produces finite, in-range comparison samples. Validates the engine ↔ predictor wiring end-to-end
 * (not numerical accuracy - that is the calibrator's concern).
 */
class PredictionReplayEngineTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000ab");
    private static final long T0 = 1_700_000_000_000L; // fixed epoch

    @Test
    void producesFiniteInRangeSamplesFromASyntheticTrace() {
        HovorkaGlucosePredictionService predictor = rawPredictor();
        HovorkaParameters params = params70kg();
        RapidInsulinIobParameters rapidIob = new RapidInsulinIobParameters(4.5, 55.0);

        // 8 h of CGM every 5 min, gently oscillating around 7 mmol/L.
        List<PredictionReplayEngine.Reading> cgm = new ArrayList<>();
        for (int m = 0; m <= 480; m += 5) {
            double mmol = 7.0 + 0.8 * Math.sin(m / 60.0);
            cgm.add(new PredictionReplayEngine.Reading(T0 + m * 60_000L, mmol));
        }
        // One 40 g meal + 5 U bolus at t0+60 min.
        List<PredictionReplayEngine.Event> events = List.of(
                new PredictionReplayEngine.Event(T0 + 60 * 60_000L, 40.0, 5.0, false, 10.0, 8.0, 3.0));

        PredictionReplayEngine engine = new PredictionReplayEngine(
                predictor, params, rapidIob, /*settings*/ null, USER, cgm, events,
                new PredictionReplayEngine.Config());

        assertThat(engine.anchorCount()).isGreaterThan(0);

        List<AnchorSample> samples = engine.replay(TwinScales.neutral());
        assertThat(samples).isNotEmpty();
        for (AnchorSample s : samples) {
            assertThat(s.predicted()).isFinite();
            assertThat(s.predicted()).isBetween(1.0, 25.0);
            assertThat(s.actual()).isFinite();
            assertThat(s.hourOfDay()).isBetween(0, 23);
        }
    }

    // ---
    // Timezone alignment: the calibrator replays the SAME predictor the live dashboard calls, and
    // the live path is fed the client's local wall time. Anchors must therefore be built on the
    // user's local clock, or the residual grid learns a UTC hour-of-day and the dashboard applies
    // it at a local hour-of-day - a phase shift equal to the user's UTC offset.
    // ---

    @Test
    void hourOfDayFollowsTheUsersLocalClockNotUtc() {
        List<PredictionReplayEngine.Reading> cgm = syntheticTrace();
        List<PredictionReplayEngine.Event> events = List.of(
                new PredictionReplayEngine.Event(T0 + 60 * 60_000L, 40.0, 5.0, false, 10.0, 8.0, 3.0));

        List<AnchorSample> utc = new PredictionReplayEngine(
                rawPredictor(), params70kg(), new RapidInsulinIobParameters(4.5, 55.0), null,
                USER, cgm, events, new PredictionReplayEngine.Config(), ZoneOffset.UTC)
                .replay(TwinScales.neutral());

        List<AnchorSample> plus4 = new PredictionReplayEngine(
                rawPredictor(), params70kg(), new RapidInsulinIobParameters(4.5, 55.0), null,
                USER, cgm, events, new PredictionReplayEngine.Config(), ZoneOffset.ofHours(4))
                .replay(TwinScales.neutral());

        assertThat(utc).isNotEmpty();
        assertThat(plus4).hasSameSizeAs(utc);
        for (int i = 0; i < utc.size(); i++) {
            int expected = (utc.get(i).hourOfDay() + 4) % 24;
            assertThat(plus4.get(i).hourOfDay())
                    .as("sample %d: a UTC+4 user's grid bucket must sit 4 hours from the UTC one", i)
                    .isEqualTo(expected);
        }
    }

    @Test
    void noteWallTimeConvertsToTheInstantItActuallyHappened() {
        // A note stored as local wall time 12:00 for a UTC+4 user happened at 08:00 UTC.
        // Treating that wall time as UTC - which the calibrator did - places the meal four hours
        // after the glucose response it caused, so the fit sees a rise with no carbs and carbs
        // with no rise.
        LocalDateTime storedWallTime = LocalDateTime.of(2026, 8, 20, 12, 0);

        long epoch = PredictionReplayEngine.toEpochMs(storedWallTime, ZoneOffset.ofHours(4));

        assertThat(epoch).isEqualTo(
                LocalDateTime.of(2026, 8, 20, 8, 0).toInstant(ZoneOffset.UTC).toEpochMilli());
        // and the round trip returns the wall time the user actually saw
        assertThat(PredictionReplayEngine.toLdt(epoch, ZoneOffset.ofHours(4)))
                .isEqualTo(storedWallTime);
    }

    @Test
    void wallTimeConversionFollowsDstWithinTheCalibrationWindow() {
        // LOOKBACK_DAYS = 30, so every fit twice a year spans a DST transition. A stored fixed
        // offset would convert one side of it an hour wrong; a zone id resolves the offset per
        // instant. Europe/Berlin moved CET->CEST at 02:00 on 2026-03-29.
        ZoneId berlin = ZoneId.of("Europe/Berlin");

        LocalDateTime beforeDst = LocalDateTime.of(2026, 3, 28, 12, 0);  // CET  (UTC+1)
        LocalDateTime afterDst  = LocalDateTime.of(2026, 3, 30, 12, 0);  // CEST (UTC+2)

        assertThat(PredictionReplayEngine.toEpochMs(beforeDst, berlin)).isEqualTo(
                LocalDateTime.of(2026, 3, 28, 11, 0).toInstant(ZoneOffset.UTC).toEpochMilli());
        assertThat(PredictionReplayEngine.toEpochMs(afterDst, berlin)).isEqualTo(
                LocalDateTime.of(2026, 3, 30, 10, 0).toInstant(ZoneOffset.UTC).toEpochMilli());

        // and both round-trip to the wall time the user actually saw
        assertThat(PredictionReplayEngine.toLdt(
                PredictionReplayEngine.toEpochMs(beforeDst, berlin), berlin)).isEqualTo(beforeDst);
        assertThat(PredictionReplayEngine.toLdt(
                PredictionReplayEngine.toEpochMs(afterDst, berlin), berlin)).isEqualTo(afterDst);
    }

    private static List<PredictionReplayEngine.Reading> syntheticTrace() {
        List<PredictionReplayEngine.Reading> cgm = new ArrayList<>();
        for (int m = 0; m <= 480; m += 5) {
            cgm.add(new PredictionReplayEngine.Reading(T0 + m * 60_000L, 7.0 + 0.8 * Math.sin(m / 60.0)));
        }
        return cgm;
    }

    private static HovorkaGlucosePredictionService rawPredictor() {
        DallaManGutModel gut = new DallaManGutModel();
        HovorkaOdeSolver solver = new HovorkaOdeSolver(gut);
        BasalInsulinResolver basal = new BasalInsulinResolver();
        // The pre-fetched overload used by the engine never touches these collaborators.
        return new HovorkaGlucosePredictionService(
                mock(che.glucosemonitorbe.hovorka.HovorkaParameterService.class),
                solver, basal,
                mock(che.glucosemonitorbe.service.UserInsulinPreferencesService.class),
                gut,
                mock(che.glucosemonitorbe.service.UserSettingsService.class),
                PredictionResidualProvider.NONE);
    }

    private static HovorkaParameters params70kg() {
        double w = 70.0;
        double vG = HovorkaParameters.VG_PER_KG * w;
        double f01 = HovorkaParameters.F01_PER_KG * w;
        double egp0 = HovorkaParameters.EGP0_PER_KG * w;
        return new HovorkaParameters(vG, f01, f01, egp0,
                HovorkaParameters.K12_POP, HovorkaParameters.K21_POP,
                45.0 / 1.68, 1.0, 2.2, w);
    }
}
