package che.glucosemonitorbe.azt1d;

import che.glucosemonitorbe.hovorka.learning.PredictionReplayEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Loader for the AZT1D 2025 dataset (25 real Type-1 subjects on automated insulin delivery).
 *
 * <p>Each subject CSV has columns:
 * {@code EventDateTime, DeviceMode, BolusType, Basal, CorrectionDelivered,
 * TotalBolusInsulinDelivered, FoodDelivered, CarbSize, CGM}. CGM is 5-min mg/dL; carbs
 * ({@code CarbSize}, g) and boluses ({@code TotalBolusInsulinDelivered}, U - includes the pump's
 * automatic corrections) are sparse event rows.</p>
 *
 * <h3>Modelling choices</h3>
 * <ul>
 *   <li>CGM mg/dL -> mmol/L (÷18.0182).</li>
 *   <li>Discrete meals + boluses become {@link PredictionReplayEngine.Event}s.</li>
 *   <li>The continuous auto-{@code Basal} rate is <b>not</b> injected as insulin: the predictor
 *       already assumes basal balances endogenous glucose at steady state, and the residual layer
 *       absorbs the AID system's average time-of-day modulation. (Treating every 5-min basal tick as
 *       an extra rapid dose on top of that steady-state assumption would double-count suppression.)</li>
 *   <li>Times are local (Arizona, no DST); parsed as a stable epoch so relative timing and
 *       hour-of-day are preserved.</li>
 * </ul>
 */
public final class Azt1dDataset {

    private static final double MGDL_PER_MMOL = 18.0182;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // -- Clinical seeding rules (used to derive personal settings the dataset does not provide) --
    /** Correction factor "1800 rule": ISF [mg/dL per U] ≈ 1800 / TDD (rapid-acting analog). */
    private static final double RULE_1800     = 1800.0;
    /**
     * Scales the clinical 1800-rule ISF to the Hovorka model's {@code isf} parameter. Empirically the
     * raw 1800-rule (in mmol/L) overshoots the model ISF ~2×: seeded raw, the twin drove its learned
     * {@code isf×} to the 0.5 calibrator floor for nearly every AZT1D subject. This factor makes the
     * seed model-consistent so the un-calibrated baseline is competitive and the twin de-pins from 0.5.
     */
    private static final double MODEL_ISF_SCALE = 0.5;
    /** "500 rule": carb ratio [g per U] ≈ 500 / TDD - fallback only, when CR can't be observed. */
    private static final double RULE_500      = 500.0;
    /** Typical Type-1 total daily insulin per kg [U/kg/day] - inverts TDD -> body weight. */
    private static final double U_PER_KG_DAY  = 0.55;
    private static final double DEFAULT_ISF_MMOL = 2.2;   // matches HovorkaParameterService default
    private static final double DEFAULT_WEIGHT   = 70.0;  // population fallback
    private static final double MIN_WEIGHT       = 40.0;
    private static final double MAX_WEIGHT       = 120.0;

    // -- Delivery-column handling (the AZT1D insulin columns are noisy) --
    /** {@code Basal} is a rate [U/h] sampled every 5 min; delivered units = rate × (5/60) h. */
    private static final double FIVE_MIN_H        = 5.0 / 60.0;
    /** Reject basal-rate rows above this as corrupt (real basal rarely exceeds ~4 U/h; data has 1900+). */
    private static final double MAX_BASAL_RATE    = 10.0;
    /** Reject single boluses above this as corrupt (physiological single dose cap). */
    private static final double MAX_SINGLE_BOLUS  = 30.0;
    /** Plausible Type-1 TDD band [U/day]; outside it the profile is flagged and falls back to defaults. */
    private static final double MIN_PLAUSIBLE_TDD = 15.0;
    private static final double MAX_PLAUSIBLE_TDD = 130.0;

    // -- Meal-window ISF (matches user_settings.isf_breakfast/lunch/dinner windows, local hour) --
    private static final int BREAKFAST_START = 5,  BREAKFAST_END = 11;   // 05:00-11:00
    private static final int LUNCH_START     = 11, LUNCH_END     = 16;   // 11:00-16:00
    private static final int DINNER_START    = 16, DINNER_END    = 22;   // 16:00-22:00
    /** Minimum meals observed in a window before its carb ratio is trusted to modulate ISF. */
    private static final int MIN_WINDOW_MEALS = 3;
    /** Clamp the per-window sensitivity ratio so a noisy window CR can't produce an extreme ISF. */
    private static final double WINDOW_RATIO_MIN = 0.6, WINDOW_RATIO_MAX = 1.6;

