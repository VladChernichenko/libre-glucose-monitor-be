package che.glucosemonitorbe.hovorka.learning;

import che.glucosemonitorbe.domain.GlucoseConversion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * What {@link ReplayMetrics} actually computes: the model's error against the naive "glucose stays
 * put" forecast that {@link AnchorSample#baseline()} carries. The arithmetic decides whether the
 * physiological model earns its place at a horizon, so the aggregations are pinned by hand-checked
 * numbers rather than by re-deriving them with the same formulas under test.
 */
class ReplayMetricsTest {

    private static final double EPS = 1e-9;

    private static AnchorSample sample(int horizonMin, double predicted, double actual, double baseline) {
        return new AnchorSample(horizonMin, predicted, actual, baseline, Regime.FASTING, 9);
    }

    private static AnchorSample sample(int horizonMin, double predicted, double actual,
                                       double baseline, int hourOfDay) {
        return new AnchorSample(horizonMin, predicted, actual, baseline, Regime.FASTING, hourOfDay);
    }

    // -- empty input -----------------------------------------------------------

    @Test
    void overall_onEmptyInput_reportsZeroSamplesAndNaNs() {
        ReplayMetrics.Stats stats = ReplayMetrics.overall(List.of(), ResidualBiasModel.neutral());

        assertThat(stats.horizonMin()).isZero();
        assertThat(stats.n()).isZero();
        assertThat(stats.modelRmse()).isNaN();
        assertThat(stats.modelMae()).isNaN();
        assertThat(stats.modelBias()).isNaN();
        assertThat(stats.persistenceRmse()).isNaN();
        assertThat(stats.persistenceMae()).isNaN();
        assertThat(stats.persistenceBias()).isNaN();
    }

    @Test
    void overall_onNullInput_isTreatedAsEmptyRatherThanThrowing() {
        ReplayMetrics.Stats stats = ReplayMetrics.overall(null, null);

        assertThat(stats.n()).isZero();
        assertThat(stats.modelRmse()).isNaN();
    }

    @Test
    void byHorizon_onEmptyInput_returnsNoRows() {
        assertThat(ReplayMetrics.byHorizon(List.of(), ResidualBiasModel.neutral())).isEmpty();
    }

    // -- single sample ---------------------------------------------------------

    @Test
    void singleSample_rmseMaeAndBiasAllEqualThatOneError() {
        // model over-predicts by 1.0; persistence (baseline 6.0) under-predicts by 1.0
        ReplayMetrics.Stats s = ReplayMetrics.overall(List.of(sample(30, 8.0, 7.0, 6.0)), null);

        assertThat(s.n()).isEqualTo(1);
        assertThat(s.modelRmse()).isCloseTo(1.0, within(EPS));
        assertThat(s.modelMae()).isCloseTo(1.0, within(EPS));
        assertThat(s.modelBias()).isCloseTo(1.0, within(EPS));
        assertThat(s.persistenceRmse()).isCloseTo(1.0, within(EPS));
        assertThat(s.persistenceMae()).isCloseTo(1.0, within(EPS));
        // sign is retained: persistence under-predicted
        assertThat(s.persistenceBias()).isCloseTo(-1.0, within(EPS));
    }

    @Test
    void biasKeepsSignWhileMaeDoesNot_andRmsePunishesTheLargerMiss() {
        // errors of +1.0 and -3.0: MAE 2.0, RMSE sqrt(5), bias -1.0
        List<AnchorSample> samples = List.of(
                sample(60, 8.0, 7.0, 7.0),
                sample(60, 4.0, 7.0, 7.0));

        ReplayMetrics.Stats s = ReplayMetrics.overall(samples, null);

        assertThat(s.n()).isEqualTo(2);
        assertThat(s.modelMae()).isCloseTo(2.0, within(EPS));
        assertThat(s.modelRmse()).isCloseTo(Math.sqrt(5.0), within(EPS));
        assertThat(s.modelBias()).isCloseTo(-1.0, within(EPS));
        // RMSE >= MAE always, and strictly greater once the errors differ in size
        assertThat(s.modelRmse()).isGreaterThan(s.modelMae());
    }

