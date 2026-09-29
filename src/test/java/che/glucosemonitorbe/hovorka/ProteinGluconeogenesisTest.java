package che.glucosemonitorbe.hovorka;

import che.glucosemonitorbe.domain.CarbsEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Delayed glucose from protein (gluconeogenesis) as a slow-carb equivalent entry, shared by the
 * dashboard and {@code /api/predict} so both prediction paths agree on the 1.5-3 h protein rise.
 */
class ProteinGluconeogenesisTest {

    private static final LocalDateTime T = LocalDateTime.of(2024, 6, 1, 19, 0);

    @Test
    @DisplayName("a big protein portion adds a slow-glucose entry at meal + onset")
    void bigProteinPortion_addsDelayedGlucoseEntry() {
        CarbsEntry steak = entry(T, 40.0, 60.0);

        List<CarbsEntry> out = ProteinGluconeogenesis.withDelayedProteinGlucose(List.of(steak));

        assertThat(out).hasSize(2).contains(steak);
        CarbsEntry pgn = out.get(1);
        assertThat(pgn.getTimestamp()).isEqualTo(T.plusMinutes(ProteinGluconeogenesis.ONSET_MIN));
        // 60 g × 50 % glucogenic × 0.40 g carb-equivalent per g
        assertThat(pgn.getCarbs()).isCloseTo(12.0, within(1e-9));
        assertThat(pgn.getMealType()).isEqualTo(ProteinGluconeogenesis.MEAL_TYPE);
        assertThat(pgn.getProtein())
                .as("the equivalent entry must not carry protein, or it would be expanded again")
                .isNull();
    }

    @Test
    @DisplayName("the delayed entry absorbs like its meal, so it cannot speed up the meal's remaining carbs")
    void delayedEntry_inheritsTheMealsKinetics() {
        CarbsEntry lentils = entry(T, 50.0, 60.0);
        lentils.setEstimatedGi(40.0);
        lentils.setFiber(10.0);
        lentils.setAbsorptionSpeedClass("SLOW");

        CarbsEntry pgn = ProteinGluconeogenesis.withDelayedProteinGlucose(List.of(lentils)).get(1);

        assertThat(pgn.getEstimatedGi()).isEqualTo(40.0);
        assertThat(pgn.getFiber()).isEqualTo(10.0);
        assertThat(pgn.getAbsorptionSpeedClass()).isEqualTo("SLOW");
    }

    @Test
    @DisplayName("protein counts even when the note has no carbs (eggs / meat first course)")
    void proteinOnlyNote_stillAddsDelayedGlucose() {
        List<CarbsEntry> out = ProteinGluconeogenesis.withDelayedProteinGlucose(List.of(entry(T, 0.0, 40.0)));

        assertThat(out).hasSize(2);
        assertThat(out.get(1).getCarbs()).isCloseTo(8.0, within(1e-9));
    }

    @Test
    @DisplayName("a small protein amount below the threshold adds nothing")
    void smallProtein_addsNothing() {
        CarbsEntry toast = entry(T, 30.0, 8.0);   // 8 × 0.5 × 0.4 = 1.6 g < threshold

        assertThat(ProteinGluconeogenesis.withDelayedProteinGlucose(List.of(toast))).containsExactly(toast);
    }

    @Test
    @DisplayName("the input list is not modified and entries without timestamps are skipped")
    void inputUntouched_andNullTimestampSkipped() {
        List<CarbsEntry> in = new ArrayList<>(List.of(entry(null, 10.0, 60.0)));

        List<CarbsEntry> out = ProteinGluconeogenesis.withDelayedProteinGlucose(in);

        assertThat(in).hasSize(1);
        assertThat(out).hasSize(1);
    }

    private static CarbsEntry entry(LocalDateTime when, double carbs, double protein) {
        CarbsEntry e = CarbsEntry.builder().timestamp(when).carbs(carbs).build();
        e.setProtein(protein);
        return e;
    }
}