    private Azt1dDataset() {}

    /** One subject's parsed CGM trace, event list, and estimated personal profile. */
    public record Subject(String id,
                          List<PredictionReplayEngine.Reading> cgm,
                          List<PredictionReplayEngine.Event> events,
                          Profile profile) {}

    /**
     * Per-subject personal settings, estimated from the subject's <em>own</em> AID delivery data -
     * a "digital profile" seeded the way a clinician would before any twin calibration. None of these
     * are given in the AZT1D CSV; they are derived from delivered basal, total boluses, and the
     * carb-to-food-bolus ratio the pump used.
     *
     * @param days           record span in days
     * @param tddU           total daily dose (basal + bolus) [U/day]
     * @param basalRateUPerH mean basal delivery rate [U/h]
     * @param carbRatioGPerU carb ratio [g/U] - observed median of CarbSize/FoodDelivered, else 500-rule
     * @param isfMmolPerU    insulin sensitivity factor [mmol/L per U] - 1800-rule from TDD
     * @param weightKg       body weight [kg] - inverted from TDD (≈0.55 U/kg/day), clamped [40,120]
     * @param plausible      false when TDD fell outside the plausible band (corrupt insulin columns) -
     *                       ISF and weight then fall back to population defaults rather than garbage
     * @param isfBreakfast   ISF for 05:00-11:00 [mmol/L per U] = daily ISF × (window CR / overall CR)
     * @param isfLunch       ISF for 11:00-16:00 [mmol/L per U] (daily ISF when the window lacks meals)
     * @param isfDinner      ISF for 16:00-22:00 [mmol/L per U] (daily ISF when the window lacks meals)
     */
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

