package che.glucosemonitorbe.azt1d;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Azt1dBolusLogTest {

    private static final String HEADER =
            "EventDateTime,DeviceMode,BolusType,Basal,CorrectionDelivered,TotalBolusInsulinDelivered,FoodDelivered,CarbSize,CGM";

    @TempDir Path dir;

    private static long epoch(String ts) {
        return LocalDateTime.parse(ts.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private List<Azt1dDataset.BolusRecord> load(String header, String... rows) throws Exception {
        Path csv = dir.resolve("Subject 1.csv");
        Files.writeString(csv, header + "\n" + String.join("\n", rows) + "\n");
        return Azt1dDataset.loadBolusLog(csv);
    }

    @Test
    void readsAMealBolusWithCarbs() throws Exception {
        List<Azt1dDataset.BolusRecord> log =
                load(HEADER, "2024-01-01 12:00:00,,Standard,,0,4.5,4.5,45,110");

        assertThat(log).hasSize(1);
        Azt1dDataset.BolusRecord r = log.get(0);
        assertThat(r.epochMs()).isEqualTo(epoch("2024-01-01 12:00:00"));
        assertThat(r.bolusType()).isEqualTo("Standard");
        assertThat(r.carbsG()).isEqualTo(45.0);
        assertThat(r.totalU()).isEqualTo(4.5);
        assertThat(r.foodU()).isEqualTo(4.5);
        assertThat(Azt1dDataset.isManual(r)).isTrue();
        assertThat(Azt1dDataset.isAutomatic(r)).isFalse();
    }

    @Test
    void readsACorrectionOnlyDose() throws Exception {
        List<Azt1dDataset.BolusRecord> log =
                load(HEADER, "2024-01-01 14:00:00,,Standard/Correction,,2.0,2.0,0,,180");

        Azt1dDataset.BolusRecord r = log.get(0);
        assertThat(r.correctionU()).isEqualTo(2.0);
        assertThat(r.totalU()).isEqualTo(2.0);
        assertThat(r.carbsG()).isEqualTo(0.0);
        assertThat(Azt1dDataset.isManual(r)).isTrue();
    }

    @Test
    void flagsAutomaticBoluses() throws Exception {
        List<Azt1dDataset.BolusRecord> log =
                load(HEADER, "2024-01-01 15:00:00,,Automatic Bolus/Correction,,0.3,0.3,0,,190");

        Azt1dDataset.BolusRecord r = log.get(0);
        assertThat(Azt1dDataset.isAutomatic(r)).isTrue();
        assertThat(Azt1dDataset.isManual(r)).isFalse();
    }

    @Test
    void skipsRowsWithNoInsulinAndNoCarbs() throws Exception {
        List<Azt1dDataset.BolusRecord> log = load(HEADER,
                "2024-01-01 16:00:00,,,,,,,,150",
                "2024-01-01 16:05:00,,,0.8,,,,,152");

        assertThat(log).isEmpty();
    }

    @Test
    void resolvesColumnsFromTheHeaderInAnotherOrder() throws Exception {
        String reordered = "CGM,EventDateTime,CarbSize,FoodDelivered,TotalBolusInsulinDelivered,CorrectionDelivered,Basal,BolusType,DeviceMode";
        List<Azt1dDataset.BolusRecord> log =
                load(reordered, "110,2024-01-01 12:00:00,30,3.0,3.0,0,,Standard,");

        assertThat(log).hasSize(1);
        assertThat(log.get(0).carbsG()).isEqualTo(30.0);
        assertThat(log.get(0).totalU()).isEqualTo(3.0);
        assertThat(log.get(0).bolusType()).isEqualTo("Standard");
    }
}
