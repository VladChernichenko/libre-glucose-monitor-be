package che.glucosemonitorbe.empirical;

import che.glucosemonitorbe.domain.ActivityIntensity;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.hovorka.ActivityProvider;
import che.glucosemonitorbe.hovorka.BasalInsulinResolver;
import che.glucosemonitorbe.hovorka.DallaManGutModel;
import che.glucosemonitorbe.hovorka.HovorkaGlucosePredictionService;
import che.glucosemonitorbe.hovorka.HovorkaOdeSolver;
import che.glucosemonitorbe.hovorka.HovorkaParameterService;
import che.glucosemonitorbe.hovorka.HovorkaParameters;
import che.glucosemonitorbe.hovorka.learning.AnchorSample;
import che.glucosemonitorbe.hovorka.learning.PredictionReplayEngine;
import che.glucosemonitorbe.hovorka.learning.PredictionResidualProvider;
import che.glucosemonitorbe.hovorka.learning.TwinScales;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import che.glucosemonitorbe.service.UserSettingsService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Side-by-side benchmark of the two prediction engines on the bundled fixtures: Hovorka (through
 * {@link PredictionReplayEngine} with neutral {@link TwinScales}) and the empirical twin, over the
 * same anchors, scored with the same metric - forecast vs interpolated CGM at +60 and +120 min,
 * hit = within max(20 mg/dL, 20% of actual).
 *
 * <p>It reports; it does not judge. Nothing here asserts that either engine wins.</p>
 *
 * <h3>What each engine is given</h3>
 * <p>Both start from the same glucose at the anchor and see only what was logged up to
 * anchor + 20 min. Hovorka is replayed one anchor at a time so that it is scored at exactly the
 * empirical anchors rather than on the replay engine's own strided grid.</p>
 *
 * <h3>Fixture -> Hovorka event mapping</h3>
 * <ul>
 *   <li>{@code ins} -> bolus; {@code basal} -> long-acting dose.</li>
 *   <li>{@code meal} -> carbs. The fixture's {@code fat} is a yes/no flag, not grams, and it has no
 *       protein or fibre, so those macros are 0; {@code fruit} and {@code gi} have no Hovorka
 *       counterpart.</li>
 *   <li>{@code fast} -> carbs on the rescue (fast-acting) curve.</li>
 *   <li>{@code walk} -> {@link ActivityIntensity#LOW} activity for its duration.</li>
 *   <li>{@code alc}, {@code heat} -> not modelled by Hovorka.</li>
 * </ul>
 * Hovorka runs on population parameters for a 70 kg adult with the ISF (1.5 mmol/L per U) and
 * rapid-insulin curve (4 h, peak 55 min) the fixtures were logged under.
 */
@Tag("benchmark")
class EngineBenchmarkTest {

    private static final int[] HORIZONS_MIN = {60, 120};
    /** Every fixture timestamp carries this offset. */
    private static final ZoneOffset USER_ZONE = ZoneOffset.ofHours(4);
    private static final long MIN_MS = 60_000L;
    private static final long KNOWN_AHEAD_MS = 20 * MIN_MS;

    /** Running score of one engine at one horizon. */
    private static final class Score {
        int checks;
        int hits;
        double absErrMgdl;

        void add(double predictedMmol, double actualMmol) {
            checks++;
            if (EmpiricalTwinEngine.isHit(predictedMmol, actualMmol)) hits++;
            absErrMgdl += Math.abs(predictedMmol - actualMmol) * EmpiricalTwinEngine.MGDL_PER_MMOL;
        }

        double hitPct() {
            return 100.0 * hits / checks;
        }

        double mae() {
            return absErrMgdl / checks;
        }
    }

    @Test
    void scoresBothEnginesOnTheSameAnchors() {
        TwinFixtures.assumePresent();
        List<PredictionReplayEngine.Reading> readings = TwinFixtures.readings();
        List<EmpiricalEvent> events = TwinFixtures.events();
        EmpiricalTwinParams params = EmpiricalTwinParams.defaults();
        HovorkaGlucosePredictionService hovorka = rawPredictor();

        Score[] empirical = {new Score(), new Score()};
        Score[] hovorkaScore = {new Score(), new Score()};
        List<Long> anchors = EmpiricalTwinEngine.anchors(events);

        for (long t0 : anchors) {
            List<EmpiricalTwinEngine.Point> forecast = EmpiricalTwinEngine.forecast(t0, readings, events, params);
            if (forecast.isEmpty()) continue;
            double g0 = forecast.get(0).mmol();

            Double[] actual = new Double[HORIZONS_MIN.length];
            for (int i = 0; i < HORIZONS_MIN.length; i++) {
                actual[i] = EmpiricalTwinEngine.actualAt(readings, t0 + HORIZONS_MIN[i] * MIN_MS);
                if (actual[i] != null) {
                    empirical[i].add(forecast.get(HORIZONS_MIN[i] / EmpiricalTwinEngine.STEP_MIN).mmol(), actual[i]);
                }
            }
            for (AnchorSample s : hovorkaSamples(hovorka, t0, g0, actual, events)) {
                hovorkaScore[s.horizonMin() == HORIZONS_MIN[0] ? 0 : 1].add(s.predicted(), s.actual());
            }
        }

        System.out.println(render(readings.size(), events.size(), anchors.size(), hovorkaScore, empirical));

        for (int i = 0; i < HORIZONS_MIN.length; i++) {
            assertThat(empirical[i].checks).as("empirical checks at +%d", HORIZONS_MIN[i]).isPositive();
            assertThat(empirical[i].mae()).isFinite();
            assertThat(hovorkaScore[i].checks)
                    .as("Hovorka must be scored on the same checks at +%d", HORIZONS_MIN[i])
                    .isEqualTo(empirical[i].checks);
            assertThat(hovorkaScore[i].mae()).isFinite();
        }
    }

    /**
     * Replays Hovorka from a single anchor. The replay engine places anchors on a strided grid that
     * starts at the first reading and matches actuals to the nearest reading, so it is handed a
     * trace made of exactly this anchor's start value and its interpolated actuals: one anchor, at
     * {@code t0}, compared against the same actuals the empirical engine is.
     */
    private static List<AnchorSample> hovorkaSamples(HovorkaGlucosePredictionService predictor, long t0,
                                                     double g0, Double[] actual, List<EmpiricalEvent> events) {
        List<PredictionReplayEngine.Reading> trace = new ArrayList<>();
        trace.add(new PredictionReplayEngine.Reading(t0, g0));
        for (int i = 0; i < HORIZONS_MIN.length; i++) {
            if (actual[i] != null) trace.add(new PredictionReplayEngine.Reading(t0 + HORIZONS_MIN[i] * MIN_MS, actual[i]));
        }
        // Closes the window so the horizon fits; outside the alignment tolerance of every check.
        trace.add(new PredictionReplayEngine.Reading(t0 + EmpiricalTwinEngine.HORIZON_MIN * MIN_MS, g0));

        PredictionReplayEngine.Config cfg = new PredictionReplayEngine.Config();
        cfg.horizonMin = HORIZONS_MIN[HORIZONS_MIN.length - 1];
        cfg.strideMin = EmpiricalTwinEngine.HORIZON_MIN; // one candidate anchor: t0
        cfg.sampleHorizons = HORIZONS_MIN;

        List<PredictionReplayEngine.Event> known = new ArrayList<>();
        List<EmpiricalEvent> walks = new ArrayList<>();
        for (EmpiricalEvent e : events) {
            if (e.epochMs() > t0 + KNOWN_AHEAD_MS) continue;
            switch (e.type()) {
                case INSULIN -> known.add(new PredictionReplayEngine.Event(e.epochMs(), 0, e.amount(), false, 0, 0, 0));
                case BASAL -> known.add(new PredictionReplayEngine.Event(e.epochMs(), 0, e.amount(), true, 0, 0, 0));
                case MEAL -> known.add(new PredictionReplayEngine.Event(e.epochMs(), e.amount(), 0, false, 0, 0, 0));
                case FAST -> known.add(new PredictionReplayEngine.Event(e.epochMs(), e.amount(), 0, false, 0, 0, 0, true));
                case WALK -> walks.add(e);
                case ALCOHOL, HEAT -> { }
            }
        }
        ActivityProvider activity = walks.isEmpty() ? ActivityProvider.NONE : time -> {
            long t = PredictionReplayEngine.toEpochMs(time, USER_ZONE);
            for (EmpiricalEvent w : walks) {
                if (t >= w.epochMs() && t < w.endEpochMs()) return ActivityIntensity.LOW.aLevel();
            }
            return 0.0;
        };

        return new PredictionReplayEngine(predictor, vladParams(), new RapidInsulinIobParameters(4.0, 55.0),
                /*settings*/ null, UUID.fromString("00000000-0000-0000-0000-0000000000ab"),
                trace, known, cfg, activity, USER_ZONE)
                .replay(TwinScales.neutral());
    }

    private static String render(int readings, int events, int anchors, Score[] hovorka, Score[] empirical) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%nEngine benchmark: %d readings, %d events, %d anchors%n", readings, events, anchors));
        sb.append(String.format("%-10s %-8s %7s %6s %7s %12s%n", "engine", "horizon", "checks", "hits", "hit %", "MAE mg/dL"));
        row(sb, "Hovorka", hovorka);
        row(sb, "Empirical", empirical);
        sb.append("hit = |forecast - CGM| <= max(20 mg/dL, 20% of CGM)");
        return sb.toString();
    }

    private static void row(StringBuilder sb, String engine, Score[] byHorizon) {
        Score pooled = new Score();
        for (int i = 0; i < byHorizon.length; i++) {
            Score s = byHorizon[i];
            sb.append(line(engine, "+" + HORIZONS_MIN[i] + " min", s));
            pooled.checks += s.checks;
            pooled.hits += s.hits;
            pooled.absErrMgdl += s.absErrMgdl;
        }
        sb.append(line(engine, "pooled", pooled));
    }

    private static String line(String engine, String horizon, Score s) {
        return String.format("%-10s %-8s %7d %6d %6.0f%% %12.1f%n",
                engine, horizon, s.checks, s.hits, s.hitPct(), s.mae());
    }

    private static HovorkaGlucosePredictionService rawPredictor() {
        DallaManGutModel gut = new DallaManGutModel();
        // The pre-fetched overload used by the replay engine never touches the mocked collaborators.
        return new HovorkaGlucosePredictionService(
                mock(HovorkaParameterService.class),
                new HovorkaOdeSolver(gut), new BasalInsulinResolver(),
                mock(UserInsulinPreferencesService.class),
                gut,
                mock(UserSettingsService.class),
                PredictionResidualProvider.NONE);
    }

    private static HovorkaParameters vladParams() {
        double w = HovorkaParameters.DEFAULT_WEIGHT;
        double f01 = HovorkaParameters.F01_PER_KG * w;
        return new HovorkaParameters(HovorkaParameters.VG_PER_KG * w, f01, f01, HovorkaParameters.EGP0_PER_KG * w,
                HovorkaParameters.K12_POP, HovorkaParameters.K21_POP,
                45.0 / HovorkaParameterService.HALF_LIFE_TO_TMAX_G, 1.0, 1.5, w);
    }
}
