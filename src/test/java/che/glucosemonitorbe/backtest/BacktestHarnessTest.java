package che.glucosemonitorbe.backtest;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two things:
 * <ol>
 *   <li>Always-on unit tests for the harness math (CSV parsing, alignment, Clarke zones) on
 *       synthetic fixtures — these guard the measurement, not the model.</li>
 *   <li>A data-gated run that executes the full backtest against the exported CSVs and prints
 *       the report. Skipped (not failed) when the CSV files are absent, so CI without data is green.
 *       Override paths with {@code -Dbacktest.cgm=… -Dbacktest.notes=…}.</li>
 * </ol>
 */
class BacktestHarnessTest {

    private static List<Path> cgmFiles() {
        return List.of(
                Path.of(System.getProperty("backtest.cgm",  "cgm_readings_202606101956.csv")),
                Path.of(System.getProperty("backtest.cgm2", "/Users/vlad/harness-local/cgm_readings_202606102003.csv")))
                .stream().filter(Files::exists).toList();
    }

    private static List<Path> noteFiles() {
        return List.of(
                Path.of(System.getProperty("backtest.notes",  "notes_202606101957.csv")),
                Path.of(System.getProperty("backtest.notes2", "/Users/vlad/harness-local/notes_202606102004.csv")))
                .stream().filter(Files::exists).toList();
    }

    // ── Math unit tests (always run) ───────────────────────────────────────────

    @Test
    void csvSplit_handlesQuotedCommasAndEscapedQuotes() {
        List<String> f = BacktestHarness.splitCsv("a,\"b,c\",\"d\"\"e\",f");
        assertThat(f).containsExactly("a", "b,c", "d\"e", "f");
    }

    @Test
    void noteTimestamp_parsesOffsetDateTime() {
        long e = BacktestHarness.parseNoteTs("2026-06-10 05:48:57.000 +0400");
        // 05:48:57 +0400 == 01:48:57Z
        assertThat(java.time.Instant.ofEpochMilli(e).toString()).isEqualTo("2026-06-10T01:48:57Z");
    }

    @Test
    void nearest_returnsClosestWithinTolerance_elseNull() {
        long[] t = {0, 300_000, 600_000};       // 0,5,10 min
        double[] g = {5.0, 6.0, 7.0};
        assertThat(BacktestHarness.nearest(t, g, 290_000, 150_000)).isEqualTo(6.0);
        assertThat(BacktestHarness.nearest(t, g, 450_000, 150_000)).isEqualTo(7.0); // exact tie → upper index
        assertThat(BacktestHarness.nearest(t, g, 1_000_000, 150_000)).isNull();      // gap > tol
    }

    @Test
    @DisplayName("Clarke zones: canonical points land in the expected zones")
    void clarkeZones_canonicalPoints() {
        assertThat(BacktestHarness.clarkeZone(100, 100)).isEqualTo(BacktestHarness.ClarkeZone.A);
        assertThat(BacktestHarness.clarkeZone(100, 150)).isEqualTo(BacktestHarness.ClarkeZone.B);
        assertThat(BacktestHarness.clarkeZone(80, 200)).isEqualTo(BacktestHarness.ClarkeZone.C);
        assertThat(BacktestHarness.clarkeZone(300, 150)).isEqualTo(BacktestHarness.ClarkeZone.D);
        assertThat(BacktestHarness.clarkeZone(250, 60)).isEqualTo(BacktestHarness.ClarkeZone.E);
        assertThat(BacktestHarness.clarkeZone(50, 200)).isEqualTo(BacktestHarness.ClarkeZone.E);
    }

    @Test
    void metrics_computeMaeRmseInBand_onSyntheticSamples() {
        BacktestHarness.Report r = new BacktestHarness.Report(new BacktestHarness.Config());
        // horizon 240: errors of +1.0 and -3.0 vs actual 8.0; baseline 7.0
        r.add(240, BacktestHarness.Bucket.MEAL, 9.0, 8.0, 7.0);   // |err|=1.0 → in ±2 band
        r.add(240, BacktestHarness.Bucket.MEAL, 5.0, 8.0, 7.0);   // |err|=3.0 → out of band
        BacktestHarness.Metrics m = r.metricsAt(240);
        assertThat(m.n).isEqualTo(2);
        assertThat(m.mae).isEqualTo(2.0);
        assertThat(m.rmse).isCloseTo(Math.sqrt((1 + 9) / 2.0), org.assertj.core.data.Offset.offset(1e-9));
        assertThat(m.inBandPct).isEqualTo(50.0);
    }

    // ── Full backtest against real data (gated) ────────────────────────────────

