package che.glucosemonitorbe.service;

import che.glucosemonitorbe.dto.PredictionFactors;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class GlucoseCalculationsServiceTest {

    @Test
    void determineTrendUsesAdjustedThresholds() throws Exception {
        GlucoseCalculationsService service = new GlucoseCalculationsService(null, null, null, null, null, null, null, null);
        Method method = GlucoseCalculationsService.class.getDeclaredMethod("determineTrend", PredictionFactors.class, double.class);
        method.setAccessible(true);

        PredictionFactors rising = PredictionFactors.builder().carbContribution(0.35).insulinContribution(0.0).baselineContribution(0.0).trendContribution(0.0).build();
        PredictionFactors falling = PredictionFactors.builder().carbContribution(-0.35).insulinContribution(0.0).baselineContribution(0.0).trendContribution(0.0).build();
        PredictionFactors stable = PredictionFactors.builder().carbContribution(0.1).insulinContribution(-0.1).baselineContribution(0.0).trendContribution(0.0).build();

        assertEquals("rising", method.invoke(service, rising, 120.0));
        assertEquals("falling", method.invoke(service, falling, 120.0));
        assertEquals("stable", method.invoke(service, stable, 120.0));
    }
}