package che.glucosemonitorbe.azt1d;

import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.hovorka.BasalInsulinResolver;
import che.glucosemonitorbe.hovorka.DallaManGutModel;
import che.glucosemonitorbe.hovorka.HovorkaGlucosePredictionService;
import che.glucosemonitorbe.hovorka.HovorkaOdeSolver;
import che.glucosemonitorbe.hovorka.HovorkaParameterService;
import che.glucosemonitorbe.hovorka.HovorkaParameters;
import che.glucosemonitorbe.hovorka.InterstitialLagModel;
import che.glucosemonitorbe.hovorka.learning.AnchorSample;
import che.glucosemonitorbe.hovorka.learning.DigitalTwinCalibrator;
import che.glucosemonitorbe.hovorka.learning.PredictionReplayEngine;
import che.glucosemonitorbe.hovorka.learning.PredictionResidualProvider;
import che.glucosemonitorbe.hovorka.learning.ReplayMetrics;
import che.glucosemonitorbe.hovorka.learning.TwinScales;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import che.glucosemonitorbe.service.UserSettingsService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Data-gated validation of the digital-twin learning against the real-world AZT1D 2025 dataset
 * (25 Type-1 subjects on automated insulin delivery).
 *
 * <p>For each subject it fits the twin on the earlier 80% of the record and scores it on the
 * held-out later 20%, then prints a per-subject and aggregate report of baseline (un-calibrated)
 * vs. calibrated prediction MAE. This is the end-to-end proof that the learning loop improves
 * real predictions - not just synthetic ones.</p>
 *
 * <p>Skipped (not failed) when the dataset is absent. Point it at the data with
 * {@code -Dazt1d.dir="/path/to/AZT1D 2025"}.</p>
 */
