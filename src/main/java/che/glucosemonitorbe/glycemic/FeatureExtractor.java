package che.glucosemonitorbe.glycemic;

import che.glucosemonitorbe.domain.UserZones;
import che.glucosemonitorbe.dto.RapidInsulinIobParameters;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.service.InsulinCalculatorService;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the {@link EventFeatures} vector for a run at its onset (spec sections 1.3, 2.3). Pure: uses only
 * the scan's already-loaded notes and residual series. "Significant IOB" is derived from
 * {@code expectedDrop60 = ISF x (IOB(onset) - IOB(onset + 60 min))} over logged rapid boluses.
 */
public final class FeatureExtractor {

    private static final int SLOPE_WINDOW_MIN = 30;
    private static final int ACTIVITY_WINDOW_MIN = 30;

    public record Input(ResidualSeries series, ResidualRun run, int onsetIndex, ZoneId zone,
                        List<Note> notes, RapidInsulinIobParameters iob, double isfMmolPerU,
                        double cobAtOnsetGrams, int matchLeadMinutes) {}

    private FeatureExtractor() {}

    public static EventFeatures extract(Input in) {
        ResidualSeries s = in.series();
        ResidualRun run = in.run();
        long onsetMs = s.epochMs(in.onsetIndex());
        LocalDateTime onset = UserZones.toWallTime(onsetMs, in.zone());
        LocalDateTime windowEnd = UserZones.toWallTime(run.endMs(), in.zone());
        LocalDateTime matchFrom = onset.minusMinutes(in.matchLeadMinutes());

        double iobAtOnset = 0.0, iobDrop = 0.0;
        Double minSinceCarbs = null, minSinceBolus = null;
        boolean carbsLogged = false, insulinLogged = false, activity = false;
        for (Note n : in.notes()) {
            LocalDateTime t = n.getTimestamp();
            if (t == null) continue;
            double minsAgo = Duration.between(t, onset).toSeconds() / 60.0;
            boolean inMatch = !t.isBefore(matchFrom) && !t.isAfter(windowEnd);
            if (n.isActivity()) {
                if (Math.abs(minsAgo) <= ACTIVITY_WINDOW_MIN) activity = true;
                continue;
            }
            if (n.getCarbs() != null && n.getCarbs() > 0) {
                if (inMatch) carbsLogged = true;
                if (minsAgo >= 0) minSinceCarbs = minSinceCarbs == null ? minsAgo : Math.min(minSinceCarbs, minsAgo);
            }
            if (!n.isLongActing() && n.getInsulin() != null && n.getInsulin() > 0) {
                if (inMatch) insulinLogged = true;
                if (minsAgo >= 0) {
                    minSinceBolus = minSinceBolus == null ? minsAgo : Math.min(minSinceBolus, minsAgo);
                    double now = InsulinCalculatorService.iobOpenApsExponential(
                            n.getInsulin(), minsAgo, in.iob().diaHours(), in.iob().peakMinutes());
                    double later = InsulinCalculatorService.iobOpenApsExponential(
                            n.getInsulin(), minsAgo + 60, in.iob().diaHours(), in.iob().peakMinutes());
                    iobAtOnset += now;
                    iobDrop += now - later;
                }
            }
        }

        List<Long> tRun = new ArrayList<>();
        List<Double> rRun = new ArrayList<>();
        for (int i = run.from(); i <= run.to(); i++) { tRun.add(s.epochMs(i)); rRun.add(s.smoothed(i)); }

        List<Long> tWin = new ArrayList<>();
        List<Double> actual = new ArrayList<>(), predicted = new ArrayList<>();
        for (int i = in.onsetIndex(); i < s.size() && s.epochMs(i) <= onsetMs + SLOPE_WINDOW_MIN * 60_000L; i++) {
            tWin.add(s.epochMs(i));
            actual.add(s.points().get(i).actualMmol());
            predicted.add(s.points().get(i).predictedMmol());
        }

        DayOfWeek dow = onset.getDayOfWeek();
        return new EventFeatures(
                EventFeatures.VERSION,
                onset.getHour() + onset.getMinute() / 60.0,
                dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY,
                run.durationMinutes(),
                run.mean(),
                run.peak(),
                slopePerMinute(tRun, rRun),
                slopePerMinute(tWin, actual),
                slopePerMinute(tWin, predicted),
                s.points().get(in.onsetIndex()).actualMmol(),
                iobAtOnset,
                in.isfMmolPerU() * iobDrop,
                in.cobAtOnsetGrams(),
                minSinceCarbs,
                minSinceBolus,
                activity,
                carbsLogged,
                insulinLogged,
                s.sigma() > 0 ? Math.abs(run.mean()) / s.sigma() : 0.0,
                null, null, null, null);
    }

    /** Least-squares slope in value units per minute; 0 with fewer than two points. */
    public static double slopePerMinute(List<Long> epochMs, List<Double> values) {
        int n = epochMs.size();
        if (n < 2) return 0.0;
        double mx = 0, my = 0;
        for (int i = 0; i < n; i++) { mx += epochMs.get(i) / 60_000.0; my += values.get(i); }
        mx /= n; my /= n;
        double sxy = 0, sxx = 0;
        for (int i = 0; i < n; i++) {
            double dx = epochMs.get(i) / 60_000.0 - mx;
            sxy += dx * (values.get(i) - my);
            sxx += dx * dx;
        }
        return sxx == 0 ? 0.0 : sxy / sxx;
    }
}
