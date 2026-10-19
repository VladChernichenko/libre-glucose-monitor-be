package che.glucosemonitorbe.domain;

import che.glucosemonitorbe.domain.InjectionSiteRotation.Injection;
import che.glucosemonitorbe.domain.InjectionSiteRotation.Result;
import che.glucosemonitorbe.domain.InjectionSiteRotation.Status;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static che.glucosemonitorbe.domain.InjectionSiteZone.*;
import static org.assertj.core.api.Assertions.assertThat;

class InjectionSiteRotationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 19, 18, 0);

    private static Injection at(double hoursAgo, InjectionSiteZone zone) {
        return new Injection(NOW.minusMinutes(Math.round(hoursAgo * 60)), zone);
    }

    private static Status status(Result r, InjectionSiteZone zone) {
        return r.zones().stream().filter(z -> z.zone() == zone).findFirst().orElseThrow().status();
    }

    @Test
    void zonesAreColouredByHowRecentlyTheyWereUsed() {
        Result r = InjectionSiteRotation.evaluate(List.of(at(3, ABD_UR), at(30, ABD_UL), at(80, THIGH_L)), NOW, true);

        assertThat(status(r, ABD_UR)).isEqualTo(Status.USED_24H);
        assertThat(status(r, ABD_UL)).isEqualTo(Status.USED_72H);
        assertThat(status(r, THIGH_L)).isEqualTo(Status.FREE);
        assertThat(status(r, ARM_R)).isEqualTo(Status.FREE);
        assertThat(r.zones()).hasSize(12).noneMatch(z -> z.zone() == UNKNOWN);
    }

    @Test
    void suggestsANeverUsedZoneFirstInZoneOrder() {
        Result r = InjectionSiteRotation.evaluate(List.of(at(3, ABD_UR), at(30, ABD_UL)), NOW, true);

        assertThat(r.suggested()).isEqualTo(ABD_LR);
    }

    @Test
    void suggestsTheZoneUnusedForLongestOnceAllHaveBeenUsed() {
        List<Injection> h = new ArrayList<>();
        int hoursAgo = 1;
        for (InjectionSiteZone z : InjectionSiteZone.values()) {
            if (z.isBodyZone()) h.add(at(hoursAgo++, z));   // ABD_UR newest ... BUTT_L oldest
        }

        assertThat(InjectionSiteRotation.evaluate(h, NOW, false).suggested()).isEqualTo(BUTT_L);
        // Rapid insulin never goes to the buttocks: the oldest of the rest is FLANK_L.
        assertThat(InjectionSiteRotation.evaluate(h, NOW, true).suggested()).isEqualTo(FLANK_L);
    }

    @Test
    void warnsAfterTheSameZoneThreeTimesInARow() {
        Result r = InjectionSiteRotation.evaluate(
                List.of(at(1, ABD_UR), at(4, ABD_UR), at(6, UNKNOWN), at(9, ABD_UR), at(20, THIGH_R)), NOW, true);

        assertThat(r.repeat()).isNotNull();
        assertThat(r.repeat().zone()).isEqualTo(ABD_UR);
        assertThat(r.repeat().count()).isEqualTo(3);
    }

    @Test
    void noWarningWhenTheRunIsBroken() {
        Result r = InjectionSiteRotation.evaluate(
                List.of(at(1, ABD_UR), at(4, ABD_UR), at(6, THIGH_R), at(9, ABD_UR)), NOW, true);

        assertThat(r.repeat()).isNull();
    }

    @Test
    void ignoresInjectionsAfterNow() {
        Result r = InjectionSiteRotation.evaluate(List.of(new Injection(NOW.plusHours(1), ABD_UR)), NOW, true);

        assertThat(status(r, ABD_UR)).isEqualTo(Status.FREE);
        assertThat(r.suggested()).isEqualTo(ABD_UR);
    }

    @Test
    void parsesCodesCaseInsensitively() {
        assertThat(InjectionSiteZone.fromCode(" Abd_UR ")).isEqualTo(ABD_UR);
        assertThat(InjectionSiteZone.fromCode("unknown")).isEqualTo(UNKNOWN);
        assertThat(InjectionSiteZone.fromCode("left_ear")).isNull();
        assertThat(InjectionSiteZone.fromCode(null)).isNull();
    }
}
