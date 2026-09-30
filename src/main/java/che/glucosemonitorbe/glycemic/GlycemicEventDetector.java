package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.config.GlycemicEventProperties;
import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.InsulinDose;
import che.glucosemonitorbe.domain.RescueCarbProfile;
import che.glucosemonitorbe.domain.UserZones;
import che.glucosemonitorbe.dto.PredictionPointDTO;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.GlycemicEvent.Direction;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.hovorka.ActivityProvider;
import che.glucosemonitorbe.hovorka.HovorkaGlucosePredictionService;
import che.glucosemonitorbe.hovorka.HovorkaParameters;
import che.glucosemonitorbe.hovorka.NotesActivityProvider;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * In-memory detection pipeline (spec section 2): raw Hovorka forecast anchored at the window start ->
 * aligned residuals -> every sustained run -> onset -> feature vector. No persistence and no Spring, so
 * the offline validation harness can drive it with dataset data.
 */
public final class GlycemicEventDetector {

    public record CgmPoint(long epochMs, double mmol) {}

    /** Everything one user's scan needs. {@code end} is the user's local "now". */
    public record Input(UUID userId, ZoneId zone, LocalDateTime end, List<CgmPoint> cgm, List<Note> notes,
                        HovorkaParameters params, RapidInsulinIobParameters iob, UserSettingsDTO settings,
                        ToDoubleFunction<LocalDateTime> cobAt, boolean activityAware) {}

    public record Detection(ResidualRun run, int onsetIndex, long onsetMs, EventFeatures features,
                            ResidualSeries series) {
        public Direction direction() { return run.direction(); }
    }

    private final HovorkaGlucosePredictionService rawPredictor;
    private final GlycemicEventProperties props;

    public GlycemicEventDetector(HovorkaGlucosePredictionService rawPredictor, GlycemicEventProperties props) {
        this.rawPredictor = rawPredictor;
        this.props = props;
    }

    public List<Detection> detect(Input in) {
        long endMs = UserZones.toEpochMs(in.end(), in.zone());
        long startMs = endMs - props.getWindowMinutes() * 60_000L;
        List<CgmPoint> window = in.cgm().stream()
                .filter(p -> p.epochMs() >= startMs && p.epochMs() <= endMs)
                .sorted(Comparator.comparingLong(CgmPoint::epochMs))
                .toList();
        if (window.size() < props.getMinReadings()) return List.of();

        CgmPoint first = window.get(0);
        LocalDateTime anchor = UserZones.toWallTime(first.epochMs(), in.zone());
        List<PredictionPointDTO> predicted = predict(in, anchor, first.mmol(), props.getWindowMinutes(), n -> false);
        List<ResidualPoint> aligned = align(predicted, window, in.zone(), props.getAlignToleranceMs());
        if (aligned.size() < props.getMinReadings()) return List.of();

        ResidualSeries series = ResidualScanner.series(aligned);
        ResidualScanner scanner = new ResidualScanner(props.getSigmaMultiple(), props.getPersistenceMinutes(),
                props.getMaxGapMinutes(), props.getMinReadings());
        List<Detection> out = new ArrayList<>();
        for (ResidualRun run : scanner.runs(series)) {
            int onsetIdx = OnsetLocator.locate(series, run, props.getOnsetMaxBacktrackMinutes());
            long onsetMs = series.epochMs(onsetIdx);
            LocalDateTime onset = UserZones.toWallTime(onsetMs, in.zone());
            EventFeatures f = FeatureExtractor.extract(new FeatureExtractor.Input(series, run, onsetIdx, in.zone(),
                    in.notes(), in.iob(), in.params().isf(), in.cobAt().applyAsDouble(onset),
                    props.getMatchLeadMinutes()));
            out.add(new Detection(run, onsetIdx, onsetMs, f, series));
        }
        return out;
    }

    /**
     * Raw forecast from {@code anchor}. Boluses matching {@code dropBolus} are left out - the tail
     * resolver's no-insulin counterfactual.
     */
    public List<PredictionPointDTO> predict(Input in, LocalDateTime anchor, double g0, int minutes,
                                            Predicate<Note> dropBolus) {
        List<CarbsEntry> carbs = new ArrayList<>();
        List<InsulinDose> insulin = new ArrayList<>();
        List<Note> longActing = new ArrayList<>();
        for (Note n : in.notes()) {
            if (n.isLongActing()) { longActing.add(n); continue; }
            if (n.getCarbs() != null && n.getCarbs() > 0) carbs.add(toCarbsEntry(n));
            if (n.getInsulin() != null && n.getInsulin() > 0 && !dropBolus.test(n)) {
                insulin.add(InsulinDose.builder().timestamp(n.getTimestamp())
                        .units(n.getInsulin()).type(InsulinDose.InsulinType.BOLUS).build());
            }
        }
        // Activity-aware so a drop from a logged activity is explained, not flagged. Inert when logging is off.
        ActivityProvider activity = in.activityAware() ? NotesActivityProvider.fromNotes(in.notes()) : ActivityProvider.NONE;
        return rawPredictor.buildPredictionPath(in.params(), in.iob(), in.settings(), g0, anchor,
                carbs, insulin, longActing, in.userId(), minutes, activity);
    }

    /** Pairs each CGM point with the nearest prediction within {@code tolMs}; unmatched points are dropped. */
    public static List<ResidualPoint> align(List<PredictionPointDTO> predicted, List<CgmPoint> cgm,
                                            ZoneId zone, long tolMs) {
        TreeMap<Long, Double> byMs = new TreeMap<>();
        for (PredictionPointDTO p : predicted) {
            if (p.getPredictedGlucose() != null) byMs.put(UserZones.toEpochMs(p.getTimestamp(), zone), p.getPredictedGlucose());
        }
        List<ResidualPoint> out = new ArrayList<>();
        for (CgmPoint c : cgm) {
            Double p = nearest(byMs, c.epochMs(), tolMs);
            if (p != null) out.add(new ResidualPoint(c.epochMs(), c.mmol(), p));
        }
        return out;
    }

    /**
     * Note -> carb input for the forecast. A {@code hypo_treatment} note carries the rescue marker via
     * {@link RescueCarbProfile#mark}; left unmarked, a 15 g dextrose tablet would absorb on the 240-minute
     * mixed-meal curve, under-predict the recovery, and flag a logged hypo recovery as unlogged food.
     */
    public static CarbsEntry toCarbsEntry(Note n) {
        CarbsEntry entry = CarbsEntry.builder().timestamp(n.getTimestamp()).carbs(n.getCarbs()).build();
        return n.isHypoTreatment() ? RescueCarbProfile.mark(entry) : entry;
    }

    private static Double nearest(TreeMap<Long, Double> map, long target, long tolMs) {
        Map.Entry<Long, Double> floor = map.floorEntry(target), ceil = map.ceilingEntry(target);
        Map.Entry<Long, Double> best = null;
        long bestDiff = Long.MAX_VALUE;
        if (floor != null) { best = floor; bestDiff = target - floor.getKey(); }
        if (ceil != null && ceil.getKey() - target < bestDiff) { best = ceil; bestDiff = ceil.getKey() - target; }
        return best != null && bestDiff <= tolMs ? best.getValue() : null;
    }
}
