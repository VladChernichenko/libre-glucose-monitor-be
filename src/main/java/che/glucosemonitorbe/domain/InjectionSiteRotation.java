package che.glucosemonitorbe.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Injection-site rotation (spec F7): when each zone was last used, which zone to suggest next, and
 * whether the latest doses keep hitting the same zone. Pure - no Spring, no DB.
 */
public final class InjectionSiteRotation {

    /** Used within this window: red on the body map. */
    public static final Duration RECENT = Duration.ofHours(24);
    /** Used within this window (but not {@link #RECENT}): amber. */
    public static final Duration WARM = Duration.ofHours(72);
    /** The same zone this many times in a row raises the rotation warning. */
    public static final int REPEAT_LIMIT = 3;

    private InjectionSiteRotation() {}

    /** One injection whose site is known ({@code zone} may be {@link InjectionSiteZone#UNKNOWN}). */
    public record Injection(LocalDateTime time, InjectionSiteZone zone) {}

    public enum Status { USED_24H, USED_72H, FREE }

    public record ZoneUse(InjectionSiteZone zone, LocalDateTime lastUsedAt, Status status) {}

    /** The same zone {@code count} times in a row, ending with the latest sited dose. */
    public record Repeat(InjectionSiteZone zone, int count) {}

    /**
     * @param zones     every body zone, in {@link InjectionSiteZone} order
     * @param suggested the zone unused for longest (buttocks excluded for rapid insulin); never-used wins
     * @param repeat    non-null when the latest {@link #REPEAT_LIMIT}+ sited doses share a zone
     */
    public record Result(List<ZoneUse> zones, InjectionSiteZone suggested, Repeat repeat) {}

    /**
     * @param history  injections with a recorded site, any order; ones after {@code now} are ignored
     * @param forRapid the next dose is rapid-acting, so buttocks are not suggested
     */
    public static Result evaluate(List<Injection> history, LocalDateTime now, boolean forRapid) {
        List<Injection> past = history.stream()
                .filter(i -> i.time() != null && i.zone() != null && !i.time().isAfter(now))
                .sorted(Comparator.comparing(Injection::time).reversed())
                .toList();

        Map<InjectionSiteZone, LocalDateTime> last = new EnumMap<>(InjectionSiteZone.class);
        for (Injection i : past) {
            if (i.zone().isBodyZone()) last.putIfAbsent(i.zone(), i.time());
        }

        List<ZoneUse> zones = new ArrayList<>();
        InjectionSiteZone suggested = null;
        LocalDateTime suggestedLast = null;
        for (InjectionSiteZone z : InjectionSiteZone.values()) {
            if (!z.isBodyZone()) continue;
            LocalDateTime used = last.get(z);
            zones.add(new ZoneUse(z, used, status(used, now)));
            if (forRapid && z.isButtock()) continue;
            // Never used beats used; otherwise the oldest last use. Ties keep enum order.
            if (suggested == null || (suggestedLast != null && (used == null || used.isBefore(suggestedLast)))) {
                suggested = z;
                suggestedLast = used;
            }
        }
        return new Result(zones, suggested, repeat(past));
    }

    private static Status status(LocalDateTime used, LocalDateTime now) {
        if (used == null) return Status.FREE;
        Duration age = Duration.between(used, now);
        if (age.compareTo(RECENT) < 0) return Status.USED_24H;
        if (age.compareTo(WARM) < 0) return Status.USED_72H;
        return Status.FREE;
    }

    /** Consecutive same-zone run at the newest end; "don't remember" answers neither break nor extend it. */
    private static Repeat repeat(List<Injection> newestFirst) {
        InjectionSiteZone zone = null;
        int count = 0;
        for (Injection i : newestFirst) {
            if (!i.zone().isBodyZone()) continue;
            if (zone == null) zone = i.zone();
            if (i.zone() != zone) break;
            count++;
        }
        return count >= REPEAT_LIMIT ? new Repeat(zone, count) : null;
    }
}
