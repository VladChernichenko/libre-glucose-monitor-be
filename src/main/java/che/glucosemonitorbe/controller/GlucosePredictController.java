package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.PredictRequest;
import che.glucosemonitorbe.dto.PredictResponse;
import che.glucosemonitorbe.service.GlucosePredictService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/predict} - macronutrient-aware glucose prediction.
 *
 * <p>Accepts the current CGM reading, a prospective insulin bolus, and the
 * full macronutrient breakdown of the upcoming meal (carbs, protein, fat,
 * fiber). Returns a 300-minute prediction curve together with the optimal
 * pre-bolus pause and bolus-wave strategy recommendation.</p>
 *
 * <h3>Example request</h3>
 * <pre>{@code
 * POST /api/predict
 * {
 *   "currentGlucose": 7.2,
 *   "insulinDose":    4.5,
 *   "carbs":          40,
 *   "protein":        30,
 *   "fat":            25,
 *   "fiber":          5,
 *   "horizonMinutes": 300
 * }
 * }</pre>
 */
@Tag(name = "Glucose Prediction", description = "Macronutrient-aware Hovorka ODE prediction with pre-bolus optimisation")
@RestController
@RequestMapping("/api/predict")
@RequiredArgsConstructor
public class GlucosePredictController {

    private final GlucosePredictService predictService;

    @Operation(
        summary     = "Predict glucose curve from macros",
        description = "Runs a Hovorka 2-compartment ODE simulation with Elashoff gastric modulation. "
                    + "Returns a prediction curve for the specified horizon (default 300 min), the "
                    + "optimal pre-bolus pause, and a bolus-wave strategy recommendation."
    )
    @ApiResponse(responseCode = "200", description = "Prediction computed successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @PostMapping
    public ResponseEntity<PredictResponse> predict(
            @Valid @RequestBody PredictRequest request,
            Authentication authentication) {

        String username = authentication != null ? authentication.getName() : null;
        PredictResponse response = predictService.predict(request, username);
        return ResponseEntity.ok(response);
    }
}