    @Test
    @DisplayName("run backtest on exported CGM/notes CSVs (remote + local, merged) and print the report")
    void runBacktestOnExportedData() throws Exception {
        // Default to the remote export (in the module dir) + the local-DB export (same subject,
        // different user_id, extends the window back ~4 days). Merged and de-duped by the harness.
        List<Path> cgm = List.of(
                Path.of(System.getProperty("backtest.cgm",  "cgm_readings_202606101956.csv")),
                Path.of(System.getProperty("backtest.cgm2", "/Users/vlad/harness-local/cgm_readings_202606102003.csv")));
        List<Path> notes = List.of(
                Path.of(System.getProperty("backtest.notes",  "notes_202606101957.csv")),
                Path.of(System.getProperty("backtest.notes2", "/Users/vlad/harness-local/notes_202606102004.csv")));

        List<Path> cgmPresent   = cgm.stream().filter(Files::exists).toList();
        List<Path> notesPresent = notes.stream().filter(Files::exists).toList();
        Assumptions.assumeTrue(!cgmPresent.isEmpty() && !notesPresent.isEmpty(),
                "No CSV exports found — skipping data-gated backtest");

        double isf    = Double.parseDouble(System.getProperty("backtest.isf", "2.2"));
        double weight = Double.parseDouble(System.getProperty("backtest.weight", "70"));
        double chl    = Double.parseDouble(System.getProperty("backtest.carbHalfLife", "45"));

        // Variant A: ODE core only (fixed base tMaxG, no FPU) — what the first runs measured.
        BacktestHarness.Config base = new BacktestHarness.Config();
        base.isf = isf; base.weightKg = weight; base.carbHalfLifeMin = chl;
        base.macroTMaxG = false; base.fpuEquiv = false; base.label = "A · ODE core (fixed tMaxG, no FPU)";

        // Variant B: production-faithful meal handling (per-meal macro tMaxG + FPU-equiv tails).
        BacktestHarness.Config faithful = new BacktestHarness.Config();
        faithful.isf = isf; faithful.weightKg = weight; faithful.carbHalfLifeMin = chl;
        faithful.macroTMaxG = true; faithful.fpuEquiv = true;
        faithful.label = "B · production-faithful (macro tMaxG + FPU)";

        BacktestHarness.Report rA = BacktestHarness.runAndReport(cgmPresent, notesPresent, base);
        BacktestHarness.Report rB = BacktestHarness.runAndReport(cgmPresent, notesPresent, faithful);
        System.out.println(rA.render());
        System.out.println(rB.render());

        assertThat(rB.sampleCount())
                .as("backtest produced no aligned samples — check time alignment / data coverage")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("ISF × aG × EGP sweep — print 4h sensitivity grid")
    void parameterSweep() throws Exception {
        List<Path> cgm = cgmFiles(), notes = noteFiles();
        Assumptions.assumeTrue(!cgm.isEmpty() && !notes.isEmpty(),
                "No CSV exports found — skipping sweep");

        double[] isfs  = {1.6, 2.2, 2.8};
        double[] aGs   = {0.7, 1.0, 1.3};
        double[] egps  = {1.0, 1.3, 1.6};

        System.out.println("\n══════════════ ISF × aG × EGP SWEEP (4h horizon, ODE-core meal handling) ══════════════");
        System.out.printf("%5s %5s %6s | %9s %6s %7s | %8s %7s | %8s | %9s%n",
                "ISF", "aG", "egp", "overall%", "MAE", "bias", "MEAL%", "mBias", "FAST bias", "Clarke AB%");

        double bestInBand = -1; String bestRow = "";
        for (double isf : isfs) for (double aG : aGs) for (double egp : egps) {
            BacktestHarness.Config c = new BacktestHarness.Config();
            c.isf = isf; c.aG = aG; c.egpScale = egp;
            c.macroTMaxG = false; c.fpuEquiv = false;   // isolate ISF/aG/EGP from macro/FPU confounds
            BacktestHarness.Report r = BacktestHarness.runAndReport(cgm, notes, c);
            BacktestHarness.Metrics all  = r.metricsAt(240);
            BacktestHarness.Metrics meal = r.metricsAt(240, BacktestHarness.Bucket.MEAL);
            BacktestHarness.Metrics fast = r.metricsAt(240, BacktestHarness.Bucket.FASTING);
            System.out.printf("%5.1f %5.2f %6.1f | %8.1f%% %6.2f %7.2f | %7.1f%% %7.2f | %8.2f | %8.1f%%%n",
                    isf, aG, egp, all.inBandPct, all.mae, all.bias,
                    meal.inBandPct, meal.bias, fast.bias, all.clarkeAB);
            if (all.inBandPct > bestInBand) {
                bestInBand = all.inBandPct;
                bestRow = String.format("ISF=%.1f aG=%.2f egp=%.1f → overall in-band %.1f%% (persistence ~48.7%%)",
                        isf, aG, egp, all.inBandPct);
            }
        }
        System.out.println("BEST: " + bestRow);
        System.out.println("════════════════════════════════════════════════════════════════════════════════════════\n");
    }
}