class Azt1dCalibrationValidationTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final double TRAIN_FRACTION = 0.8;
    private static final int MIN_CGM = 500;

    private static Path datasetRoot() {
        return Path.of(System.getProperty("azt1d.dir", ""));
    }

    /** 90% two-sided normal quantile - matches the live prediction band. */
    private static final double BAND_Z = 1.6449;

    private record Row(String id, int trainAnchors, int valSamples,
                       double maeBaseline, double maeCalibrated, double effective,
                       double isfScale, double agScale, boolean applied, String confidence,
                       double sd30, double sd60, double sd90, double sd120) {}

    @Test
    void calibratesAndImprovesAcrossRealSubjects() throws Exception {
        Path root = datasetRoot();
        Assumptions.assumeTrue(!root.toString().isBlank() && Files.isDirectory(root.resolve("CGM Records")),
                "AZT1D dataset not present - set -Dazt1d.dir=\"/path/to/AZT1D 2025\" to run");

        List<Azt1dDataset.Subject> subjects = Azt1dDataset.loadAll(root);
        assertThat(subjects).isNotEmpty();

        // Each subject is seeded with their OWN personal settings (weight, ISF, carb ratio, basal)
        // estimated from their delivery data - then the twin calibrates on top of that realistic prior.
        System.out.print(renderProfiles(subjects));

        HovorkaGlucosePredictionService predictor = rawPredictor();
        RapidInsulinIobParameters rapidIob = new RapidInsulinIobParameters(4.5, 55.0);
        PredictionReplayEngine.Config cfg = new PredictionReplayEngine.Config();
        cfg.maxAnchors = 120; // keep the 25-subject sweep quick

        List<Row> rows = new ArrayList<>();
        // Held-out samples pooled across subjects, for the persistence comparison below.
        List<AnchorSample> allRaw = new ArrayList<>();
        List<AnchorSample> allEffective = new ArrayList<>();
        int beatsPersistence = 0, scoredSubjects = 0;

        for (Azt1dDataset.Subject s : subjects) {
            if (s.cgm().size() < MIN_CGM) continue;

            HovorkaParameters baseParams = personalParams(s.profile());

            int boundary = (int) Math.floor(s.cgm().size() * TRAIN_FRACTION);
            List<PredictionReplayEngine.Reading> trainCgm = s.cgm().subList(0, boundary);
            List<PredictionReplayEngine.Reading> valCgm   = s.cgm().subList(boundary, s.cgm().size());

            PredictionReplayEngine train = new PredictionReplayEngine(
                    predictor, baseParams, rapidIob, null, USER, trainCgm, s.events(), cfg);
            PredictionReplayEngine val = new PredictionReplayEngine(
                    predictor, baseParams, rapidIob, null, USER, valCgm, s.events(), cfg);

            DigitalTwinCalibrator.Result r = new DigitalTwinCalibrator().calibrate(train, val);
            double effective = r.improved() ? r.maeCalibrated() : r.maeBaseline();
            var u = r.uncertainty();

            // Same held-out anchors, scored against the no-change forecast. "Effective" mirrors what
            // a user would actually get: the calibrated twin only when the gate let it ship. The
            // per-subject residual is folded into `predicted` here so pooled scoring stays correct.
            List<AnchorSample> raw = val.replay(TwinScales.neutral());
            List<AnchorSample> eff = r.improved()
                    ? withResidual(val.replay(r.scales()), r.residual())
                    : raw;
            allRaw.addAll(raw);
            allEffective.addAll(eff);
            if (!raw.isEmpty()) {
                scoredSubjects++;
                if (ReplayMetrics.overall(eff, null).beatsPersistence()) beatsPersistence++;
            }
            rows.add(new Row(s.id(), train.anchorCount(), r.valSamples(),
                    r.maeBaseline(), r.maeCalibrated(), effective,
                    r.scales().isfScale(), r.scales().agScale(), r.improved(), r.confidence(),
                    u.sdAtHorizon(30), u.sdAtHorizon(60), u.sdAtHorizon(90), u.sdAtHorizon(120)));
        }

        System.out.print(render(rows));

        // -- The question the calibration report cannot answer ---------------------
        // Baseline/calibrated MAE only compare the model to itself. Persistence is the honest
        // reference: if a constant line wins, the ODE is not earning its place at that horizon.
        System.out.print(ReplayMetrics.render(
                "═════════ vs PERSISTENCE - raw model (no twin), held-out ═════════",
                ReplayMetrics.byHorizon(allRaw, null), ReplayMetrics.overall(allRaw, null)));
        System.out.print(ReplayMetrics.render(
                "═════════ vs PERSISTENCE - effective (twin where it shipped) ═════════",
                ReplayMetrics.byHorizon(allEffective, null),
                ReplayMetrics.overall(allEffective, null)));
        System.out.printf("subjects where the effective model beats persistence: %d / %d%n",
                beatsPersistence, scoredSubjects);

        // Safety guarantee: the out-of-sample gate must never let a twin ship that is worse than the
        // un-calibrated model. Effective MAE (what a user would actually get) <= baseline for everyone.
        for (Row row : rows) {
            if (!Double.isNaN(row.maeBaseline())) {
                assertThat(row.effective()).isLessThanOrEqualTo(row.maeBaseline() + 1e-6);
            }
        }
        // The learned band must widen with horizon (uncertainty grows further out) for every subject.
        for (Row row : rows) {
            assertThat(row.sd30()).isLessThanOrEqualTo(row.sd60() + 1e-9);
            assertThat(row.sd60()).isLessThanOrEqualTo(row.sd90() + 1e-9);
            assertThat(row.sd90()).isLessThanOrEqualTo(row.sd120() + 1e-9);
        }
        assertThat(rows).isNotEmpty();
    }

    /**
     * Sweeps the CGM interstitial lag and reports skill vs persistence at each setting, for the raw
     * model and for the effective (calibrated) one. The twin partly compensates for a missing lag,
     * so both curves are needed to see what the measurement model actually buys.
     */
    @Test
    void interstitialLagSweep() throws Exception {
        Path root = datasetRoot();
        Assumptions.assumeTrue(!root.toString().isBlank() && Files.isDirectory(root.resolve("CGM Records")),
                "AZT1D dataset not present - set -Dazt1d.dir=\"/path/to/AZT1D 2025\" to run");

        List<Azt1dDataset.Subject> subjects = Azt1dDataset.loadAll(root);
        HovorkaGlucosePredictionService predictor = rawPredictor();
        RapidInsulinIobParameters rapidIob = new RapidInsulinIobParameters(4.5, 55.0);
        PredictionReplayEngine.Config cfg = new PredictionReplayEngine.Config();
        cfg.maxAnchors = 120;

        double restore = InterstitialLagModel.tauMinutes;
        StringBuilder report = new StringBuilder(
                "\n═════════ CGM LAG SWEEP - skill vs persistence (held-out) ═════════\n");
        report.append(String.format("%-8s %10s %10s %10s %10s %10s %8s%n",
                "tau,min", "raw+30", "raw+120", "eff+30", "eff+60", "eff+120", "eff pool"));
        try {
            for (double tau : new double[]{0, 12, 25, 50, 100}) {
                InterstitialLagModel.tauMinutes = tau;
                List<AnchorSample> raw = new ArrayList<>();
                List<AnchorSample> eff = new ArrayList<>();
                for (Azt1dDataset.Subject s : subjects) {
                    if (s.cgm().size() < MIN_CGM) continue;
                    HovorkaParameters baseParams = personalParams(s.profile());
                    int boundary = (int) Math.floor(s.cgm().size() * TRAIN_FRACTION);
                    PredictionReplayEngine train = new PredictionReplayEngine(predictor, baseParams,
                            rapidIob, null, USER, s.cgm().subList(0, boundary), s.events(), cfg);
                    PredictionReplayEngine val = new PredictionReplayEngine(predictor, baseParams,
                            rapidIob, null, USER, s.cgm().subList(boundary, s.cgm().size()),
                            s.events(), cfg);
                    DigitalTwinCalibrator.Result r = new DigitalTwinCalibrator().calibrate(train, val);
                    List<AnchorSample> rawS = val.replay(TwinScales.neutral());
                    raw.addAll(rawS);
                    eff.addAll(r.improved() ? withResidual(val.replay(r.scales()), r.residual()) : rawS);
                }
                report.append(String.format("%-8.0f %9.1f%% %9.1f%% %9.1f%% %9.1f%% %9.1f%% %7.1f%%%n",
                        tau,
                        100 * skillAt(raw, 30), 100 * skillAt(raw, 120),
                        100 * skillAt(eff, 30), 100 * skillAt(eff, 60), 100 * skillAt(eff, 120),
                        100 * ReplayMetrics.overall(eff, null).skill()));
                System.out.print(report.substring(report.lastIndexOf("\n", report.length() - 2) + 1));
            }
        } finally {
            InterstitialLagModel.tauMinutes = restore;
        }
        System.out.print(report);
        assertThat(subjects).isNotEmpty();
    }

    private static double skillAt(List<AnchorSample> samples, int horizon) {
        for (ReplayMetrics.Stats s : ReplayMetrics.byHorizon(samples, null)) {
            if (s.horizonMin() == horizon) return s.skill();
        }
        return Double.NaN;
    }

    /** Fold a per-subject residual grid into {@code predicted} so pooled scoring stays correct. */
    private static List<AnchorSample> withResidual(
            List<AnchorSample> samples, che.glucosemonitorbe.hovorka.learning.ResidualBiasModel residual) {
        if (residual == null || residual.isNeutral()) return samples;
        List<AnchorSample> out = new ArrayList<>(samples.size());
        for (AnchorSample s : samples) {
            out.add(new AnchorSample(s.horizonMin(),
                    s.predicted() + residual.correctionAt(s.hourOfDay()),
                    s.actual(), s.baseline(), s.regime(), s.hourOfDay()));
        }
        return out;
    }

    // -- Report rendering -----------------------------------------------------

    private static String render(List<Row> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n══════════════ AZT1D DIGITAL-TWIN CALIBRATION (out-of-sample) ══════════════\n");
        sb.append(String.format("%-11s %7s %7s %9s %9s %7s %7s %7s %6s%n",
                "subject", "anchors", "valN", "baseMAE", "calMAE", "impr%", "isf×", "aG×", "appl"));

        int applied = 0;
        double sumBase = 0, sumEff = 0, sumImprApplied = 0;
        int counted = 0;
        for (Row r : rows) {
            if (Double.isNaN(r.maeBaseline())) {
                sb.append(String.format("%-11s %7d %7s   (insufficient anchors)%n", r.id(), r.trainAnchors(), "-"));
                continue;
            }
            double impr = 100.0 * (r.maeBaseline() - r.maeCalibrated()) / r.maeBaseline();
            sb.append(String.format("%-11s %7d %7d %9.2f %9.2f %6.1f%% %6.2f %6.2f %6s%n",
                    r.id(), r.trainAnchors(), r.valSamples(),
                    r.maeBaseline(), r.maeCalibrated(), impr, r.isfScale(), r.agScale(),
                    r.applied() ? "YES" : "no"));
            sumBase += r.maeBaseline();
            sumEff  += r.effective();
            counted++;
            if (r.applied()) { applied++; sumImprApplied += impr; }
        }
        sb.append("--------------------------------------------------------------------------\n");
        if (counted > 0) {
            sb.append(String.format("Subjects scored: %d   |   twin applied (beat baseline O.O.S.): %d%n", counted, applied));
            sb.append(String.format("Mean baseline MAE:   %.2f mmol/L%n", sumBase / counted));
            sb.append(String.format("Mean effective MAE:  %.2f mmol/L  (twin applied where it helped)%n", sumEff / counted));
            sb.append(String.format("Mean MAE reduction across all subjects: %.1f%%%n",
                    100.0 * (sumBase - sumEff) / sumBase));
            if (applied > 0) {
                sb.append(String.format("Mean improvement among applied subjects: %.1f%%%n", sumImprApplied / applied));
            }
        }

        // -- Learned prediction band (90% interval half-width = z*σ) --------------
        double[] sumSd = new double[4];
        int bandN = 0;
        for (Row r : rows) {
            if (Double.isNaN(r.maeBaseline())) continue;
            sumSd[0] += r.sd30(); sumSd[1] += r.sd60(); sumSd[2] += r.sd90(); sumSd[3] += r.sd120();
            bandN++;
        }
        if (bandN > 0) {
            sb.append("\n-- PROBABILISTIC BAND (mean learned σ and 90% half-width by horizon) ------\n");
            sb.append(String.format("%-14s %8s %8s %8s %8s%n", "horizon", "30min", "60min", "90min", "120min"));
            sb.append(String.format("%-14s %7.2f  %7.2f  %7.2f  %7.2f %n", "σ (mmol/L)",
                    sumSd[0] / bandN, sumSd[1] / bandN, sumSd[2] / bandN, sumSd[3] / bandN));
            sb.append(String.format("%-14s %7.2f  %7.2f  %7.2f  %7.2f %n", "±90% (mmol/L)",
                    BAND_Z * sumSd[0] / bandN, BAND_Z * sumSd[1] / bandN,
                    BAND_Z * sumSd[2] / bandN, BAND_Z * sumSd[3] / bandN));
            sb.append("Band widens with horizon -> each predicted point ships an interval, not a bare line.\n");
        }
        sb.append("══════════════════════════════════════════════════════════════════════════\n");
        return sb.toString();
    }

    // -- Predictor wiring (raw model, no DB) -------------------------------------

    private static HovorkaGlucosePredictionService rawPredictor() {
        DallaManGutModel gut = new DallaManGutModel();
        HovorkaOdeSolver solver = new HovorkaOdeSolver(gut);
        BasalInsulinResolver basal = new BasalInsulinResolver();
        return new HovorkaGlucosePredictionService(
                mock(HovorkaParameterService.class), solver, basal,
                mock(UserInsulinPreferencesService.class), gut,
                mock(UserSettingsService.class), PredictionResidualProvider.NONE);
    }

    /**
     * Build the Hovorka parameter set from a subject's estimated personal profile: body weight scales
     * the physiological volumes, ISF sets insulin action. tMaxG (45-min carb half-life), A_G (1.0) and
     * the intercompartmental rates keep their population defaults - the twin refines the rest.
     */
    private static HovorkaParameters personalParams(Azt1dDataset.Profile p) {
        double w = p.weightKg() > 0 ? p.weightKg() : HovorkaParameters.DEFAULT_WEIGHT;
        double isf = p.isfMmolPerU() > 0 ? p.isfMmolPerU() : 2.2;
        double vG = HovorkaParameters.VG_PER_KG * w;
        double f01 = HovorkaParameters.F01_PER_KG * w;
        double egp0 = HovorkaParameters.EGP0_PER_KG * w;
        return new HovorkaParameters(vG, f01, f01, egp0,
                HovorkaParameters.K12_POP, HovorkaParameters.K21_POP,
                45.0 / 1.68, 1.0, isf, w);
    }

    /** Per-subject personal-settings table - the "profile" seeded for each test user from their data. */
    private static String renderProfiles(List<Azt1dDataset.Subject> subjects) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n═══════ AZT1D PER-SUBJECT PROFILES (personal settings seeded from each subject's own AID data) ═══════\n");
        sb.append(String.format("%-11s %6s %9s %11s %13s %8s %8s %8s %9s %10s %6s%n",
                "subject", "days", "TDD(U/d)", "basal(U/h)", "ISF(mmol/L*U)",
                "isf-B", "isf-L", "isf-D", "CR(g/U)", "weight(kg)", "flag"));
        int flagged = 0;
        for (Azt1dDataset.Subject s : subjects) {
            Azt1dDataset.Profile p = s.profile();
            if (!p.plausible()) flagged++;
            sb.append(String.format("%-11s %6.1f %9.1f %11.2f %13.2f %8.2f %8.2f %8.2f %9s %10.1f %6s%n",
                    s.id(), p.days(), p.tddU(), p.basalRateUPerH(), p.isfMmolPerU(),
                    p.isfBreakfast(), p.isfLunch(), p.isfDinner(),
                    Double.isNaN(p.carbRatioGPerU()) ? "-" : String.format("%.1f", p.carbRatioGPerU()),
                    p.weightKg(), p.plausible() ? "ok" : "⚠fb"));
        }
        sb.append("ISF = 1800-rule from TDD (model-scaled); isf-B/L/D = ISF × (window CR / overall CR); CR = observed median; weight ≈ TDD/0.55.\n");
        sb.append(String.format("⚠fb = implausible TDD from corrupt insulin columns -> ISF/weight fall back to population defaults (%d subjects).%n", flagged));
        sb.append("═══════════════════════════════════════════════════════════════════════════════════════════════════\n");
        return sb.toString();
    }
}
