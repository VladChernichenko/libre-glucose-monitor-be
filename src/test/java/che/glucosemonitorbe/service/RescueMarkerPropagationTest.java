package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.CarbsEntry;
import che.glucosemonitorbe.domain.RescueCarbProfile;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.hovorka.learning.PredictionReplayEngine;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code hypo_treatment} note must arrive at every physiology consumer still carrying its rescue
 * marker.
 *
 * <p>Four separate code paths turn a note into a {@link CarbsEntry}: the shared
 * {@code NoteToCarbsEntryMapper} (covered by {@code NoteToCarbsEntryMapperRescueTest}), the
 * digital-twin replay feeder, the unlogged-event residual scan and the AI context aggregator
 * (covered in {@code ContextAggregatorServiceTest}). Three of them once hand-built the entry with no
 * {@code absorptionMode} at all, so {@code RescueCarbProfile.isRescue(null)} was false and the
 * rescue silently reverted to the user's 240-minute mixed-meal curve.
 *
 * <p>The drift went unnoticed because the existing rescue tests pinned the shared <em>constants</em>
 * - {@code rescueTMaxG()} against {@code RescueCarbProfile.HALF_LIFE_MIN} - and never asserted that
 * an entry arriving from these paths carried the marker at all. Constants agreeing is worth nothing
 * if no entry reaches them.
 */
class RescueMarkerPropagationTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 8, 20, 9, 15);

    // -- Path 1: digital-twin replay feeder ------------------------------------

    /**
     * {@code DigitalTwinCalibrationService} flattens notes into {@code PredictionReplayEngine.Event},
     * which is all the replay engine ever sees. If the rescue flag is lost here, the nightly fit
     * models a 15 g dextrose tablet over 240 minutes, cannot explain the sharp real rise, and
     * compensates via agScale/isfScale - which then apply to every prediction for that user.
     */
    @Test
    void twinCalibrationEventCarriesTheRescueFlag() {
        PredictionReplayEngine.Event event =
                DigitalTwinCalibrationService.toEvent(hypoTreatmentNote(15.0), ZoneOffset.UTC);

        assertThat(event.rescue())
                .as("a hypo_treatment note must reach the replay engine flagged as a rescue")
                .isTrue();
        assertThat(event.carbs()).isEqualTo(15.0);
    }

    @Test
    void twinCalibrationEventLeavesAnOrdinaryMealUnflagged() {
        PredictionReplayEngine.Event event =
                DigitalTwinCalibrationService.toEvent(ordinaryMealNote(15.0), ZoneOffset.UTC);

        assertThat(event.rescue())
                .as("an ordinary meal must not be flagged - otherwise the assertion above passes "
                    + "for a mutant that flags everything")
                .isFalse();
    }

    // -- Path 2: unlogged-event residual scan ----------------------------------

    /**
     * Left unmarked here, the raw forward prediction under-predicts the rescue rise, the residual
     * comes out large and positive, and a <em>logged</em> hypo recovery is flagged as unlogged food.
     * That flag then feeds back into the twin fit as a down-weighted CGM window.
     */
    @Test
    void unloggedEventScanEntryCarriesTheRescueMarker() {
        CarbsEntry entry = UnloggedEventDetectionService.toCarbsEntry(hypoTreatmentNote(15.0));

        assertThat(RescueCarbProfile.isRescue(entry.getAbsorptionMode()))
                .as("absorptionMode was %s", entry.getAbsorptionMode())
                .isTrue();
        assertThat(entry.getCarbs()).isEqualTo(15.0);
        assertThat(entry.getTimestamp()).isEqualTo(AT);
    }

    @Test
    void unloggedEventScanEntryLeavesAnOrdinaryMealUnmarked() {
        CarbsEntry entry = UnloggedEventDetectionService.toCarbsEntry(ordinaryMealNote(15.0));

        assertThat(RescueCarbProfile.isRescue(entry.getAbsorptionMode())).isFalse();
    }

    // -- The marker itself -----------------------------------------------------

    /**
     * {@code mark} is the single writer of the marker. Pin what it writes, so a consumer that goes
     * through it cannot be left half-stamped (absorption mode set but GI or speed class not, which
     * would still reach {@code CarbsOnBoardService} with the wrong duration bucket).
     */
    @Test
    void markStampsTheWholeProfileNotJustTheMode() {
        CarbsEntry entry = RescueCarbProfile.mark(CarbsEntry.builder().carbs(15.0).build());

        assertThat(entry.getAbsorptionMode()).isEqualTo(RescueCarbProfile.ABSORPTION_MODE);
        assertThat(entry.getEstimatedGi()).isEqualTo((double) RescueCarbProfile.GI);
        assertThat(entry.getAbsorptionSpeedClass()).isEqualTo(RescueCarbProfile.SPEED_CLASS);
    }

    // -- Fixtures --------------------------------------------------------------

    private static Note hypoTreatmentNote(double grams) {
        Note n = ordinaryMealNote(grams);
        n.setType(Note.TYPE_HYPO_TREATMENT);
        n.setMeal("Hypo treatment");
        return n;
    }

    private static Note ordinaryMealNote(double grams) {
        Note n = new Note();
        n.setTimestamp(AT);
        n.setCarbs(grams);
        n.setInsulin(0.0);
        return n;
    }
}
