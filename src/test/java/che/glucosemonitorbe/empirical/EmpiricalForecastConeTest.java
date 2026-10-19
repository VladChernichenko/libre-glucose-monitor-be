package che.glucosemonitorbe.empirical;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EmpiricalForecastConeTest {

    @Test
    void theConeIsAPointAtTheAnchorAndWidensWithTheHorizon() {
        EmpiricalForecastCone cone = EmpiricalForecastCone.defaults();

        assertThat(cone.halfWidthMgdl(0)).isZero();
        double prev = 0;
        for (int m = 5; m <= 240; m += 5) {
            assertThat(cone.halfWidthMgdl(m)).isGreaterThanOrEqualTo(prev);
            prev = cone.halfWidthMgdl(m);
        }
        assertThat(cone.halfWidthMgdl(60)).isEqualTo(52.0);
        assertThat(cone.halfWidthMgdl(240)).isEqualTo(78.0);
    }

    @Test
    void betweenKnotsTheWidthIsInterpolated() {
        EmpiricalForecastCone cone = EmpiricalForecastCone.defaults();

        assertThat(cone.halfWidthMgdl(5)).isCloseTo(19.0 / 3, within(1e-9)); // 0 → 19 over 15 min
        assertThat(cone.halfWidthMgdl(45)).isCloseTo(42.5, within(1e-9));   // 33 → 52 over 30 min
        assertThat(cone.halfWidthMmol(60)).isCloseTo(52.0 / 18.016, within(1e-9));
    }

    @Test
    void fittingTakesThe90thPercentileOfErrorsAtEachKnot() {
        List<EmpiricalForecastCone.Sample> s = new ArrayList<>();
        for (int i = 1; i <= 11; i++) s.add(new EmpiricalForecastCone.Sample(60, i * 5)); // 5..55

        EmpiricalForecastCone cone = EmpiricalForecastCone.fit(s);

        assertThat(cone.halfWidthMgdl(60)).isEqualTo(50.0);  // index floor(0.9 × 10) = 9 → 50
        assertThat(cone.halfWidthMgdl(30)).isEqualTo(33.0);  // too few samples: default kept
        assertThat(cone.halfWidthMgdl(90)).isEqualTo(58.0);
    }

    @Test
    void aFittedConeNeverNarrowsFurtherOut() {
        List<EmpiricalForecastCone.Sample> s = new ArrayList<>();
        for (int i = 0; i < 10; i++) s.add(new EmpiricalForecastCone.Sample(120, 10)); // lucky horizon

        EmpiricalForecastCone cone = EmpiricalForecastCone.fit(s);

        assertThat(cone.halfWidthMgdl(120)).isEqualTo(58.0);  // held at the 90-min width
    }

    @Test
    void noSamplesGivesTheDefaults() {
        assertThat(EmpiricalForecastCone.fit(List.of()).knotsMgdl())
                .containsExactly(EmpiricalForecastCone.defaults().knotsMgdl());
        assertThat(EmpiricalForecastCone.fit(null).knotsMgdl())
                .containsExactly(EmpiricalForecastCone.defaults().knotsMgdl());
    }
}
