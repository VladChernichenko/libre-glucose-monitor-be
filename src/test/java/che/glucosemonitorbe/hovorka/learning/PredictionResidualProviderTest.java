package che.glucosemonitorbe.hovorka.learning;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The seam between the physiological model and the learned digital twin. Two things matter here and
 * both are safety-relevant: {@link PredictionResidualProvider#NONE} must be inert, because
 * calibration replay measures residuals against the raw model and a non-zero correction there would
 * be counted twice; and any provider that only supplies a correction must still yield a sensible
 * confidence band from the default method, because a prediction rendered without one reads as more
 * certain than it is.
 */
class PredictionResidualProviderTest {

    private static final double EPS = 1e-12;

    private static final UUID USER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final LocalDateTime NOON = LocalDateTime.of(2026, Month.MARCH, 14, 12, 0);

    // -- NONE ------------------------------------------------------------------

    @Test
    void none_appliesNoCorrectionAtAnyHourOfDay() {
        for (int hour = 0; hour < 24; hour++) {
            assertThat(PredictionResidualProvider.NONE
                    .residualMmol(USER, NOON.withHour(hour).withMinute(37)))
                    .as("hour %d", hour)
                    .isZero();
        }
    }

    @Test
    void none_appliesNoCorrectionForAnyUser() {
        assertThat(PredictionResidualProvider.NONE.residualMmol(UUID.randomUUID(), NOON)).isZero();
        assertThat(PredictionResidualProvider.NONE.residualMmol(null, NOON)).isZero();
        assertThat(PredictionResidualProvider.NONE.residualMmol(USER, null)).isZero();
    }

    @Test
    void none_reportsNoUncertainty_soReplayScoresTheRawModelWithNoBand() {
        for (int horizonMin : new int[]{0, 15, 30, 60, 90, 120, 240, 480}) {
            assertThat(PredictionResidualProvider.NONE.uncertaintySdMmol(USER, horizonMin))
                    .as("horizon %d min", horizonMin)
                    .isZero();
        }
        // NONE overrides the default; it must not fall back to the population prior
        assertThat(PredictionResidualProvider.NONE.uncertaintySdMmol(USER, 60))
                .isNotEqualTo(PredictionUncertaintyModel.populationDefault().sdAtHorizon(60));
    }

    // -- the default band ------------------------------------------------------

    /** A twin that has learned a correction but supplies no uncertainty of its own. */
    private static final PredictionResidualProvider CORRECTION_ONLY =
            (userId, pointTime) -> 0.4;

    @Test
    void aProviderThatOnlySuppliesACorrection_stillGetsThePopulationBand() {
        PredictionUncertaintyModel prior = PredictionUncertaintyModel.populationDefault();

        for (int horizonMin : PredictionUncertaintyModel.HORIZONS) {
            assertThat(CORRECTION_ONLY.uncertaintySdMmol(USER, horizonMin))
                    .as("horizon %d min", horizonMin)
                    .isCloseTo(prior.sdAtHorizon(horizonMin), within(EPS));
        }
        assertThat(CORRECTION_ONLY.residualMmol(USER, NOON)).isCloseTo(0.4, within(EPS));
    }

    @Test
    void theDefaultBandIsAlwaysPositiveAndWidensWithHorizon() {
        double previous = 0.0;
        for (int horizonMin : new int[]{15, 30, 60, 90, 120, 180, 240, 480}) {
            double sd = CORRECTION_ONLY.uncertaintySdMmol(USER, horizonMin);
            assertThat(sd).as("horizon %d min", horizonMin)
                    .isGreaterThanOrEqualTo(PredictionUncertaintyModel.SD_FLOOR)
                    .isLessThanOrEqualTo(PredictionUncertaintyModel.SD_MAX)
                    .isGreaterThanOrEqualTo(previous);
            previous = sd;
        }
        // a 4-hour forecast is meaningfully less certain than a half-hour one
        assertThat(CORRECTION_ONLY.uncertaintySdMmol(USER, 240))
                .isGreaterThan(CORRECTION_ONLY.uncertaintySdMmol(USER, 30));
    }

    @Test
    void anImplementationMayOverrideTheDefaultBand() {
        PredictionResidualProvider tightTwin = new PredictionResidualProvider() {
            @Override
            public double residualMmol(UUID userId, LocalDateTime pointTime) {
                return -0.25;
            }

            @Override
            public double uncertaintySdMmol(UUID userId, int horizonMin) {
                return 0.5;
            }
        };

        assertThat(tightTwin.residualMmol(USER, NOON)).isCloseTo(-0.25, within(EPS));
        assertThat(tightTwin.uncertaintySdMmol(USER, 120)).isCloseTo(0.5, within(EPS));
        assertThat(tightTwin.uncertaintySdMmol(USER, 120))
                .isNotEqualTo(PredictionUncertaintyModel.populationDefault().sdAtHorizon(120));
    }
}
