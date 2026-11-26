package che.glucosemonitorbe.backtest;

import che.glucosemonitorbe.azt1d.Azt1dDataset;
import che.glucosemonitorbe.azt1d.Azt1dReplayData;
import che.glucosemonitorbe.backtest.MealReplayHarness.Data;
import che.glucosemonitorbe.backtest.MealReplayHarness.Mode;
import che.glucosemonitorbe.backtest.MealReplayHarness.Options;
import che.glucosemonitorbe.backtest.MealReplayHarness.Report;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Baseline reports of the live forecast on real data, Dalla Man and Gamma absorption side by side.
 * Data-gated: each test skips when its data is absent. Real data never enters the repository.
 *
 * <pre>
 * ./gradlew test --tests '*MealReplayBaselineTest' -i \
 *     -Dreplay.dir=$HOME/replay-data/&lt;user&gt; \
 *     -Dazt1d.dir="/path/to/AZT1D 2025" [-Dreplay.azt1dDays=14]
 * </pre>
 * {@code replay.dir} is an export of {@code scripts/export-replay-data.sh}. Scored from
 * {@link #EVAL_FROM} on (the period success is judged on); lows over the whole history.
 * {@code replay.azt1dDays} keeps the last N days of each AZT1D subject (default 14) to bound the run.
 */
class MealReplayBaselineTest {

    static final LocalDate EVAL_FROM = LocalDate.of(2026, 11, 1);
    private static final LocalDate FAR_PAST = LocalDate.of(2000, 1, 1);
    private static final LocalDate FAR_FUTURE = LocalDate.of(2100, 1, 1);

    @Test
    void userExport() throws Exception {
        String dir = System.getProperty("replay.dir");
        Assumptions.assumeTrue(dir != null && Files.isDirectory(Path.of(dir)), "replay.dir not set");
        Data d = MealReplayHarness.load(Path.of(dir));
        for (boolean gamma : new boolean[]{false, true}) {
            MealReplayHarness.Forecaster f = MealReplayHarness.hovorka(d, gamma);
            String model = gamma ? "Gamma" : "Dalla Man";
            for (Mode mode : Mode.values()) {
                Report r = MealReplayHarness.run(d, f, new Options(mode, EVAL_FROM, FAR_FUTURE,
                        MealReplayHarness.MealContextFn.NONE));
                System.out.printf("%n=== user export, %s, %s, from %s ===%n%s", model, mode, EVAL_FROM, r.render());
            }
            Report all = MealReplayHarness.run(d, f, new Options(Mode.AS_LOGGED, FAR_PAST, FAR_FUTURE,
                    MealReplayHarness.MealContextFn.NONE));
            System.out.printf("%n=== user export, %s, AS_LOGGED, whole history (lows) ===%n%s", model, all.render());
        }
    }

    @Test
    void azt1d() throws Exception {
        String dir = System.getProperty("azt1d.dir");
        Assumptions.assumeTrue(dir != null && Files.isDirectory(Path.of(dir)), "azt1d.dir not set");
        int days = Integer.getInteger("replay.azt1dDays", 14);
        List<Azt1dDataset.Subject> subjects = Azt1dDataset.loadAll(Path.of(dir));
        Map<String, List<Azt1dDataset.BolusRecord>> logs = Azt1dDataset.loadAllBolusLogs(Path.of(dir));
        for (boolean gamma : new boolean[]{false, true}) {
            String model = gamma ? "Gamma" : "Dalla Man";
            for (Mode mode : Mode.values()) {
                Report pooled = new Report();
                int used = 0;
                for (Azt1dDataset.Subject s : subjects) {
                    if (!s.profile().plausible() || s.cgm().isEmpty()) continue;
                    Data d = Azt1dReplayData.of(s, logs.getOrDefault(s.id(), List.of()));
                    LocalDate last = d.notes().isEmpty() ? FAR_PAST
                            : d.notes().get(d.notes().size() - 1).getTimestamp().toLocalDate();
                    Report r = MealReplayHarness.run(d, MealReplayHarness.hovorka(d, gamma),
                            new Options(mode, last.minusDays(days), last.plusDays(1), MealReplayHarness.MealContextFn.NONE));
                    if (mode == Mode.AS_LOGGED) System.out.printf("%n--- %s, %s, %s ---%n%s", s.id(), model, mode, r.render());
                    pooled.merge(r);
                    used++;
                }
                System.out.printf("%n=== AZT1D pooled (%d subjects, last %d days), %s, %s ===%n%s",
                        used, days, model, mode, pooled.render());
            }
        }
    }
}