    @Test
    void identicalSamples_collapseToThatSingleErrorWithNoSpread() {
        List<AnchorSample> samples = List.of(
                sample(30, 9.0, 8.0, 8.5),
                sample(30, 9.0, 8.0, 8.5),
                sample(30, 9.0, 8.0, 8.5));

        ReplayMetrics.Stats s = ReplayMetrics.overall(samples, null);

        assertThat(s.n()).isEqualTo(3);
        // with every error equal, RMSE == MAE == |bias|
        assertThat(s.modelRmse()).isCloseTo(1.0, within(EPS));
        assertThat(s.modelMae()).isCloseTo(1.0, within(EPS));
        assertThat(s.modelBias()).isCloseTo(1.0, within(EPS));
        assertThat(s.persistenceRmse()).isCloseTo(0.5, within(EPS));
    }

    // -- skill score -----------------------------------------------------------

    @Test
    void skill_isOneWhenTheModelIsPerfect_andPositiveMeansItBeatsPersistence() {
        // model nails it; persistence is 2.0 out
        ReplayMetrics.Stats s = ReplayMetrics.overall(List.of(sample(60, 7.0, 7.0, 9.0)), null);

        assertThat(s.modelRmse()).isZero();
        assertThat(s.skill()).isCloseTo(1.0, within(EPS));
        assertThat(s.beatsPersistence()).isTrue();
    }

    @Test
    void skill_isZeroWhenTheModelOnlyMatchesPersistence() {
        ReplayMetrics.Stats s = ReplayMetrics.overall(List.of(sample(60, 8.0, 7.0, 6.0)), null);

        assertThat(s.skill()).isCloseTo(0.0, within(EPS));
        // equal RMSE is not "better": a tie does not beat the free forecast
        assertThat(s.beatsPersistence()).isFalse();
    }

    @Test
    void skill_isNegativeWhenAConstantLineWouldHaveBeenBetter() {
        // model 3.0 out, persistence 1.0 out -> skill = 1 - 3/1 = -2
        ReplayMetrics.Stats s = ReplayMetrics.overall(List.of(sample(120, 10.0, 7.0, 8.0)), null);

        assertThat(s.skill()).isCloseTo(-2.0, within(EPS));
        assertThat(s.beatsPersistence()).isFalse();
    }

    @Test
    void skill_isNaNWhenPersistenceIsFlawless_becauseTheRatioIsUndefined() {
        // baseline == actual: RMSE_persistence is 0, so 1 - model/0 must not be reported as -Inf
        ReplayMetrics.Stats s = ReplayMetrics.overall(List.of(sample(30, 8.0, 7.0, 7.0)), null);

        assertThat(s.persistenceRmse()).isZero();
        assertThat(s.skill()).isNaN();
        assertThat(s.beatsPersistence()).isFalse();
    }

    @Test
    void skill_isNaNForAnEmptyStatsRow() {
        assertThat(ReplayMetrics.overall(List.of(), null).skill()).isNaN();
    }

    // -- unit conversion -------------------------------------------------------

    @Test
    void mgdlAccessors_convertBothRmseColumns() {
        ReplayMetrics.Stats s = ReplayMetrics.overall(List.of(sample(30, 8.0, 7.0, 5.0)), null);

        assertThat(s.modelRmseMgdl())
                .isCloseTo(s.modelRmse() * GlucoseConversion.MGDL_PER_MMOL, within(EPS));
        assertThat(s.persistenceRmseMgdl())
                .isCloseTo(s.persistenceRmse() * GlucoseConversion.MGDL_PER_MMOL, within(EPS));
        // sanity: 2.0 mmol/L of persistence error is ~36 mg/dL
        assertThat(s.persistenceRmseMgdl()).isCloseTo(36.0364, within(1e-4));
    }

