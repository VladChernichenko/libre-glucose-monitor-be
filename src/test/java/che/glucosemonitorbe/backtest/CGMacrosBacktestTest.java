package che.glucosemonitorbe.backtest;

import che.glucosemonitorbe.hovorka.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Postprandial accuracy harness for the Hovorka / Dalla Man gut model
 * run against the CGMacros dataset (45 non-diabetic subjects, ~1 500 real meals).
 *
 * <p>Because these subjects have no exogenous insulin, {@code insulinEffect=0}
 * throughout. The test isolates the gut absorption / glucose distribution model.
 * Endogenous insulin response means we systematically over-predict at ≥90 min;
 * the +30/+60 min windows are the most informative.</p>
 *
 * <p>Skipped automatically when the CGMacros directory does not exist,
 * so CI without the dataset stays green.</p>
 */
class CGMacrosBacktestTest {

    private static final Path DATA_DIR = Path.of(
            "/Users/vlad/IdeaProjects/glucose-monitor-project/CGMacros");
    private static final Path BIO_CSV = DATA_DIR.resolve("bio.csv");

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final int[] HORIZONS_MIN = {30, 60, 90, 120};

    // ── Main test ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CGMacros postprandial accuracy: gut-model vs real CGM, no-insulin subjects")
    void cgMacrosPostprandialAccuracy() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(DATA_DIR),
                "CGMacros dataset not found at " + DATA_DIR + " — skipping");

        Map<Integer, Double> weightKgBySubject = loadBioWeights();
        HovorkaOdeSolver solver = new HovorkaOdeSolver(new DallaManGutModel());

        int H = HORIZONS_MIN.length;
        int[]    n      = new int[H];
        double[] sumAbs = new double[H];
        double[] sumSq  = new double[H];
        double[] sumApe = new double[H];
        double[] sumBias = new double[H];
        int[][] zones    = new int[H][5];

        int subjectsRun = 0, mealsRun = 0;

        List<Path> subjectDirs = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(DATA_DIR, "CGMacros-*")) {
            ds.forEach(subjectDirs::add);
        }
        subjectDirs.sort(Comparator.comparing(Path::getFileName));

        for (Path subjectDir : subjectDirs) {
            String id  = subjectDir.getFileName().toString();
            Path   csv = subjectDir.resolve(id + ".csv");
            if (!Files.exists(csv)) continue;

            int    subjNum  = Integer.parseInt(id.substring("CGMacros-".length()));
            double weightKg = weightKgBySubject.getOrDefault(subjNum, HovorkaParameters.DEFAULT_WEIGHT);

            List<Row> rows = loadRows(csv);
            if (rows.size() < 121) continue;
            subjectsRun++;

            for (int i = 0; i < rows.size(); i++) {
                Row meal = rows.get(i);
                if (meal.mealType == null || meal.carbsG < 5.0) continue;
                if (meal.libreGlMgdl <= 30) continue;

                double tMaxG = MacroNutrientGastricModel.computeTMaxG(
                        meal.carbsG, meal.proteinG, meal.fatG, meal.fiberG,
                        HovorkaParameterService.HALF_LIFE_TO_TMAX_G);
                HovorkaParameters params = buildParams(weightKg, tMaxG);

                double anchorMmol = meal.libreGlMgdl / BacktestHarness.MGDL_PER_MMOL;
                double carbMmol   = meal.carbsG * params.aG() / 0.18;

                HovorkaState state = HovorkaState.steadyState(anchorMmol, params);
                state = solver.step(state, params, carbMmol, 0.0);

                double[] predMmol = new double[H];
                for (int m = 2; m <= HORIZONS_MIN[H - 1]; m++) {
                    state = solver.step(state, params, 0.0, 0.0);
                    for (int h = 0; h < H; h++) {
                        if (m == HORIZONS_MIN[h]) predMmol[h] = state.glucoseMmolL(params);
                    }
                }

                boolean counted = false;
                for (int h = 0; h < H; h++) {
                    Double actualMmol = findActualAt(rows, i, HORIZONS_MIN[h]);
                    if (actualMmol == null || predMmol[h] == 0.0) continue;
                    double err = predMmol[h] - actualMmol;
                    n[h]++;
                    sumAbs[h]  += Math.abs(err);
                    sumSq[h]   += err * err;
                    sumApe[h]  += Math.abs(err) / actualMmol;
                    sumBias[h] += err;
                    zones[h][BacktestHarness.clarkeZone(
                            actualMmol * BacktestHarness.MGDL_PER_MMOL,
                            predMmol[h] * BacktestHarness.MGDL_PER_MMOL).ordinal()]++;
                    counted = true;
                }
                if (counted) mealsRun++;
            }
        }

        printReport(subjectsRun, mealsRun, n, sumAbs, sumSq, sumApe, sumBias, zones);

        assertThat(subjectsRun).as("subjects processed").isGreaterThan(10);
        assertThat(mealsRun).as("meals processed").isGreaterThan(100);
        assertThat(n[0]).as("+30 min samples").isGreaterThan(50);

        // At +30 min the insulin response has barely begun: MAE should be < 2.5 mmol/L.
        double mae30 = sumAbs[0] / n[0];
        assertThat(mae30).as("MAE at +30 min (mmol/L)").isLessThan(2.5);

        // Clarke A+B at +30 min: predictions should be safer than random (> 50%).
        double clarkeAB30 = 100.0 * (zones[0][0] + zones[0][1]) / n[0];
        assertThat(clarkeAB30).as("Clarke A+B% at +30 min").isGreaterThan(50.0);
    }

    // ── Synthetic T1D IOB test ─────────────────────────────────────────────────

    /**
     * Synthetic type-1 diabetes scenario: 60 g carb meal + meal bolus via the OpenAPS
     * exponential IOB curve. Validates the full ODE pipeline (gut absorption + insulin effect)
     * without needing a real dataset.
     *
     * <p>Two trajectories run in parallel from the same fasting steady state:
     * <ul>
     *   <li><b>No insulin</b>: pure gut absorption, glucose climbs to T1D-level hyperglycaemia.
     *   <li><b>With bolus</b>: OpenAPS IOB curve drives {@code insulinEffect} at each minute,
     *       suppressing the postprandial rise and keeping glucose safe.
     * </ul>
     */
    @Test
    @DisplayName("Synthetic T1D: meal bolus via OpenAPS IOB curve suppresses postprandial rise")
    void syntheticT1d_mealBolus_suppressesPostprandialRise() {
        double weightKg   = 70.0;
        double isf        = 2.5;   // mmol/L per unit — typical T1D
        double cr         = 10.0;  // g carbs per unit
        double diaMin     = 270.0; // 4.5 h duration
        double peakMin    = 75.0;  // Fiasp-like peak

        double carbsG   = 60.0;
        double proteinG = 20.0;
        double fatG     = 15.0;
        double fiberG   = 5.0;
        double bolusUnits = carbsG / cr;  // 6 units

        double tMaxG = MacroNutrientGastricModel.computeTMaxG(
                carbsG, proteinG, fatG, fiberG,
                HovorkaParameterService.HALF_LIFE_TO_TMAX_G);
        HovorkaParameters p = buildParams(weightKg, tMaxG);

        double g0mmol   = 5.5;
        double carbMmol = carbsG * p.aG() / 0.18;
        double effVol   = p.effectiveInsulinVolume();

        HovorkaOdeSolver solver = new HovorkaOdeSolver(new DallaManGutModel());
        HovorkaState stateNoIns = HovorkaState.steadyState(g0mmol, p);
        HovorkaState stateIns   = HovorkaState.steadyState(g0mmol, p);

        // Minute 0: inject carbs (bolus timing is implicit in the IOB curve starting at m=1)
        stateNoIns = solver.step(stateNoIns, p, carbMmol, 0.0);
        stateIns   = solver.step(stateIns,   p, carbMmol, 0.0);

        double peakNoIns = stateNoIns.glucoseMmolL(p);
        double peakIns   = stateIns.glucoseMmolL(p);
        double minIns    = stateIns.glucoseMmolL(p);
        double gNoIns120 = 0.0, gIns120 = 0.0;

        for (int m = 1; m <= 180; m++) {
            double ie = iobEffect(bolusUnits, m, diaMin, peakMin, isf, effVol);
            stateNoIns = solver.step(stateNoIns, p, 0.0, 0.0);
            stateIns   = solver.step(stateIns,   p, 0.0, ie);
            double gN = stateNoIns.glucoseMmolL(p);
            double gI = stateIns.glucoseMmolL(p);
            peakNoIns = Math.max(peakNoIns, gN);
            peakIns   = Math.max(peakIns,   gI);
            minIns    = Math.min(minIns,     gI);
            if (m == 120) { gNoIns120 = gN; gIns120 = gI; }
        }

        System.out.printf(
                "%nSynthetic T1D bolus test  (60 g carbs  %.1f u  ISF=%.1f  DIA=%.0f min)%n"
                + "  No insulin : peak=%.2f mmol/L   @120min=%.2f%n"
                + "  With bolus : peak=%.2f mmol/L   @120min=%.2f   min=%.2f%n",
                bolusUnits, isf, diaMin,
                peakNoIns, gNoIns120,
                peakIns,   gIns120, minIns);

        // T1D with no insulin: gut absorption alone drives glucose into hyperglycaemia
        assertThat(peakNoIns).as("peak glucose — no insulin").isGreaterThan(9.0);

        // Bolus must suppress the peak
        assertThat(peakIns).as("peak glucose — with bolus").isLessThan(peakNoIns);

        // Without counterregulatory hormones the model correctly shows late-phase hypo risk
        // from a CR=10 bolus (slightly aggressive). Guard > 2.0 catches sign errors / runaway.
        assertThat(minIns).as("minimum glucose — with bolus").isGreaterThan(2.0);

        // At +2 h the insulin-treated trajectory must be clearly lower
        assertThat(gIns120).as("glucose at +120 min — with bolus").isLessThan(gNoIns120);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static HovorkaParameters buildParams(double weightKg, double tMaxG) {
        double vG  = HovorkaParameters.VG_PER_KG * weightKg;
        double f01 = HovorkaParameters.F01_PER_KG * weightKg;
        return new HovorkaParameters(vG, f01, f01,
                HovorkaParameters.K12_POP, HovorkaParameters.K21_POP,
                tMaxG, 1.0, 2.2, weightKg);
    }

    /**
     * Find the CGM reading at approximately anchorIdx + horizonMin rows.
     * Scans a ±3 row window and returns the value whose timestamp is closest to
     * anchor + horizonMin minutes, or null if nothing is within 150 s (±2.5 min).
     */
    private Double findActualAt(List<Row> rows, int anchorIdx, int horizonMin) {
        LocalDateTime target = rows.get(anchorIdx).timestamp.plusMinutes(horizonMin);
        int start = Math.max(anchorIdx + 1, anchorIdx + horizonMin - 3);
        int end   = Math.min(rows.size() - 1, anchorIdx + horizonMin + 3);
        double bestSecs = Double.MAX_VALUE;
        Double best = null;
        for (int i = start; i <= end; i++) {
            Row r = rows.get(i);
            if (r.libreGlMgdl <= 30 || r.timestamp == null) continue;
            long diff = Math.abs(Duration.between(target, r.timestamp).toSeconds());
            if (diff < bestSecs && diff <= 150) {
                bestSecs = diff;
                best = r.libreGlMgdl / BacktestHarness.MGDL_PER_MMOL;
            }
        }
        return best;
    }

    private Map<Integer, Double> loadBioWeights() throws IOException {
        Map<Integer, Double> map = new HashMap<>();
        if (!Files.exists(BIO_CSV)) return map;
        List<String> lines  = Files.readAllLines(BIO_CSV);
        List<String> header = BacktestHarness.splitCsv(lines.get(0));
        int iSubj   = indexOfTrimmed(header, "subject");
        int iWeight = indexOfTrimmed(header, "Body weight");
        if (iSubj < 0 || iWeight < 0) return map;
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            List<String> f = BacktestHarness.splitCsv(lines.get(i));
            if (f.size() <= Math.max(iSubj, iWeight)) continue;
            String subStr = f.get(iSubj).trim();
            String wtStr  = f.get(iWeight).trim();
            if (subStr.isEmpty() || wtStr.isEmpty()) continue;
            try {
                double lbs = Double.parseDouble(wtStr);
                map.put(Integer.parseInt(subStr), lbs / 2.20462);
            } catch (NumberFormatException ignored) {}
        }
        return map;
    }

    private static int indexOfTrimmed(List<String> header, String key) {
        for (int i = 0; i < header.size(); i++) {
            if (header.get(i).trim().equalsIgnoreCase(key)) return i;
        }
        return -1;
    }

    /**
     * Load rows from a CGMacros CSV, detecting column positions dynamically from the header.
     * Two formats exist in the dataset: some files have a leading "Unnamed: 0" pandas index
     * column; others start directly with "Timestamp". Header-based detection handles both.
     */
    private List<Row> loadRows(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv);
        if (lines.isEmpty()) return List.of();

        List<String> header = BacktestHarness.splitCsv(lines.get(0));
        int colTs      = indexOfTrimmed(header, "Timestamp");
        int colLibre   = indexOfTrimmed(header, "Libre GL");
        int colMealTyp = indexOfTrimmed(header, "Meal Type");
        int colCarbs   = indexOfTrimmed(header, "Carbs");
        int colProtein = indexOfTrimmed(header, "Protein");
        int colFat     = indexOfTrimmed(header, "Fat");
        int colFiber   = indexOfTrimmed(header, "Fiber");
        if (colTs < 0 || colLibre < 0 || colMealTyp < 0) return List.of();

        int minCols = Math.max(colFiber, Math.max(colCarbs,
                Math.max(colProtein, Math.max(colFat, Math.max(colTs, colLibre)))));

        List<Row> out = new ArrayList<>(lines.size());
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            List<String> f = BacktestHarness.splitCsv(lines.get(i));
            if (f.size() <= minCols) continue;
            Row r = new Row();
            r.timestamp   = parseTs(f.get(colTs).trim());
            if (r.timestamp == null) continue;
            r.libreGlMgdl = parseD(f.get(colLibre));
            String mt = f.get(colMealTyp).trim();
            r.mealType    = mt.isEmpty() ? null : mt;
            r.carbsG      = colCarbs   >= 0 ? parseD(f.get(colCarbs))   : 0;
            r.proteinG    = colProtein >= 0 ? parseD(f.get(colProtein)) : 0;
            r.fatG        = colFat     >= 0 ? parseD(f.get(colFat))     : 0;
            r.fiberG      = colFiber   >= 0 ? parseD(f.get(colFiber))   : 0;
            out.add(r);
        }
        return out;
    }

    private static LocalDateTime parseTs(String s) {
        try { return LocalDateTime.parse(s, TS_FMT); } catch (Exception e) { return null; }
    }

    private static double parseD(String s) {
        s = s.trim();
        if (s.isEmpty()) return 0.0;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0.0; }
    }

    private static void printReport(int subjects, int meals,
                                    int[] n, double[] sumAbs, double[] sumSq,
                                    double[] sumApe, double[] sumBias, int[][] zones) {
        int H = HORIZONS_MIN.length;
        System.out.printf(
                "%n══════════ CGMacros Postprandial Backtest ══════════%n" +
                "Subjects: %d   Meals: %d   (insulinEffect=0 throughout)%n%n",
                subjects, meals);
        System.out.printf("%-9s %6s %7s %7s %7s %8s  %10s%n",
                "horizon", "n", "MAE", "RMSE", "MARD%", "bias", "ClarkeAB%");
        for (int h = 0; h < H; h++) {
            if (n[h] == 0) continue;
            double mae  = sumAbs[h] / n[h];
            double rmse = Math.sqrt(sumSq[h] / n[h]);
            double mard = 100.0 * sumApe[h] / n[h];
            double bias = sumBias[h] / n[h];
            double ab   = 100.0 * (zones[h][0] + zones[h][1]) / n[h];
            System.out.printf("+%-6dmin %6d %7.2f %7.2f %6.1f%% %8.2f  %8.1f%%%n",
                    HORIZONS_MIN[h], n[h], mae, rmse, mard, bias, ab);
        }
        System.out.printf("Note: positive bias = model over-predicts (expected, no endogenous insulin)%n" +
                "═══════════════════════════════════════════════════%n");
    }

    // ── IOB helpers (OpenAPS exponential model) ──────────────────────────────

    private static double iobEffect(double units, int minsElapsed,
                                    double diaMin, double peakMin,
                                    double isf, double effVolume) {
        double iobNow  = iobExponential(units, minsElapsed,     diaMin, peakMin);
        double iobNext = iobExponential(units, minsElapsed + 1, diaMin, peakMin);
        return isf * effVolume * Math.max(0.0, iobNow - iobNext);
    }

    private static double iobExponential(double units, double minsAgo,
                                         double diaMin, double peak) {
        if (minsAgo < 0 || minsAgo >= diaMin || units <= 0) return 0.0;
        double denom = 1.0 - 2.0 * peak / diaMin;
        if (Math.abs(denom) < 1e-5) return units * Math.max(0.0, 1.0 - minsAgo / diaMin);
        double tau = peak * (1.0 - peak / diaMin) / denom;
        double a   = 2.0 * tau / diaMin;
        double s   = 1.0 / (1.0 - a + (1.0 + a) * Math.exp(-diaMin / tau));
        double bracket = (Math.pow(minsAgo, 2) / (tau * diaMin * (1.0 - a))
                - minsAgo / tau - 1.0) * Math.exp(-minsAgo / tau) + 1.0;
        return Math.max(0.0, Math.min(units, units * (1.0 - s * (1.0 - a) * bracket)));
    }

    // ── Data row ─────────────────────────────────────────────────────────────

    private static final class Row {
        LocalDateTime timestamp;
        double libreGlMgdl;
        String mealType;
        double carbsG, proteinG, fatG, fiberG;
    }
}
