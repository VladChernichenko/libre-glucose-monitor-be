package che.glucosemonitorbe.azt1d;

import che.glucosemonitorbe.hovorka.learning.TwinScales;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Data-gated sweep over all 25 AZT1D subjects: mine episodes, forecast each with the earlier-data
 * twin and the raw model, check the rule of its kind, print a report, and hold today's results as a
 * baseline. Skipped when the dataset is absent. Point it at the data with
 * {@code -Dazt1d.dir="/path/to/AZT1D 2025"}; {@code -Dazt1d.baseline=<file>} moves the baseline and
 * {@code -Dazt1d.baseline.update=true} rewrites it.
 */
class Azt1dScenarioSweepTest {

    private static final String TWIN_SANITY_KEY = "TWIN_SANITY|all";

    private record Cell(ScenarioKind kind, String subjectId, int n, int pass, int fail, int notApplicable,
                        double medianTwin, double medianNeutral, boolean fallbackProfile) {}

    @Test
    void sweepReportsAndHoldsTheBaseline() throws Exception {
        Path root = Path.of(System.getProperty("azt1d.dir", ""));
        Assumptions.assumeTrue(!root.toString().isBlank() && Files.isDirectory(root.resolve("CGM Records")),
                "AZT1D dataset not present - set -Dazt1d.dir=\"/path/to/AZT1D 2025\" to run");

        List<Azt1dDataset.Subject> subjects = Azt1dDataset.loadAll(root);
        Map<String, List<Azt1dDataset.BolusRecord>> logs = Azt1dDataset.loadAllBolusLogs(root);
        assertThat(subjects).isNotEmpty();

        List<Cell> cells = new ArrayList<>();
        List<String> knownGaps = new ArrayList<>();
        java.util.Set<String> subjectsAtALimit = new java.util.TreeSet<>();
        for (Azt1dDataset.Subject s : subjects) {
            ScenarioForecaster forecaster = new ScenarioForecaster(s);
            List<Azt1dDataset.BolusRecord> log = logs.getOrDefault(s.id(), List.of());
            for (ScenarioKind kind : ScenarioKind.values()) {
                List<ScenarioEpisode> episodes = ScenarioMiner.mine(s, log, kind);
                if (episodes.isEmpty()) continue;
                int pass = 0, fail = 0, na = 0;
                List<Double> twinErrors = new ArrayList<>(), neutralErrors = new ArrayList<>();
                for (ScenarioEpisode ep : episodes) {
                    ForecastResult twinResult = forecaster.forecast(ep, ScaleMode.TWIN);
                    if (twinResult.twinApplied() && sitsAtALimit(twinResult.scales())) subjectsAtALimit.add(s.id());
                    Verdict twin = ScenarioRules.evaluate(twinResult);
                    Verdict neutral = ScenarioRules.evaluate(forecaster.forecast(ep, ScaleMode.NEUTRAL));
                    switch (twin.outcome()) {
                        case PASS -> pass++;
                        case NOT_APPLICABLE -> na++;
                        case FAIL -> {
                            fail++;
                            knownGaps.add(String.format("%s %s start %s: %s (%s)", s.id(), kind,
                                    java.time.Instant.ofEpochMilli(ep.startEpochMs()), twin.rule(), twin.detail()));
                        }
                    }
                    if (!Double.isNaN(twin.errorMmol())) twinErrors.add(twin.errorMmol());
                    if (!Double.isNaN(neutral.errorMmol())) neutralErrors.add(neutral.errorMmol());
                }
                cells.add(new Cell(kind, s.id(), episodes.size(), pass, fail, na,
                        median(twinErrors), median(neutralErrors), !s.profile().plausible()));
            }
        }

        System.out.print(render(cells, knownGaps));
        System.out.printf("TWIN SANITY: %d subject(s) with an applied twin whose ISF or meal scale sits at a clamp limit: %s%n",
                subjectsAtALimit.size(), subjectsAtALimit);
        holdBaseline(cells, subjectsAtALimit.size());
    }

    /** A fitted scale at its floor or ceiling is the signature of a twin that could not explain the data. */
    private static boolean sitsAtALimit(TwinScales t) {
        double tol = 1e-3;
        return Math.abs(t.isfScale() - TwinScales.MIN_SCALE) < tol || Math.abs(t.isfScale() - TwinScales.MAX_SCALE) < tol
                || Math.abs(t.agScale() - TwinScales.MIN_SCALE) < tol || Math.abs(t.agScale() - TwinScales.MAX_SCALE) < tol;
    }

    private static void holdBaseline(List<Cell> cells, int subjectsAtALimit) throws Exception {
        ScenarioBaseline current = new ScenarioBaseline();
        current.put(TWIN_SANITY_KEY, 0.0, subjectsAtALimit);
        for (Cell c : cells) {
            if (!Double.isNaN(c.medianTwin())) {
                current.put(ScenarioBaseline.key(c.kind(), c.subjectId()), c.medianTwin(), c.fail());
            }
        }
        Path file = ScenarioBaseline.defaultPath();
        boolean update = Boolean.getBoolean("azt1d.baseline.update");
        var recorded = update ? java.util.Optional.<ScenarioBaseline>empty() : ScenarioBaseline.load(file);
        if (recorded.isEmpty()) {
            current.save(file);
            System.out.println("baseline created at " + file);
            return;
        }
        assertThat(recorded.get().regressions(current)).as("regressions against %s", file).isEmpty();
    }

    private static double median(List<Double> xs) {
        if (xs.isEmpty()) return Double.NaN;
        double[] a = xs.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return a.length % 2 == 1 ? a[a.length / 2] : (a[a.length / 2 - 1] + a[a.length / 2]) / 2.0;
    }

    private static String render(List<Cell> cells, List<String> knownGaps) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n═══════ AZT1D FORECAST SCENARIOS (twin fitted on earlier days; error = |forecast - reality| at 120 min) ═══════\n");
        for (ScenarioKind kind : ScenarioKind.values()) {
            sb.append(String.format("%n%s%n%-11s %4s %5s %5s %5s %12s %14s%n", kind,
                    "subject", "n", "pass", "fail", "n/a", "median twin", "median neutral"));
            int n = 0, pass = 0, fail = 0, na = 0;
            for (Cell c : cells) {
                if (c.kind() != kind) continue;
                n += c.n(); pass += c.pass(); fail += c.fail(); na += c.notApplicable();
                sb.append(String.format("%-11s %4d %5d %5d %5d %12.2f %14.2f %s%n", c.subjectId(), c.n(), c.pass(),
                        c.fail(), c.notApplicable(), c.medianTwin(), c.medianNeutral(), c.fallbackProfile() ? "⚠fb" : ""));
            }
            sb.append(String.format("%-11s %4d %5d %5d %5d%n", "ALL", n, pass, fail, na));
        }
        sb.append("\n⚠fb = implausible insulin columns; the subject's profile fell back to population defaults.\n");
        sb.append(String.format("%nKNOWN GAPS (episodes failing their rule today): %d%n", knownGaps.size()));
        knownGaps.forEach(g -> sb.append("  ").append(g).append('\n'));
        return sb.toString();
    }
}