    // -- residual correction ---------------------------------------------------

    @Test
    void aNullResidualModelLeavesThePredictionUntouched() {
        AnchorSample s = sample(30, 8.0, 7.0, 7.5);

        assertThat(ReplayMetrics.overall(List.of(s), null).modelBias()).isCloseTo(1.0, within(EPS));
        assertThat(ReplayMetrics.overall(List.of(s), ResidualBiasModel.neutral()).modelBias())
                .isCloseTo(1.0, within(EPS));
    }

    @Test
    void theResidualCorrectionForThePointsHourIsAddedBeforeScoring() {
        // Fit a model that has learned a real correction at hour 3, then check the scored error is
        // the corrected one. The expected correction is read back from the model rather than
        // re-derived here, so this pins "ReplayMetrics applies correctionAt(hourOfDay)" and not the
        // shrinkage arithmetic that ResidualBiasModelTest already owns.
        List<AnchorSample> training = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            training.add(sample(30, 7.0, 8.0, 7.0, 3)); // actual runs 1.0 above prediction at 03:00
        }
        ResidualBiasModel fitted = ResidualBiasModel.fit(training);
        double correction = fitted.correctionAt(3);
        assertThat(correction).isGreaterThan(0.1); // the fit learned something to apply

        AnchorSample scored = sample(30, 7.0, 8.0, 7.0, 3);
        ReplayMetrics.Stats corrected = ReplayMetrics.overall(List.of(scored), fitted);
        ReplayMetrics.Stats raw = ReplayMetrics.overall(List.of(scored), null);

