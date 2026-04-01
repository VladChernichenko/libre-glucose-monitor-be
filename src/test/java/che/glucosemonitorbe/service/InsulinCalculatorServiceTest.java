package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.InsulinDose;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InsulinCalculatorServiceTest {

    private final InsulinCalculatorService service = new InsulinCalculatorService();

    @Test
    void futureDoseContributesZero() {
        LocalDateTime now = LocalDateTime.of(2025, 6, 1, 12, 0);
        InsulinDose dose = InsulinDose.builder()
                .timestamp(now.plusMinutes(30))
                .units(5.0)
                .build();
        assertEquals(0.0, service.calculateRemainingInsulin(dose, now), 1e-9);
    }

    @Test
    void doseExactlyAtCurrentTime_isFullUnits() {
        LocalDateTime t = LocalDateTime.of(2025, 6, 1, 12, 0);
        InsulinDose dose = InsulinDose.builder()
                .timestamp(t)
                .units(4.0)
                .build();
        assertEquals(4.0, service.calculateRemainingInsulin(dose, t), 0.02);
    }

    @Test
    void beyondDiaReturnsZero() {
        LocalDateTime doseTime = LocalDateTime.of(2025, 6, 1, 8, 0);
        LocalDateTime now = doseTime.plusMinutes(4 * 60 + 1);
        InsulinDose dose = InsulinDose.builder()
                .timestamp(doseTime)
                .units(10.0)
                .build();
        // Explicit 4 h DIA: default curve is Fiasp-like (4.5 h), so 4 h + 1 min would still have IOB.
        assertEquals(0.0, service.calculateRemainingInsulin(dose, now, 4.0, 75.0), 1e-9);
    }

    @Test
    void totalIobSumsDoses() {
        LocalDateTime now = LocalDateTime.of(2025, 6, 1, 12, 0);
        InsulinDose d1 = InsulinDose.builder().timestamp(now.minusHours(1)).units(2.0).build();
        InsulinDose d2 = InsulinDose.builder().timestamp(now.minusMinutes(30)).units(3.0).build();
        double sum = service.calculateTotalActiveInsulin(List.of(d1, d2), now);
        assertTrue(sum > 0 && sum < 5.0, "IOB should be between 0 and total bolused units");
    }

    @Test
    void iobDecreasesAfterBolus() {
        LocalDateTime doseTime = LocalDateTime.of(2025, 6, 1, 10, 0);
        InsulinDose dose = InsulinDose.builder().timestamp(doseTime).units(6.0).build();
        double at0 = service.calculateRemainingInsulin(dose, doseTime);
        double at2h = service.calculateRemainingInsulin(dose, doseTime.plusMinutes(120));
        assertEquals(6.0, at0, 0.05);
        assertTrue(at2h < at0 && at2h > 0, "IOB should decay: at0=" + at0 + " at2h=" + at2h);
    }
}
