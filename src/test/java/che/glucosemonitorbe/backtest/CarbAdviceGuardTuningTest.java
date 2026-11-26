package che.glucosemonitorbe.backtest;

import che.glucosemonitorbe.assistant.advice.CarbAdviceTrigger;
import che.glucosemonitorbe.assistant.advice.MealContext;
import che.glucosemonitorbe.azt1d.Azt1dDataset;
import che.glucosemonitorbe.azt1d.Azt1dReplayData;
import che.glucosemonitorbe.backtest.MealReplayHarness.Data;
import che.glucosemonitorbe.backtest.MealReplayHarness.Forecaster;
import che.glucosemonitorbe.backtest.MealReplayHarness.Mode;
import che.glucosemonitorbe.backtest.MealReplayHarness.Options;
import che.glucosemonitorbe.backtest.MealReplayHarness.Report;
import che.glucosemonitorbe.backtest.MealReplayHarness.Trigger;
import che.glucosemonitorbe.service.observer.HypoThresholds;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tunes the carb-advice guard's confirmation thresholds (reading and trend) on tuning data only:
 * AZT1D (last 14 days per subject) and the user's export before {@link MealReplayBaselineTest#EVAL_FROM}.
 * For each pair it prints the drop in meal-anchor false advice, lows newly missed, and lows that lost
 * a ≥ 15-min lead, against the unguarded rule. Gamma absorption, as in production. Data-gated.
 */
class CarbAdviceGuardTuningTest {

    private static final double[] READINGS = {4.5, 5.0, 5.5, 6.0};
    private static final double[] TRENDS = {-0.10, -0.06, -0.03};

    private record Set(Data d, Forecaster f, LocalDate from, LocalDate to) {}

    @Test
    void tuneConfirmation() throws Exception {
        String azt = System.getProperty("azt1d.dir");
        String user = System.getProperty("replay.dir");
        Assumptions.assumeTrue(azt != null && Files.isDirectory(Path.of(azt)), "azt1d.dir not set");

        List<Set> aztSets = new ArrayList<>();
        List<Azt1dDataset.Subject> subjects = Azt1dDataset.loadAll(Path.of(azt));
        Map<String, List<Azt1dDataset.BolusRecord>> logs = Azt1dDataset.loadAllBolusLogs(Path.of(azt));
        for (Azt1dDataset.Subject s : subjects) {
            if (!s.profile().plausible() || s.cgm().isEmpty()) continue;
            Data d = Azt1dReplayData.of(s, logs.getOrDefault(s.id(), List.of()));
            LocalDate last = d.notes().get(d.notes().size() - 1).getTimestamp().toLocalDate();
            aztSets.add(new Set(d, MealReplayHarness.hovorka(d, true), last.minusDays(14), last.plusDays(1)));
        }
        List<Set> userSets = new ArrayList<>();
        if (user != null && Files.isDirectory(Path.of(user))) {
            Data d = MealReplayHarness.load(Path.of(user));
            userSets.add(new Set(d, MealReplayHarness.hovorka(d, true), LocalDate.of(2000, 1, 1),
                    MealReplayBaselineTest.EVAL_FROM));
        }

        Report aztNone = run(aztSets, MealReplayHarness.MealContextFn.NONE, Trigger.LIVE);
        Report userNone = run(userSets, MealReplayHarness.MealContextFn.NONE, Trigger.LIVE);
        for (double reading : READINGS) {
            for (double trend : TRENDS) {
                Trigger t = guarded(reading, trend);
                Report aztG = run(aztSets, MealContext::at, t);
                Report userG = run(userSets, MealContext::at, t);
                System.out.printf("reading<=%.1f trend<=%.2f | AZT1D %s | user<Nov %s%n",
                        reading, trend, summary(aztNone, aztG), summary(userNone, userG));
            }
        }
    }

    /** The guarded rule with the given confirmation thresholds (the fast-fall path unchanged). */
    static Trigger guarded(double confirmReading, double confirmTrend) {
        return (now, g0, trend, forecast, mealContext) -> {
            if (!mealContext) return CarbAdviceTrigger.fires(now, trend, forecast);
            boolean confirmed = trend <= confirmTrend || g0 <= confirmReading;
            boolean hypo60 = confirmed && CarbAdviceTrigger.minimum(forecast, now, 60)
                    .map(p -> p.getPredictedGlucose() < HypoThresholds.HYPO_MMOL).orElse(false);
            boolean fastFall = trend <= CarbAdviceTrigger.FALL_RATE && CarbAdviceTrigger.minimum(forecast, now, 90)
                    .map(p -> p.getPredictedGlucose() < HypoThresholds.HYPO_MMOL).orElse(false);
            return hypo60 || fastFall;
        };
    }

    private static Report run(List<Set> sets, MealReplayHarness.MealContextFn ctx, Trigger t) {
        Report pooled = new Report();
        for (Set s : sets) pooled.merge(MealReplayHarness.run(s.d(), s.f(), new Options(Mode.AS_LOGGED, s.from(), s.to(), ctx, t)));
        return pooled;
    }

    private static String summary(Report none, Report guarded) {
        int before = none.falseAdvice(MealReplayHarness.Bucket.MEAL), after = guarded.falseAdvice(MealReplayHarness.Bucket.MEAL);
        int missed = 0, leadLost = 0;
        for (int i = 0; i < Math.min(none.lows().size(), guarded.lows().size()); i++) {
            Integer a = none.lows().get(i).leadMin(), b = guarded.lows().get(i).leadMin();
            if (a != null && b == null) missed++;
            if (a != null && a >= 15 && (b == null || b < 15)) leadLost++;
        }
        double drop = before == 0 ? 0 : 100.0 * (before - after) / before;
        return String.format("meal false %d->%d (-%.0f%%), newly missed %d, lead lost %d",
                before, after, drop, missed, leadLost);
    }
}
