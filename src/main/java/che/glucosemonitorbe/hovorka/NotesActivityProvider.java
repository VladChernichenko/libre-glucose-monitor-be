package che.glucosemonitorbe.hovorka;

import che.glucosemonitorbe.domain.ActivityIntensity;
import che.glucosemonitorbe.entity.Note;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link ActivityProvider} backed by a user's logged activity notes. For a time {@code t}, {@code a(t)}
 * is the maximum mapped intensity of any logged activity whose window
 * {@code [start, start + duration_min]} contains {@code t}, clamped to {@code [0,1]}; 0 when no activity
 * covers {@code t}. An optional per-user activity gain scales each window's intensity (default 1.0).
 *
 * <p>When there are no usable activity notes the factory returns {@link ActivityProvider#NONE}, so the
 * model behaves exactly as it does with no activity (the "no activity ⇒ bit-identical" invariant).</p>
 */
public final class NotesActivityProvider implements ActivityProvider {

    private record Window(long startMs, long endMs, double level) {}

    private final List<Window> windows;
    private final double gain;

    private NotesActivityProvider(List<Window> windows, double gain) {
        this.windows = windows;
        this.gain = gain;
    }

    /** Build from activity notes with the population gain (1.0). */
    public static ActivityProvider fromNotes(List<Note> notes) {
        return fromNotes(notes, 1.0);
    }

    /**
     * Build from activity notes, scaling each window's intensity by {@code gain} (a per-user activity
     * response multiplier). Returns {@link ActivityProvider#NONE} when there are no usable activity notes.
     */
    public static ActivityProvider fromNotes(List<Note> notes, double gain) {
        if (notes == null) return ActivityProvider.NONE;
        List<Window> windows = new ArrayList<>();
        for (Note n : notes) {
            if (n == null || !n.isActivity() || n.getTimestamp() == null
                    || n.getDurationMin() == null || n.getDurationMin() <= 0) {
                continue;
            }
            ActivityIntensity level = ActivityIntensity.fromName(n.getIntensity());
            if (level == null) continue;
            long start = n.getTimestamp().toInstant(ZoneOffset.UTC).toEpochMilli();
            long end = start + n.getDurationMin() * 60_000L;
            windows.add(new Window(start, end, level.aLevel()));
        }
        if (windows.isEmpty()) return ActivityProvider.NONE;
        return new NotesActivityProvider(windows, gain);
    }

    @Override
    public double intensityAt(LocalDateTime time) {
        long t = time.toInstant(ZoneOffset.UTC).toEpochMilli();
        double max = 0.0;
        for (Window w : windows) {
            if (t >= w.startMs() && t < w.endMs() && w.level() > max) {
                max = w.level();
            }
        }
        return ActivityModulation.clampIntensity(max * gain);
    }
}
