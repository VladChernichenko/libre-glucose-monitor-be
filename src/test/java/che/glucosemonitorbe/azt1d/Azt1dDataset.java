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
 * ({@code CarbSize}, g) and boluses ({@code TotalBolusInsulinDelivered}, U — includes the pump's
 * automatic corrections) are sparse event rows.</p>
 *
 * <h3>Modelling choices</h3>
 * <ul>
 *   <li>CGM mg/dL → mmol/L (÷18.0182).</li>
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

    private Azt1dDataset() {}

    /** One subject's parsed CGM trace and event list. */
    public record Subject(String id,
                          List<PredictionReplayEngine.Reading> cgm,
                          List<PredictionReplayEngine.Event> events) {}

    /**
     * Load and parse a single subject CSV. Column positions are resolved from the header rather than
     * hard-coded, because subjects use two different column orderings and CGM label variants
     * ({@code CGM} vs {@code Readings (CGM / BGM)}).
     */
    public static Subject load(Path csv, String id) throws IOException {
        List<PredictionReplayEngine.Reading> cgm = new ArrayList<>();
        List<PredictionReplayEngine.Event> events = new ArrayList<>();

        List<String> lines = Files.readAllLines(csv);
        if (lines.isEmpty()) return new Subject(id, cgm, events);

        String[] header = lines.get(0).split(",", -1);
        int cTime  = indexOf(header, h -> h.equalsIgnoreCase("EventDateTime"));
        int cCgm   = indexOf(header, h -> h.equalsIgnoreCase("CGM") || h.toUpperCase().contains("CGM"));
        int cCarb  = indexOf(header, h -> h.equalsIgnoreCase("CarbSize"));
        int cBolus = indexOf(header, h -> h.equalsIgnoreCase("TotalBolusInsulinDelivered"));
        if (cTime < 0 || cCgm < 0) return new Subject(id, cgm, events);

        int maxCol = Math.max(cTime, Math.max(cCgm, Math.max(cCarb, cBolus)));
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            String[] f = line.split(",", -1);
            if (f.length <= maxCol) continue;

            long epochMs = parseTs(f[cTime]);
            if (epochMs < 0) continue;

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
        }
        return new Subject(id, cgm, events);
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
