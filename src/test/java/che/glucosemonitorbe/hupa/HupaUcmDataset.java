package che.glucosemonitorbe.hupa;

import che.glucosemonitorbe.hovorka.learning.PredictionReplayEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Loader for the HUPA-UCM diabetes dataset (~25 Type-1 subjects). Mirrors {@code Azt1dDataset} so the
 * same digital-twin calibration harness can validate against a second, independent real-world cohort.
 *
 * <p>Uses the {@code Preprocessed/HUPA####P.csv} files - semicolon-delimited, 5-min cadence, header:
 * {@code time;glucose;calories;heart_rate;steps;basal_rate;bolus_volume_delivered;carb_input}.
 * {@code glucose} is mg/dL; {@code carb_input} (g) and {@code bolus_volume_delivered} (U) are the
 * meal/bolus events; {@code basal_rate} is U/h.</p>
 *
 * <h3>Modelling choices (identical to the AZT1D loader)</h3>
 * <ul>
 *   <li>CGM mg/dL -> mmol/L (÷18.0182).</li>
 *   <li>Rows with carbs and/or a bolus become {@link PredictionReplayEngine.Event}s.</li>
 *   <li>Continuous basal is <b>not</b> injected as insulin: the predictor assumes basal balances
 *       endogenous glucose at steady state; the residual layer absorbs its time-of-day modulation.</li>
 *   <li>Personal settings (weight, ISF, carb ratio) are not given, so they are estimated from the
 *       subject's own delivery aggregates exactly as for AZT1D (1800-rule ISF, model-scaled; weight
 *       from TDD; observed carb ratio).</li>
 * </ul>
 */
public final class HupaUcmDataset {

    private static final double MGDL_PER_MMOL = 18.0182;
    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final String SEP = ";";

    // -- Clinical seeding rules (mirror Azt1dDataset) --
    private static final double RULE_1800       = 1800.0;
    private static final double MODEL_ISF_SCALE  = 0.5;
    private static final double RULE_500        = 500.0;
    private static final double U_PER_KG_DAY    = 0.55;
    private static final double DEFAULT_ISF_MMOL = 2.2;
    private static final double DEFAULT_WEIGHT   = 70.0;
    private static final double MIN_WEIGHT       = 40.0;
    private static final double MAX_WEIGHT       = 120.0;

    private static final double FIVE_MIN_H       = 5.0 / 60.0;
    private static final double MAX_BASAL_RATE   = 10.0;
    private static final double MAX_SINGLE_BOLUS = 30.0;
    private static final double MIN_PLAUSIBLE_TDD = 15.0;
    private static final double MAX_PLAUSIBLE_TDD = 130.0;

    private static final int BREAKFAST_START = 5,  BREAKFAST_END = 11;
    private static final int LUNCH_START     = 11, LUNCH_END     = 16;
    private static final int DINNER_START    = 16, DINNER_END    = 22;
    private static final int MIN_WINDOW_MEALS = 3;
    private static final double WINDOW_RATIO_MIN = 0.6, WINDOW_RATIO_MAX = 1.6;

    private HupaUcmDataset() {}

    /** A per-row activity sample: heart rate [bpm] and steps in the 5-min interval. */
    public record ActivitySample(long epochMs, double hr, double steps) {}

    /** One subject's parsed CGM trace, event list, activity samples, and estimated personal profile. */
    public record Subject(String id,
                          List<PredictionReplayEngine.Reading> cgm,
                          List<PredictionReplayEngine.Event> events,
                          List<ActivitySample> activity,
                          Profile profile) {}

    /** Per-subject personal settings, estimated from the subject's own delivery data. */
    public record Profile(double days,
                          double tddU,
                          double basalRateUPerH,
                          double carbRatioGPerU,
                          double isfMmolPerU,
                          double weightKg,
                          boolean plausible,
                          double isfBreakfast,
                          double isfLunch,
                          double isfDinner) {

        static Profile defaults() {
            return new Profile(0, 0, 0, RULE_500 / Math.max(1, DEFAULT_WEIGHT * U_PER_KG_DAY),
                    DEFAULT_ISF_MMOL, DEFAULT_WEIGHT, false,
                    DEFAULT_ISF_MMOL, DEFAULT_ISF_MMOL, DEFAULT_ISF_MMOL);
        }
    }

