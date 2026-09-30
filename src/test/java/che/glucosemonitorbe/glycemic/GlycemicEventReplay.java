package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.entity.GlycemicEvent.Category;
import che.glucosemonitorbe.entity.GlycemicEvent.Direction;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.glycemic.GlycemicEventDetector.CgmPoint;
import che.glucosemonitorbe.hovorka.HovorkaParameters;
import che.glucosemonitorbe.hovorka.learning.PredictionReplayEngine;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Drives the detector + rule classifier over a dataset record by sliding the scan end time. No DB. */
public final class GlycemicEventReplay {

    public record Found(Direction direction, Category category, long onsetMs, double confidence) {}

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final long DEDUPE_MS = 30 * 60_000L;

    private final GlycemicEventDetector detector;
    private final EventClassifier classifier;
    private final HovorkaParameters params;
    private final RapidInsulinIobParameters iob;
    private final int windowMinutes;

    public GlycemicEventReplay(GlycemicEventDetector detector, EventClassifier classifier, HovorkaParameters params,
                               RapidInsulinIobParameters iob, int windowMinutes) {
        this.detector = detector;
        this.classifier = classifier;
        this.params = params;
        this.iob = iob;
        this.windowMinutes = windowMinutes;
    }

    public List<Found> scan(List<CgmPoint> cgm, List<Note> notes, long fromMs, long toMs, long strideMs) {
        List<Found> found = new ArrayList<>();
        UserSettingsDTO settings = null;   // same as the calibration harnesses: population defaults
        long windowMs = windowMinutes * 60_000L, warmupMs = 8 * 3_600_000L;
        for (long end = fromMs; end <= toMs; end += strideMs) {
            long lo = end - windowMs, noteLo = lo - warmupMs, hi = end;
            List<CgmPoint> windowCgm = cgm.stream().filter(p -> p.epochMs() >= lo && p.epochMs() <= hi).toList();
            List<Note> windowNotes = notes.stream()
                    .filter(n -> { long t = ms(n.getTimestamp()); return t >= noteLo && t <= hi; }).toList();
            GlycemicEventDetector.Input in = new GlycemicEventDetector.Input(USER, ZoneOffset.UTC, toLdt(end),
                    windowCgm, windowNotes, params, iob, settings, t -> linearCob(windowNotes, t), false);
            for (GlycemicEventDetector.Detection d : detector.detect(in)) {
                boolean seen = found.stream().anyMatch(f -> f.direction() == d.direction()
                        && Math.abs(f.onsetMs() - d.onsetMs()) <= DEDUPE_MS);
                if (seen) continue;
                EventClassifier.Classification c = classifier.classify(d.direction(), d.features());
                found.add(new Found(d.direction(), c.category(), d.onsetMs(),
                        EventClassifier.confidence(c.baseConfidence(), d.features().sigmaMultiple())));
            }
        }
        return found;
    }

    public static List<Note> toNotes(List<PredictionReplayEngine.Event> events) {
        List<Note> out = new ArrayList<>();
        for (PredictionReplayEngine.Event e : events) {
            Note n = new Note();
            n.setTimestamp(toLdt(e.epochMs()));
            n.setCarbs(e.carbs());
            n.setInsulin(e.insulin());
            n.setType(e.longActing() ? Note.TYPE_LONG_ACTING : e.rescue() ? Note.TYPE_HYPO_TREATMENT : Note.TYPE_NORMAL);
            out.add(n);
        }
        return out;
    }

    /** Linear 240-minute carb decay - a deliberate simplification for replay (stated in the report). */
    static double linearCob(List<Note> notes, LocalDateTime t) {
        double cob = 0.0;
        for (Note n : notes) {
            if (n.getCarbs() == null || n.getCarbs() <= 0 || n.getTimestamp() == null) continue;
            double minsAgo = Duration.between(n.getTimestamp(), t).toMinutes();
            if (minsAgo >= 0 && minsAgo < 240) cob += n.getCarbs() * (1 - minsAgo / 240.0);
        }
        return cob;
    }

    static LocalDateTime toLdt(long ms) { return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC); }

    static long ms(LocalDateTime t) { return t.toInstant(ZoneOffset.UTC).toEpochMilli(); }
}
