package che.glucosemonitorbe.azt1d;

import che.glucosemonitorbe.backtest.MealReplayHarness;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.hovorka.learning.PredictionReplayEngine;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Azt1dReplayDataTest {

    private static final LocalDateTime T = LocalDateTime.of(2024, 3, 1, 12, 0);

    private static long ms(LocalDateTime t) {
        return t.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    @Test
    void mapsManualBolusNearCarbsAsMealBolus() {
        Azt1dDataset.Profile profile = new Azt1dDataset.Profile(30, 40, 0.8, 12, 2.5, 72, true, 2.0, 2.6, 2.8);
        Azt1dDataset.Subject subject = new Azt1dDataset.Subject("Subject 99",
                List.of(new PredictionReplayEngine.Reading(ms(T), 6.5)), List.of(), profile);
        List<Azt1dDataset.BolusRecord> log = List.of(
                new Azt1dDataset.BolusRecord(ms(T), "Standard", 45, 0, 0, 0),
                new Azt1dDataset.BolusRecord(ms(T.plusMinutes(10)), "Standard", 0, 4.0, 0, 4.0),
                new Azt1dDataset.BolusRecord(ms(T.plusHours(3)), "Standard", 0, 1.0, 1.0, 0),
                new Azt1dDataset.BolusRecord(ms(T.plusHours(5)), "Automatic Bolus/Correction", 0, 0.6, 0.6, 0));

        MealReplayHarness.Data d = Azt1dReplayData.of(subject, log);

        assertThat(d.notes()).extracting(Note::getMeal).containsExactly("Meal", "Bolus", "Correction", "Correction");
        assertThat(d.notes().get(0).getCarbs()).isEqualTo(45.0);
        assertThat(d.notes().get(1).getInsulin()).isEqualTo(4.0);
        assertThat(d.notes().get(0).getTimestamp()).isEqualTo(T);
        assertThat(d.settings().getIsf()).isEqualTo(2.5);
        assertThat(d.settings().getIsfDinner()).isEqualTo(2.8);
        assertThat(d.settings().getGramsPerUnitLunch()).isEqualTo(12.0);
        assertThat(d.settings().getBodyWeightKg()).isEqualTo(72.0);
        assertThat(d.curves().get("default").peakMinutes()).isEqualTo(55.0);
        assertThat(d.cgm()).hasSize(1);
        assertThat(d.zone()).isEqualTo(ZoneOffset.UTC);
    }
}
