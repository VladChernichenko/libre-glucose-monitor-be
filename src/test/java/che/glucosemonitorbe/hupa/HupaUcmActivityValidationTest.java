package che.glucosemonitorbe.hupa;

import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.hovorka.ActivityProvider;
import che.glucosemonitorbe.hovorka.BasalInsulinResolver;
import che.glucosemonitorbe.hovorka.DallaManGutModel;
import che.glucosemonitorbe.hovorka.HovorkaGlucosePredictionService;
import che.glucosemonitorbe.hovorka.HovorkaOdeSolver;
import che.glucosemonitorbe.hovorka.HovorkaParameterService;
import che.glucosemonitorbe.hovorka.HovorkaParameters;
import che.glucosemonitorbe.hovorka.learning.PredictionReplayEngine;
import che.glucosemonitorbe.hovorka.learning.PredictionResidualProvider;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import che.glucosemonitorbe.service.UserSettingsService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Data-gated validation of the activity term on the HUPA-UCM dataset: for each subject it runs
 * forward predictions with vs. without the HR-derived activity signal and reports overall MAE, asserting
 * the activity-aware model is not worse. Skipped when the dataset is absent
 * ({@code -Dhupa.dir="/path/to/HUPA-UCM Diabetes Dataset"}).
 */
class HupaUcmActivityValidationTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000e3");
    private static final RapidInsulinIobParameters IOB = new RapidInsulinIobParameters(4.5, 55.0);
    private static final int HORIZON_MIN = 120;
    private static final int STRIDE = 12;            // anchor every ~60 min (5-min readings)
    private static final int MAX_ANCHORS = 100;
    private static final int MIN_CGM = 200;
    private static final long TOL_MS = 5 * 60 * 1000L;
    private static final int[] HORIZONS = {30, 60, 90, 120};

    private static Path datasetRoot() {
        return Path.of(System.getProperty("hupa.dir", ""));
    }

    @Test
    void activityTermDoesNotWorsenMae() throws Exception {
        Path root = datasetRoot();
        Assumptions.assumeTrue(!root.toString().isBlank() && Files.isDirectory(root.resolve("Preprocessed")),
                "HUPA-UCM dataset not present — set -Dhupa.dir=\"/path/to/HUPA-UCM Diabetes Dataset\" to run");

        List<HupaUcmDataset.Subject> subjects = HupaUcmDataset.loadAll(root);
        HovorkaGlucosePredictionService predictor = rawPredictor();

        double sumNone = 0, sumAct = 0;
        int n = 0;
        for (HupaUcmDataset.Subject s : subjects) {
            if (s.cgm().size() < MIN_CGM) continue;

            List<PredictionReplayEngine.Reading> cgm = s.cgm().stream()
                    .sorted((a, b) -> Long.compare(a.epochMs(), b.epochMs())).toList();
            long[] t = cgm.stream().mapToLong(PredictionReplayEngine.Reading::epochMs).toArray();
            double[] g = cgm.stream().mapToDouble(PredictionReplayEngine.Reading::mmol).toArray();

            HovorkaParameters base = personalParams(s.profile());
            ActivityProvider provider = new HupaActivityAdapter(s.activity());

            int anchors = 0;
            for (int i = 0; i < t.length && anchors < MAX_ANCHORS; i += STRIDE) {
                long t0 = t[i];
                LocalDateTime now = toLdt(t0);
                List<CarbsEntry> carbs = new ArrayList<>();
                List<InsulinDose> insulin = new ArrayList<>();
                List<Note> longActing = new ArrayList<>();
                collectEvents(s.events(), t0, carbs, insulin, longActing);

                List<PredictionPointDTO> none = predictor.buildPredictionPath(
                        base, IOB, null, g[i], now, carbs, insulin, longActing, USER, HORIZON_MIN);
                List<PredictionPointDTO> act = predictor.buildPredictionPath(
                        base, IOB, null, g[i], now, carbs, insulin, longActing, USER, HORIZON_MIN, provider);

                for (int h : HORIZONS) {
                    Double actual = nearest(t, g, t0 + h * 60_000L);
                    if (actual == null) continue;
                    Double pn = glucoseAt(none, now, h);
                    Double pa = glucoseAt(act, now, h);
                    if (pn == null || pa == null) continue;
                    sumNone += Math.abs(pn - actual);
                    sumAct += Math.abs(pa - actual);
                    n++;
                }
                anchors++;
            }
        }

        assertThat(n).isGreaterThan(0);
        double maeNone = sumNone / n;
        double maeAct = sumAct / n;
        System.out.printf("%n==== HUPA-UCM ACTIVITY VALIDATION (n=%d samples) ====%n", n);
        System.out.printf("Overall MAE without activity: %.3f mmol/L%n", maeNone);
        System.out.printf("Overall MAE with activity:    %.3f mmol/L%n", maeAct);
        System.out.printf("Delta (with − without):       %+.3f mmol/L%n", maeAct - maeNone);

        // Not-worse (allow a small tolerance for numerical noise on mostly-resting data).
        assertThat(maeAct).isLessThanOrEqualTo(maeNone + 0.05);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void collectEvents(List<PredictionReplayEngine.Event> events, long t0,
                                      List<CarbsEntry> carbs, List<InsulinDose> insulin, List<Note> longActing) {
        for (PredictionReplayEngine.Event e : events) {
            long age = t0 - e.epochMs();
            boolean inPast = age >= 0 && age <= 8 * 3600_000L;
            boolean inFuture = age < 0 && -age <= HORIZON_MIN * 60_000L;
            if (e.longActing()) {
                if (age >= 0 && age <= 36 * 3600_000L) {
                    Note n = new Note();
                    n.setTimestamp(toLdt(e.epochMs()));
                    n.setInsulin(e.insulin() > 0 ? e.insulin() : 10.0);
                    n.setType(Note.TYPE_LONG_ACTING);
                    longActing.add(n);
                }
                continue;
            }
            if (!inPast && !inFuture) continue;
            if (e.carbs() > 0) carbs.add(CarbsEntry.builder().timestamp(toLdt(e.epochMs())).carbs(e.carbs()).build());
            if (e.insulin() > 0) insulin.add(InsulinDose.builder().timestamp(toLdt(e.epochMs()))
                    .units(e.insulin()).type(InsulinDose.InsulinType.BOLUS).build());
        }
    }

    private static Double glucoseAt(List<PredictionPointDTO> pts, LocalDateTime now, int h) {
        LocalDateTime target = now.plusMinutes(h);
        return pts.stream().filter(pt -> pt.getTimestamp().equals(target))
                .map(PredictionPointDTO::getPredictedGlucose).findFirst().orElse(null);
    }

    private static Double nearest(long[] t, double[] g, long target) {
        if (t.length == 0) return null;
        int lo = 0, hi = t.length - 1;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (t[mid] < target) lo = mid + 1; else hi = mid; }
        int best = lo;
        if (lo > 0 && Math.abs(t[lo - 1] - target) < Math.abs(t[best] - target)) best = lo - 1;
        return Math.abs(t[best] - target) <= TOL_MS ? g[best] : null;
    }

    private static LocalDateTime toLdt(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC);
    }

    private static HovorkaParameters personalParams(HupaUcmDataset.Profile p) {
        double w = p.weightKg() > 0 ? p.weightKg() : HovorkaParameters.DEFAULT_WEIGHT;
        double isf = p.isfMmolPerU() > 0 ? p.isfMmolPerU() : 2.2;
        double vG = HovorkaParameters.VG_PER_KG * w;
        double f01 = HovorkaParameters.F01_PER_KG * w;
        return new HovorkaParameters(vG, f01, f01,
                HovorkaParameters.K12_POP, HovorkaParameters.K21_POP, 45.0 / 1.68, 1.0, isf, w);
    }

    private static HovorkaGlucosePredictionService rawPredictor() {
        DallaManGutModel gut = new DallaManGutModel();
        HovorkaOdeSolver solver = new HovorkaOdeSolver(gut);
        BasalInsulinResolver basal = new BasalInsulinResolver();
        return new HovorkaGlucosePredictionService(
                mock(HovorkaParameterService.class), solver, basal,
                mock(UserInsulinPreferencesService.class), gut,
                mock(UserSettingsService.class), PredictionResidualProvider.NONE);
    }
}