        assertThat(corrected.modelBias()).isCloseTo(correction - 1.0, within(EPS));
        assertThat(raw.modelBias()).isCloseTo(-1.0, within(EPS));
        // the correction moves the prediction toward reality, so the model looks better
        assertThat(corrected.modelRmse()).isLessThan(raw.modelRmse());
        // persistence is unaffected by the residual layer
        assertThat(corrected.persistenceRmse()).isCloseTo(raw.persistenceRmse(), within(EPS));
    }

    @Test
    void theCorrectionIsKeyedByHourOfDay_soASampleAtAnotherHourIsUnaffected() {
        List<AnchorSample> training = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            training.add(sample(30, 7.0, 8.0, 7.0, 3));
        }
        ResidualBiasModel fitted = ResidualBiasModel.fit(training);

        AnchorSample atHour3 = sample(30, 7.0, 7.0, 7.0, 3);
        AnchorSample atHour15 = sample(30, 7.0, 7.0, 7.0, 15);

        double biasAt3 = ReplayMetrics.overall(List.of(atHour3), fitted).modelBias();
        double biasAt15 = ReplayMetrics.overall(List.of(atHour15), fitted).modelBias();

        assertThat(biasAt3).isCloseTo(fitted.correctionAt(3), within(EPS));
        assertThat(biasAt15).isCloseTo(fitted.correctionAt(15), within(EPS));
        assertThat(biasAt3).isGreaterThan(biasAt15);
    }

    // -- grouping by horizon ---------------------------------------------------

    @Test
    void byHorizon_groupsPerHorizonAndOrdersByHorizonRegardlessOfInputOrder() {
        List<AnchorSample> samples = List.of(
                sample(120, 10.0, 7.0, 7.0),
                sample(30, 8.0, 7.0, 7.0),
                sample(60, 9.0, 7.0, 7.0),
                sample(30, 6.0, 7.0, 7.0));

        List<ReplayMetrics.Stats> rows = ReplayMetrics.byHorizon(samples, null);

        assertThat(rows).extracting(ReplayMetrics.Stats::horizonMin).containsExactly(30, 60, 120);
        assertThat(rows).extracting(ReplayMetrics.Stats::n).containsExactly(2, 1, 1);
        // the +30 row averages |+1| and |-1|
        assertThat(rows.get(0).modelMae()).isCloseTo(1.0, within(EPS));
        assertThat(rows.get(0).modelBias()).isCloseTo(0.0, within(EPS));
        // error grows with horizon, as it should
        assertThat(rows.get(1).modelRmse()).isCloseTo(2.0, within(EPS));
        assertThat(rows.get(2).modelRmse()).isCloseTo(3.0, within(EPS));
    }

    @Test
    void eachHorizonRowMatchesScoringThatHorizonAlone() {
        List<AnchorSample> thirty = List.of(sample(30, 8.0, 7.0, 7.2), sample(30, 6.5, 7.0, 7.2));
        List<AnchorSample> sixty = List.of(sample(60, 9.0, 7.0, 7.2));
        List<AnchorSample> all = new ArrayList<>(thirty);
        all.addAll(sixty);

        List<ReplayMetrics.Stats> rows = ReplayMetrics.byHorizon(all, null);
        ReplayMetrics.Stats thirtyAlone = ReplayMetrics.overall(thirty, null);

        assertThat(rows.get(0).modelRmse()).isCloseTo(thirtyAlone.modelRmse(), within(EPS));
        assertThat(rows.get(0).modelMae()).isCloseTo(thirtyAlone.modelMae(), within(EPS));
        assertThat(rows.get(0).n()).isEqualTo(thirtyAlone.n());
        // and the pooled row spans every horizon
        assertThat(ReplayMetrics.overall(all, null).n()).isEqualTo(3);
        assertThat(rows.get(0).horizonMin()).isEqualTo(30);
        // the pooled row is flagged with horizon 0, not with a real horizon
        assertThat(ReplayMetrics.overall(all, null).horizonMin()).isZero();
    }

    // -- rendering -------------------------------------------------------------

    @Test
    void render_printsAHeader_aRowPerHorizon_andThePooledRow() {
        List<AnchorSample> samples = List.of(
                sample(30, 8.0, 7.0, 7.0),
                sample(60, 9.0, 7.0, 7.0));
        List<ReplayMetrics.Stats> rows = ReplayMetrics.byHorizon(samples, null);
        ReplayMetrics.Stats pooled = ReplayMetrics.overall(samples, null);

        String table = ReplayMetrics.render("replay: user-1", rows, pooled);

        assertThat(table).contains("replay: user-1");
        assertThat(table).contains("horizon").contains("modRMSE").contains("persRMSE")
                .contains("modMAE").contains("persMAE").contains("modBias").contains("skill");
        assertThat(table).contains("+30 min").contains("+60 min").contains("pooled");
        assertThat(table).contains("skill = 1 - RMSE_model/RMSE_persistence");
        // header + two horizon rows + pooled + the trailing note
        assertThat(table.lines().filter(l -> !l.isBlank())).hasSize(6);
    }

    @Test
    void render_omitsThePooledRowWhenThereIsNothingPooled() {
        List<ReplayMetrics.Stats> rows =
                ReplayMetrics.byHorizon(List.of(sample(30, 8.0, 7.0, 7.5)), null);

        assertThat(ReplayMetrics.render("t", rows, null)).doesNotContain("pooled");
        assertThat(ReplayMetrics.render("t", rows, ReplayMetrics.overall(List.of(), null)))
                .doesNotContain("pooled");
    }

    @Test
    void render_survivesNaNRowsRatherThanThrowing() {
        ReplayMetrics.Stats empty = ReplayMetrics.overall(List.of(), null);

        String table = ReplayMetrics.render("empty replay", List.of(empty), empty);

        assertThat(table).contains("empty replay").contains("NaN");
    }

    @Test
    void render_formatsSkillAsAPercentageWithASign() {
        // model perfect, persistence 2.0 out -> skill 1.0 -> "+100.0%"
        List<ReplayMetrics.Stats> rows =
                ReplayMetrics.byHorizon(List.of(sample(30, 7.0, 7.0, 9.0)), null);

        assertThat(ReplayMetrics.render("t", rows, null)).contains("+100.0%");
    }
}
