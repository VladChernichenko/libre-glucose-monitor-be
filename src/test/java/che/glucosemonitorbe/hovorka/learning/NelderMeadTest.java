package che.glucosemonitorbe.hovorka.learning;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NelderMeadTest {

    @Test
    void minimisesAShiftedQuadraticToItsKnownMinimum() {
        // f(x,y) = (x-3)² + (y+2)² → minimum at (3, -2), value 0.
        NelderMead nm = NelderMead.standard();
        NelderMead.Result r = nm.minimize(
                p -> {
                    double dx = p[0] - 3.0, dy = p[1] + 2.0;
                    return dx * dx + dy * dy;
                },
                new double[]{0.0, 0.0},
                new double[]{-10, -10},
                new double[]{10, 10},
                0.5);

        assertThat(r.point()[0]).isCloseTo(3.0, org.assertj.core.data.Offset.offset(1e-2));
        assertThat(r.point()[1]).isCloseTo(-2.0, org.assertj.core.data.Offset.offset(1e-2));
        assertThat(r.value()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-3));
    }

    @Test
    void respectsBoxBoundsWhenMinimumLiesOutside() {
        // Minimum of (x-5)² is at 5, but the box caps x at 2 → optimiser should stop at the bound.
        NelderMead nm = NelderMead.standard();
        NelderMead.Result r = nm.minimize(
                p -> (p[0] - 5.0) * (p[0] - 5.0),
                new double[]{0.0}, new double[]{0.0}, new double[]{2.0}, 0.3);
        assertThat(r.point()[0]).isCloseTo(2.0, org.assertj.core.data.Offset.offset(1e-2));
    }
}
