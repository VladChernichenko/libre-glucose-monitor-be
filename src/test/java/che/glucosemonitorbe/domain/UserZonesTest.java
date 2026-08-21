package che.glucosemonitorbe.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one place the wall-time -> UTC-epoch rule is defined. Every zone here is explicit, so the
 * assertions hold identically on a UTC build box and on the {@code Europe/London} default the app
 * deploys under - a suite that leans on the machine's zone cannot catch a UTC assumption.
 */
class UserZonesTest {

    @Test
    @DisplayName("The IANA zone wins over the reported offset")
    void ianaZoneIsAuthoritative() {
        // Berlin is UTC+2 in July; the stale offset says UTC+4. The zone must win.
        assertThat(UserZones.resolve("Europe/Berlin", 240)).isEqualTo(ZoneId.of("Europe/Berlin"));
    }

    @Test
    @DisplayName("An absent or unparseable zone falls back to the reported offset, then to UTC")
    void fallbackChain() {
        assertThat(UserZones.resolve(null, 240)).isEqualTo(ZoneOffset.ofHours(4));
        assertThat(UserZones.resolve("  ", -330)).isEqualTo(ZoneOffset.ofTotalSeconds(-330 * 60));
        assertThat(UserZones.resolve("Mars/Olympus_Mons", 60)).isEqualTo(ZoneOffset.ofHours(1));
        assertThat(UserZones.resolve(null, null)).isEqualTo(ZoneOffset.UTC);
        assertThat(UserZones.resolve("Mars/Olympus_Mons", null)).isEqualTo(ZoneOffset.UTC);
        assertThat(UserZones.parseIana("Mars/Olympus_Mons")).isNull();
        assertThat(UserZones.parseIana(null)).isNull();
    }

    @Test
    @DisplayName("A wall time converts to the UTC epoch it names on its own zone, not to the same "
            + "digits read as UTC")
    void toEpochMsUsesTheZone() {
        LocalDateTime noon = LocalDateTime.of(2026, 7, 15, 12, 0);
        ZoneId berlin = ZoneId.of("Europe/Berlin");

        long viaZone = UserZones.toEpochMs(noon, berlin);
        long viaUtc = noon.toInstant(ZoneOffset.UTC).toEpochMilli();

        // 12:00 in Berlin is 10:00 UTC: reading it as UTC lands two hours late.
        assertThat(viaUtc - viaZone).isEqualTo(2 * 3600_000L);
        assertThat(UserZones.toWallTime(viaZone, berlin)).isEqualTo(noon);
    }

    @Test
    @DisplayName("atZone, not a fixed offset: the two ends of a window spanning a DST change each "
            + "get their own offset")
    void dstTransitionIsResolvedPerInstant() {
        ZoneId london = ZoneId.of("Europe/London");
        // BST ends 26 Oct 2025 at 02:00 local. 01:30 is UTC+1, 03:30 is UTC+0.
        LocalDateTime before = LocalDateTime.of(2025, 10, 26, 1, 30);
        LocalDateTime after = LocalDateTime.of(2025, 10, 26, 3, 30);

        long elapsedMs = UserZones.toEpochMs(after, london) - UserZones.toEpochMs(before, london);

        // Three real hours elapse across a two-hour move of the wall clock.
        assertThat(elapsedMs).isEqualTo(3 * 3600_000L);
    }
}
