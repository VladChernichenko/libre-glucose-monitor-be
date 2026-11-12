package che.glucosemonitorbe.hovorka.learning;

import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.hovorka.ActivityProvider;
import che.glucosemonitorbe.hovorka.ProteinGluconeogenesis;
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

    // ---
    // Rescue-carb marker. `Event` is the ONLY thing the replay engine ever sees - it never gets
    // the Note - so if the rescue flag does not travel on it, the nightly twin fit models a 15 g
    // dextrose tablet on the user's 240-minute mixed-meal curve, meets a sharp real rise it cannot
    // explain, and absorbs the mismatch into agScale/isfScale, which then bias every prediction
    // for that user. The spec's promise that a rescue "cannot corrupt the parameter-titration
    // loops" is exactly this path.
    // ---

    @Test
    void aRescueEventAbsorbsFasterThanTheSameCarbsLoggedAsAMeal() {
        // A short window with a single anchor at t0, and the carbs landing 15 min into its horizon
        // so the 30-minute sample lands while both curves are still delivering.
        List<PredictionReplayEngine.Reading> cgm = new ArrayList<>();
        for (int m = 0; m <= 120; m += 5) {
            cgm.add(new PredictionReplayEngine.Reading(T0 + m * 60_000L, 4.0));
        }
        long ingestedAt = T0 + 15 * 60_000L;

        List<AnchorSample> asMeal = replayWith(cgm,
                new PredictionReplayEngine.Event(ingestedAt, 15.0, 0.0, false, 0, 0, 0, false));
        List<AnchorSample> asRescue = replayWith(cgm,
                new PredictionReplayEngine.Event(ingestedAt, 15.0, 0.0, false, 0, 0, 0, true));

        assertThat(asMeal).isNotEmpty();
        assertThat(asRescue).hasSameSizeAs(asMeal);

        double mealAt30   = predictedAt(asMeal, 30);
        double rescueAt30 = predictedAt(asRescue, 30);
        assertThat(rescueAt30)
                .as("15 g on the rescue curve (tMaxG ~8.9 min) must have delivered more glucose "
                    + "15 min after ingestion than the same 15 g on the user's own curve "
                    + "(tMaxG ~26.8 min). Equal values mean Event.rescue() never reached the gut "
                    + "model and the twin is fitting a rescue as a mixed meal.")
                .isGreaterThan(mealAt30);
    }

    /** The flag must discriminate, not merely perturb: two unflagged replays are bit-identical. */
    @Test
    void twoUnflaggedReplaysAreIdenticalSoTheRescueDifferenceIsTheFlag() {
        List<PredictionReplayEngine.Reading> cgm = new ArrayList<>();
        for (int m = 0; m <= 120; m += 5) {
            cgm.add(new PredictionReplayEngine.Reading(T0 + m * 60_000L, 4.0));
        }
        long ingestedAt = T0 + 15 * 60_000L;

        List<AnchorSample> a = replayWith(cgm,
                new PredictionReplayEngine.Event(ingestedAt, 15.0, 0.0, false, 0, 0, 0, false));
        // The 7-arg convenience constructor must mean "not a rescue", not "unspecified".
        List<AnchorSample> b = replayWith(cgm,
                new PredictionReplayEngine.Event(ingestedAt, 15.0, 0.0, false, 0, 0, 0));

        assertThat(a).hasSameSizeAs(b);
        for (int i = 0; i < a.size(); i++) {
            assertThat(b.get(i).predicted()).isEqualTo(a.get(i).predicted());
        }
    }

    // ---
    // Calibration must fit the model it will be applied to. Three ways it once did not: the ISF
    // scale never reached a replayed dose, the fit differentiated predictions already rounded for
    // display, and the replay built its meals differently from the dashboard.
    // ---

    @Test
    void anIsfScaleMovesAReplayedBolusWhenTheSettingsCarryAnIsf() {
        // settings.isf is what a dose's ISF resolves to for almost every user. A scale applied only
        // to the parameter fallback is invisible here, and the fit then cannot learn sensitivity.
        UserSettingsDTO settings = new UserSettingsDTO();
        settings.setIsf(2.0);
        List<PredictionReplayEngine.Reading> cgm = flatTrace(8.0, 240);
        List<PredictionReplayEngine.Event> bolus = List.of(
                new PredictionReplayEngine.Event(T0, 0.0, 3.0, false, 0, 0, 0));
        PredictionReplayEngine engine = new PredictionReplayEngine(
                rawPredictor(), params70kg(), new RapidInsulinIobParameters(4.5, 55.0), settings,
                USER, cgm, bolus, new PredictionReplayEngine.Config(), ZoneOffset.UTC);

        double neutral = predictedAt(engine.replay(TwinScales.of(1.0, 1.0)), 120);
        double scaled  = predictedAt(engine.replay(TwinScales.of(1.5, 1.0)), 120);

        assertThat(scaled)
                .as("a 1.5x ISF scale must deepen the fall from 3 U; equal values mean the scale "
                    + "never reached the dose")
                .isLessThan(neutral - 0.5);
    }

    @Test
    void replayedPredictionsKeepTheirPrecisionForTheFiniteDifferenceStep() {
        List<PredictionReplayEngine.Reading> cgm = flatTrace(6.0, 240);
        List<PredictionReplayEngine.Event> meal = List.of(
                new PredictionReplayEngine.Event(T0 + 15 * 60_000L, 40.0, 0.0, false, 0, 0, 0));
        PredictionReplayEngine engine = new PredictionReplayEngine(
                rawPredictor(), params70kg(), new RapidInsulinIobParameters(4.5, 55.0), null,
                USER, cgm, meal, new PredictionReplayEngine.Config(), ZoneOffset.UTC);

        // 1e-3 is the optimiser's own relative step on a scale of 1.0.
        double base    = predictedAt(engine.replay(TwinScales.of(1.0, 1.0)), 120);
        double stepped = predictedAt(engine.replay(TwinScales.of(1.0, 1.001)), 120);

        assertThat(stepped - base)
                .as("a 0.1%% larger meal must raise the prediction by a small, non-zero amount; "
                    + "0 means the sample was rounded and the Jacobian is quantisation noise")
                .isGreaterThan(0.0)
                .isLessThan(0.05);
    }

    @Test
    void aMixedMealIsReplayedOnTheSameEntriesTheDashboardBuilds() {
        long mealAt = T0 + 15 * 60_000L;
        List<PredictionReplayEngine.Reading> cgm = flatTrace(6.0, 240);
        List<PredictionReplayEngine.Event> meal = List.of(new PredictionReplayEngine.Event(
                mealAt, 40.0, 0.0, false, /*protein*/ 30.0, /*fat*/ 30.0, /*fiber*/ 5.0, false, /*gi*/ 40.0));
        HovorkaGlucosePredictionService predictor = rawPredictor();
        RapidInsulinIobParameters rapidIob = new RapidInsulinIobParameters(4.5, 55.0);

        List<AnchorSample> replayed = new PredictionReplayEngine(
                predictor, params70kg(), rapidIob, null, USER, cgm, meal,
                new PredictionReplayEngine.Config(), ZoneOffset.UTC).replay(TwinScales.neutral());

        // The dashboard's own construction: the meal with its GI and macros, plus the delayed
        // protein glucose - and nothing for fat, which acts through the ileal brake.
        CarbsEntry entry = CarbsEntry.builder()
                .timestamp(PredictionReplayEngine.toLdt(mealAt, ZoneOffset.UTC)).carbs(40.0).build();
        entry.setEstimatedGi(40.0);
        entry.setProtein(30.0);
        entry.setFat(30.0);
        entry.setFiber(5.0);
        List<PredictionPointDTO> live = predictor.buildPredictionPath(
                params70kg(), rapidIob, null, 6.0, PredictionReplayEngine.toLdt(T0, ZoneOffset.UTC),
                ProteinGluconeogenesis.withDelayedProteinGlucose(List.of(entry)), List.of(), List.of(),
                USER, 120, ActivityProvider.NONE, BasalInsulinResolver.DEFAULT_DURATION_HOURS);

        for (int horizon : new int[]{30, 60, 90, 120}) {
            double expected = live.stream()
                    .filter(pt -> pt.getTimestamp().equals(
                            PredictionReplayEngine.toLdt(T0 + horizon * 60_000L, ZoneOffset.UTC)))
                    .findFirst().orElseThrow().getRawPredictedGlucose();
            assertThat(predictedAt(replayed, horizon))
                    .as("horizon %d min", horizon)
                    .isEqualTo(expected);
        }
    }

    private static List<PredictionReplayEngine.Reading> flatTrace(double mmol, int minutes) {
        List<PredictionReplayEngine.Reading> cgm = new ArrayList<>();
        for (int m = 0; m <= minutes; m += 5) {
            cgm.add(new PredictionReplayEngine.Reading(T0 + m * 60_000L, mmol));
        }
        return cgm;
    }

    private static List<AnchorSample> replayWith(
            List<PredictionReplayEngine.Reading> cgm, PredictionReplayEngine.Event event) {
        return new PredictionReplayEngine(
                rawPredictor(), params70kg(), new RapidInsulinIobParameters(4.5, 55.0), null,
                USER, cgm, List.of(event), new PredictionReplayEngine.Config(), ZoneOffset.UTC)
                .replay(TwinScales.neutral());
    }

    private static double predictedAt(List<AnchorSample> samples, int horizonMin) {
        return samples.stream()
                .filter(s -> s.horizonMin() == horizonMin)
                .mapToDouble(AnchorSample::predicted)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no sample at horizon " + horizonMin));
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
