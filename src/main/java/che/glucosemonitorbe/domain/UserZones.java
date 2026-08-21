package che.glucosemonitorbe.domain;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Single definition of "which clock is this user's wall time on", and of the wall-time to
 * epoch-millis conversion that reconciles it with {@code cgm_readings.date_timestamp}.
 *
 * <h3>Why this exists</h3>
 * <p>{@code notes.timestamp} holds the client's <b>naive local wall time</b>. {@code
 * cgm_readings.date_timestamp} holds <b>true UTC epoch-millis</b> written by the sync schedulers.
 * Reconciling the two requires the user's zone. Converting a wall time with {@code
 * toInstant(ZoneOffset.UTC)} instead silently shifts every comparison by the zone offset - the
 * bug fixed in {@code 75b3f24}, {@code eba6d48} and {@code 9f81ef9}, each time by hand in a
 * different service. The resolution rule lived in three private copies before this class.
 */
public final class UserZones {

    private UserZones() {}

    /**
     * The IANA zone if {@code ianaZone} names one, else {@code null}. Callers that want to log a
     * bad stored value can test this before calling {@link #resolve}.
     */
    public static ZoneId parseIana(String ianaZone) {
        if (ianaZone == null || ianaZone.isBlank()) return null;
        try {
            return ZoneId.of(ianaZone);
        } catch (DateTimeException e) {
            return null;
        }
    }

    /**
     * The clock this user's wall times live on: their IANA zone if known (authoritative, because it
     * resolves the offset per instant and so survives a DST transition mid-window), else the raw
     * offset they last reported, else UTC - which reproduces the behaviour from before either was
     * recorded.
     *
     * @param ianaZone         {@code user_settings.timezone}, may be null/blank/unparseable
     * @param utcOffsetMinutes {@code user_settings.utc_offset_minutes}, minutes EAST of UTC
     */
    public static ZoneId resolve(String ianaZone, Integer utcOffsetMinutes) {
        ZoneId iana = parseIana(ianaZone);
        if (iana != null) return iana;
        if (utcOffsetMinutes != null) return ZoneOffset.ofTotalSeconds(utcOffsetMinutes * 60);
        return ZoneOffset.UTC;
    }

    /**
     * A wall time on {@code zone} as UTC epoch-millis, comparable against
     * {@code cgm_readings.date_timestamp}.
     *
     * <p>Uses {@code atZone}, not {@code toInstant(offset)}: the offset is resolved for THIS
     * instant, so a window spanning a DST transition converts both ends correctly.
     */
    public static long toEpochMs(LocalDateTime wallTime, ZoneId zone) {
        return wallTime.atZone(zone).toInstant().toEpochMilli();
    }

    /** UTC epoch-millis back onto {@code zone}'s wall clock. Inverse of {@link #toEpochMs}. */
    public static LocalDateTime toWallTime(long epochMs, ZoneId zone) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone);
    }
}
