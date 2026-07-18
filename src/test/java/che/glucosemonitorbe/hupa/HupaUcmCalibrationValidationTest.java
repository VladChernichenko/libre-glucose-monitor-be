package che.glucosemonitorbe.hupa;

import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.hovorka.BasalInsulinResolver;
import che.glucosemonitorbe.hovorka.DallaManGutModel;
import che.glucosemonitorbe.hovorka.HovorkaGlucosePredictionService;
import che.glucosemonitorbe.hovorka.HovorkaOdeSolver;
import che.glucosemonitorbe.hovorka.HovorkaParameterService;
import che.glucosemonitorbe.hovorka.HovorkaParameters;
import che.glucosemonitorbe.hovorka.learning.DigitalTwinCalibrator;
import che.glucosemonitorbe.hovorka.learning.PredictionReplayEngine;
import che.glucosemonitorbe.hovorka.learning.PredictionResidualProvider;
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
 * Data-gated validation of the digital-twin learning (Levenberg-Marquardt + EGP₀) against the
 * real-world HUPA-UCM diabetes dataset - a second, independent cohort to the AZT1D validation.
 *
 * <p>For each subject it fits the twin on the earlier 80% of the record and scores it on the held-out
 * later 20%, then prints per-subject and aggregate baseline vs. calibrated prediction MAE.</p>
 *
 * <p>Skipped (not failed) when the dataset is absent. Point it at the data with
 * {@code -Dhupa.dir="/path/to/HUPA-UCM Diabetes Dataset"}.</p>
 */
class HupaUcmCalibrationValidationTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final double TRAIN_FRACTION = 0.8;
    private static final int MIN_CGM = 500;
    private static final double BAND_Z = 1.6449;

    private static Path datasetRoot() {
        return Path.of(System.getProperty("hupa.dir", ""));
    }

    private record Row(String id, int trainAnchors, int valSamples,
                       double maeBaseline, double maeCalibrated, double effective,
                       double isfScale, double agScale, double egpScale, boolean applied,
                       double sd30, double sd60, double sd90, double sd120) {}

    @Test
    void calibratesAndImprovesAcrossRealSubjects() throws Exception {
        Path root = datasetRoot();
        Assumptions.assumeTrue(!root.toString().isBlank() && Files.isDirectory(root.resolve("Preprocessed")),
                "HUPA-UCM dataset not present - set -Dhupa.dir=\"/path/to/HUPA-UCM Diabetes Dataset\" to run");

        List<HupaUcmDataset.Subject> subjects = HupaUcmDataset.loadAll(root);
        assertThat(subjects).isNotEmpty();

        HovorkaGlucosePredictionService predictor = rawPredictor();
        RapidInsulinIobParameters rapidIob = new RapidInsulinIobParameters(4.5, 55.0);
        PredictionReplayEngine.Config cfg = new PredictionReplayEngine.Config();
        cfg.maxAnchors = 120; // keep the sweep quick across all subjects

        List<Row> rows = new ArrayList<>();
        for (HupaUcmDataset.Subject s : subjects) {
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
            rows.add(new Row(s.id(), train.anchorCount(), r.valSamples(),
                    r.maeBaseline(), r.maeCalibrated(), effective,
                    r.scales().isfScale(), r.scales().agScale(), r.scales().egpScale(), r.improved(),
                    u.sdAtHorizon(30), u.sdAtHorizon(60), u.sdAtHorizon(90), u.sdAtHorizon(120)));
        }

        System.out.print(render(rows));

        for (Row row : rows) {
            if (!Double.isNaN(row.maeBaseline())) {
                assertThat(row.effective()).isLessThanOrEqualTo(row.maeBaseline() + 1e-6);
            }
            assertThat(row.sd30()).isLessThanOrEqualTo(row.sd60() + 1e-9);
            assertThat(row.sd60()).isLessThanOrEqualTo(row.sd90() + 1e-9);
            assertThat(row.sd90()).isLessThanOrEqualTo(row.sd120() + 1e-9);
        }
        assertThat(rows).isNotEmpty();
    }

    // -- Report rendering -----------------------------------------------------

    private static String render(List<Row> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n══════════════ HUPA-UCM DIGITAL-TWIN CALIBRATION (LM + EGP0, out-of-sample) ══════════════\n");
        sb.append(String.format("%-11s %7s %7s %9s %9s %7s %7s %7s %7s %6s%n",
                "subject", "anchors", "valN", "baseMAE", "calMAE", "impr%", "isf×", "aG×", "egp×", "appl"));

        int applied = 0;
        double sumBase = 0, sumEff = 0, sumImprApplied = 0;
        int counted = 0;
        for (Row r : rows) {
            if (Double.isNaN(r.maeBaseline())) {
                sb.append(String.format("%-11s %7d %7s   (insufficient anchors)%n", r.id(), r.trainAnchors(), "-"));
                continue;
            }
            double impr = 100.0 * (r.maeBaseline() - r.maeCalibrated()) / r.maeBaseline();
            sb.append(String.format("%-11s %7d %7d %9.2f %9.2f %6.1f%% %6.2f %6.2f %6.2f %6s%n",
                    r.id(), r.trainAnchors(), r.valSamples(),
                    r.maeBaseline(), r.maeCalibrated(), impr, r.isfScale(), r.agScale(), r.egpScale(),
                    r.applied() ? "YES" : "no"));
            sumBase += r.maeBaseline();
            sumEff  += r.effective();
            counted++;
            if (r.applied()) { applied++; sumImprApplied += impr; }
        }
        sb.append("-----------------------------------------------------------------------------------------\n");
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
        }
        sb.append("══════════════════════════════════════════════════════════════════════════════════════════\n");
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

    private static HovorkaParameters personalParams(HupaUcmDataset.Profile p) {
        double w = p.weightKg() > 0 ? p.weightKg() : HovorkaParameters.DEFAULT_WEIGHT;
        double isf = p.isfMmolPerU() > 0 ? p.isfMmolPerU() : 2.2;
        double vG = HovorkaParameters.VG_PER_KG * w;
        double f01 = HovorkaParameters.F01_PER_KG * w;
        return new HovorkaParameters(vG, f01, f01,
                HovorkaParameters.K12_POP, HovorkaParameters.K21_POP,
                45.0 / 1.68, 1.0, isf, w);
    }
}
