package che.glucosemonitorbe.config;

import che.glucosemonitorbe.glycemic.GlycemicEventDetector;
import che.glucosemonitorbe.hovorka.*;
import che.glucosemonitorbe.hovorka.learning.PredictionResidualProvider;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import che.glucosemonitorbe.service.UserSettingsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the pure detector with a raw predictor: no twin overlay, no residual correction. */
@Configuration
public class GlycemicEngineConfig {

    @Bean
    public GlycemicEventDetector glycemicEventDetector(HovorkaParameterService paramService, HovorkaOdeSolver odeSolver,
                                                       BasalInsulinResolver basalResolver,
                                                       UserInsulinPreferencesService insulinPrefsService,
                                                       DallaManGutModel gutModel, UserSettingsService userSettingsService,
                                                       GlycemicEventProperties props) {
        HovorkaGlucosePredictionService raw = new HovorkaGlucosePredictionService(paramService, odeSolver,
                basalResolver, insulinPrefsService, gutModel, userSettingsService, PredictionResidualProvider.NONE);
        return new GlycemicEventDetector(raw, props);
    }
}