    /** Load and parse a single subject CSV (header-driven column resolution). */
    public static Subject load(Path csv, String id) throws IOException {
        List<PredictionReplayEngine.Reading> cgm = new ArrayList<>();
        List<PredictionReplayEngine.Event> events = new ArrayList<>();
        List<ActivitySample> activity = new ArrayList<>();

        List<String> lines = Files.readAllLines(csv);
        if (lines.isEmpty()) return new Subject(id, cgm, events, activity, Profile.defaults());

        String[] header = lines.get(0).split(SEP, -1);
        int cTime  = indexOf(header, "time");
        int cCgm   = indexOf(header, "glucose");
        int cCarb  = indexOf(header, "carb_input");
        int cBolus = indexOf(header, "bolus_volume_delivered");
        int cBasal = indexOf(header, "basal_rate");
        int cHr    = indexOf(header, "heart_rate");
        int cSteps = indexOf(header, "steps");
        if (cTime < 0 || cCgm < 0) return new Subject(id, cgm, events, activity, Profile.defaults());

        double sumBasal = 0.0, sumBolus = 0.0;
        long firstEpoch = Long.MAX_VALUE, lastEpoch = Long.MIN_VALUE;
        List<Double> crSamples = new ArrayList<>();
        List<Double> crBreakfast = new ArrayList<>(), crLunch = new ArrayList<>(), crDinner = new ArrayList<>();

        int maxCol = Math.max(cTime, Math.max(cCgm, Math.max(cCarb, cBolus)));
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            String[] f = line.split(SEP, -1);
            if (f.length <= maxCol) continue;

            long epochMs = parseTs(f[cTime]);
            if (epochMs < 0) continue;
            firstEpoch = Math.min(firstEpoch, epochMs);
            lastEpoch  = Math.max(lastEpoch, epochMs);

            double cgmMgdl = parseD(f[cCgm]);
            if (cgmMgdl > 0) {
                cgm.add(new PredictionReplayEngine.Reading(epochMs, cgmMgdl / MGDL_PER_MMOL));
            }

            double carbs   = cCarb  >= 0 ? parseD(f[cCarb])  : 0.0;
            double insulin = cBolus >= 0 ? parseD(f[cBolus]) : 0.0;
            if (carbs > 0 || insulin > 0) {
                events.add(new PredictionReplayEngine.Event(
                        epochMs, Math.max(0, carbs), Math.max(0, insulin),
                        false, 0.0, 0.0, 0.0));
            }

            if (insulin > 0 && insulin <= MAX_SINGLE_BOLUS) sumBolus += insulin;
            if (cBasal >= 0 && cBasal < f.length) {
                double bRate = parseD(f[cBasal]);
                if (bRate > 0 && bRate <= MAX_BASAL_RATE) sumBasal += bRate * FIVE_MIN_H;
            }
            // Observed carb ratio [g/U] from a same-row meal bolus.
            if (carbs > 0 && insulin > 0 && insulin <= MAX_SINGLE_BOLUS) {
                double cr = carbs / insulin;
                crSamples.add(cr);
                int hour = (int) ((epochMs / 3_600_000L) % 24L);
                if (hour >= BREAKFAST_START && hour < BREAKFAST_END)  crBreakfast.add(cr);
                else if (hour >= LUNCH_START && hour < LUNCH_END)     crLunch.add(cr);
                else if (hour >= DINNER_START && hour < DINNER_END)   crDinner.add(cr);
            }

            double hr    = (cHr    >= 0 && cHr    < f.length) ? parseD(f[cHr])    : 0.0;
            double steps = (cSteps >= 0 && cSteps < f.length) ? parseD(f[cSteps]) : 0.0;
            if (hr > 0 || steps > 0) activity.add(new ActivitySample(epochMs, hr, steps));
        }
        return new Subject(id, cgm, events, activity, estimateProfile(
                sumBasal, sumBolus, firstEpoch, lastEpoch, crSamples, crBreakfast, crLunch, crDinner));
    }

    private static Profile estimateProfile(double sumBasalUnits, double sumBolusUnits,
                                           long firstEpoch, long lastEpoch, List<Double> crSamples,
                                           List<Double> crBreakfast, List<Double> crLunch, List<Double> crDinner) {
        if (firstEpoch > lastEpoch) return Profile.defaults();
        double days = Math.max(1.0, (lastEpoch - firstEpoch) / 86_400_000.0);
        double tdd = (sumBasalUnits + sumBolusUnits) / days;
        double basalRate = sumBasalUnits / (days * 24.0);

        double cr = !crSamples.isEmpty() ? median(crSamples)
                : (tdd > 0 ? RULE_500 / tdd : Double.NaN);

        boolean plausible = tdd >= MIN_PLAUSIBLE_TDD && tdd <= MAX_PLAUSIBLE_TDD;
        double isf = plausible ? (RULE_1800 / tdd) / MGDL_PER_MMOL * MODEL_ISF_SCALE : DEFAULT_ISF_MMOL;
        double weight = plausible
                ? Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT, tdd / U_PER_KG_DAY))
                : DEFAULT_WEIGHT;

        double isfB = windowIsf(isf, crBreakfast, cr);
        double isfL = windowIsf(isf, crLunch, cr);
        double isfD = windowIsf(isf, crDinner, cr);
        return new Profile(days, tdd, basalRate, cr, isf, weight, plausible, isfB, isfL, isfD);
    }

    private static double windowIsf(double dailyIsf, List<Double> windowCr, double overallCr) {
        if (windowCr.size() < MIN_WINDOW_MEALS || !(overallCr > 0)) return dailyIsf;
        double ratio = median(windowCr) / overallCr;
        ratio = Math.max(WINDOW_RATIO_MIN, Math.min(WINDOW_RATIO_MAX, ratio));
        return dailyIsf * ratio;
    }

    private static double median(List<Double> xs) {
        List<Double> s = new ArrayList<>(xs);
        s.sort(Double::compareTo);
        int n = s.size();
        return n == 0 ? Double.NaN
                : (n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0);
    }

    private static int indexOf(String[] header, String name) {
        for (int i = 0; i < header.length; i++) {
            if (header[i].trim().equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    /** Load every {@code Preprocessed/HUPA####P.csv} under the dataset root, ordered by filename. */
    public static List<Subject> loadAll(Path datasetRoot) throws IOException {
        Path dir = datasetRoot.resolve("Preprocessed");
        List<Subject> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = stream
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".csv"))
                    .sorted()
                    .toList();
            for (Path csv : files) {
                String id = csv.getFileName().toString().replaceFirst("(?i)\\.csv$", "");
                out.add(load(csv, id));
            }
        }
        return out;
    }

    private static long parseTs(String s) {
        try {
            return LocalDateTime.parse(s.trim(), TS).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (Exception e) {
            return -1;
        }
    }

    private static double parseD(String s) {
        if (s == null) return 0.0;
        s = s.trim();
        if (s.isEmpty()) return 0.0;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