        /** Neutral profile for subjects with no usable delivery data (they are skipped downstream). */
        static Profile defaults() {
            return new Profile(0, 0, 0, RULE_500 / Math.max(1, DEFAULT_WEIGHT * U_PER_KG_DAY),
                    DEFAULT_ISF_MMOL, DEFAULT_WEIGHT, false,
                    DEFAULT_ISF_MMOL, DEFAULT_ISF_MMOL, DEFAULT_ISF_MMOL);
        }
    }

    /**
     * Load and parse a single subject CSV. Column positions are resolved from the header rather than
     * hard-coded, because subjects use two different column orderings and CGM label variants
     * ({@code CGM} vs {@code Readings (CGM / BGM)}).
     */
    public static Subject load(Path csv, String id) throws IOException {
        List<PredictionReplayEngine.Reading> cgm = new ArrayList<>();
        List<PredictionReplayEngine.Event> events = new ArrayList<>();

        List<String> lines = Files.readAllLines(csv);
        if (lines.isEmpty()) return new Subject(id, cgm, events, Profile.defaults());

        String[] header = lines.get(0).split(",", -1);
        int cTime  = indexOf(header, h -> h.equalsIgnoreCase("EventDateTime"));
        int cCgm   = indexOf(header, h -> h.equalsIgnoreCase("CGM") || h.toUpperCase().contains("CGM"));
        int cCarb  = indexOf(header, h -> h.equalsIgnoreCase("CarbSize"));
        int cBolus = indexOf(header, h -> h.equalsIgnoreCase("TotalBolusInsulinDelivered"));
        int cBasal = indexOf(header, h -> h.equalsIgnoreCase("Basal"));
        int cFood  = indexOf(header, h -> h.equalsIgnoreCase("FoodDelivered"));
        if (cTime < 0 || cCgm < 0) return new Subject(id, cgm, events, Profile.defaults());

        // Profile aggregates - accumulated while parsing so we make a single pass over the file.
        double sumBasal = 0.0, sumBolus = 0.0;
        long firstEpoch = Long.MAX_VALUE, lastEpoch = Long.MIN_VALUE;
        List<Double> crSamples = new ArrayList<>();
        List<Double> crBreakfast = new ArrayList<>(), crLunch = new ArrayList<>(), crDinner = new ArrayList<>();

        int maxCol = Math.max(cTime, Math.max(cCgm, Math.max(cCarb, cBolus)));
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            String[] f = line.split(",", -1);
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

            // -- Personal-settings aggregates (outlier-filtered) ---------------
            if (insulin > 0 && insulin <= MAX_SINGLE_BOLUS) sumBolus += insulin;
            if (cBasal >= 0 && cBasal < f.length) {
                double bRate = parseD(f[cBasal]);                     // U/h at this timestamp
                if (bRate > 0 && bRate <= MAX_BASAL_RATE) sumBasal += bRate * FIVE_MIN_H;  // -> delivered U
            }
            double food = (cFood >= 0 && cFood < f.length) ? parseD(f[cFood]) : 0.0;
            if (food > 0 && carbs > 0) {
                double cr = carbs / food;                            // observed carb ratio [g/U]
                crSamples.add(cr);
                int hour = (int) ((epochMs / 3_600_000L) % 24L);     // local hour (times encoded as UTC)
                if (hour >= BREAKFAST_START && hour < BREAKFAST_END)  crBreakfast.add(cr);
                else if (hour >= LUNCH_START && hour < LUNCH_END)     crLunch.add(cr);
                else if (hour >= DINNER_START && hour < DINNER_END)   crDinner.add(cr);
            }
        }
        return new Subject(id, cgm, events, estimateProfile(
                sumBasal, sumBolus, firstEpoch, lastEpoch, crSamples, crBreakfast, crLunch, crDinner));
    }

    /**
     * Derive a subject's personal settings from their delivery aggregates. TDD drives both the
     * body-weight estimate (≈0.55 U/kg/day) and the ISF (1800-rule); the carb ratio is taken from the
     * observed carb-to-food-bolus ratio when meals exist, otherwise the 500-rule.
     */
    private static Profile estimateProfile(double sumBasalUnits, double sumBolusUnits,
                                           long firstEpoch, long lastEpoch, List<Double> crSamples,
                                           List<Double> crBreakfast, List<Double> crLunch, List<Double> crDinner) {
        if (firstEpoch > lastEpoch) return Profile.defaults();
        double days = Math.max(1.0, (lastEpoch - firstEpoch) / 86_400_000.0);
        double tdd = (sumBasalUnits + sumBolusUnits) / days;
        double basalRate = sumBasalUnits / (days * 24.0);

        // Carb ratio comes from the pump's own CarbSize/FoodDelivered (a cleaner signal than TDD), so
        // it is kept even when TDD is corrupt; the 500-rule is only a last resort.
        double cr = !crSamples.isEmpty() ? median(crSamples)
                : (tdd > 0 ? RULE_500 / tdd : Double.NaN);

        boolean plausible = tdd >= MIN_PLAUSIBLE_TDD && tdd <= MAX_PLAUSIBLE_TDD;
        double isf = plausible ? (RULE_1800 / tdd) / MGDL_PER_MMOL * MODEL_ISF_SCALE : DEFAULT_ISF_MMOL;
        double weight = plausible
                ? Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT, tdd / U_PER_KG_DAY))
                : DEFAULT_WEIGHT;

        // Per-window ISF: modulate the daily ISF by how each window's observed carb ratio (insulin
        // sensitivity to carbs) compares to the whole-day ratio - captures dawn resistance etc.
        double isfB = windowIsf(isf, crBreakfast, cr);
        double isfL = windowIsf(isf, crLunch, cr);
        double isfD = windowIsf(isf, crDinner, cr);
        return new Profile(days, tdd, basalRate, cr, isf, weight, plausible, isfB, isfL, isfD);
    }

    /**
     * Window ISF = daily ISF scaled by the window's carb-ratio relative to the whole-day carb-ratio
     * (both track insulin sensitivity, so they move together). Falls back to the daily ISF when the
     * window has too few meals to trust, or when the overall ratio is unavailable.
     */
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

    private static int indexOf(String[] header, java.util.function.Predicate<String> match) {
        for (int i = 0; i < header.length; i++) {
            if (match.test(header[i].trim())) return i;
        }
        return -1;
    }

    /**
     * Load every {@code CGM Records/Subject N/Subject N.csv} under the dataset root, ordered by
     * subject number.
     */
    public static List<Subject> loadAll(Path datasetRoot) throws IOException {
        Path recordsDir = datasetRoot.resolve("CGM Records");
        List<Subject> out = new ArrayList<>();
        for (int n = 1; n <= 25; n++) {
            Path csv = recordsDir.resolve("Subject " + n).resolve("Subject " + n + ".csv");
            if (Files.exists(csv)) {
                out.add(load(csv, "Subject " + n));
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
