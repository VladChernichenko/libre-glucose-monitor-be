package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.NutritionAnalyzeRequest;
import che.glucosemonitorbe.service.nutrition.FoodCompositionService;
import che.glucosemonitorbe.service.nutrition.FoodPortion;
import che.glucosemonitorbe.service.nutrition.MealPhotoRecognizer;
import che.glucosemonitorbe.service.nutrition.NutritionSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Photo in, catalog snapshot out. The forecast curve is the existing Hovorka path once the
 * snapshot is stored on the note. This controller does not integrate a second curve.
 */
@Tag(name = "Nutrition", description = "Meal photo recognition and catalog nutrition")
@Slf4j
@RestController
@RequestMapping("/api/nutrition")
@RequiredArgsConstructor
public class NutritionImageController {

    private final MealPhotoRecognizer recognizer;
    private final FoodCompositionService composition;

    @Operation(summary = "Recognize a meal photo and return catalog carbohydrates, fat, protein, fiber, GI and GL")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Nutrition snapshot returned"),
            @ApiResponse(responseCode = "400", description = "No photo provided"),
            @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @PostMapping(value = "/analyze-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<NutritionSnapshot> analyzeImage(
            @RequestParam("photo") MultipartFile photo,
            Authentication authentication
    ) {
        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (photo == null || photo.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        List<FoodPortion> foods = recognizer.recognize(photo);
        NutritionSnapshot snapshot = composition.compose(foods);
        log.info("Meal photo user={} foods={} source={} carbs={} gi={}",
                authentication.getName(),
                foods.size(),
                snapshot.getSource(),
                snapshot.getTotalCarbs(),
                snapshot.getEstimatedGi());
        return ResponseEntity.ok(snapshot);
    }

    @Operation(summary = "Look up typed foods in the catalog and return carbohydrates, fat, protein, fiber, GI and GL")
    @ApiResponses({ @ApiResponse(responseCode = "200", description = "Nutrition snapshot returned"),
            @ApiResponse(responseCode = "400", description = "Ingredients text is blank"),
            @ApiResponse(responseCode = "401", description = "Unauthorized") })
    @PostMapping("/analyze")
    public ResponseEntity<NutritionSnapshot> analyzeIngredients(
            @RequestBody NutritionAnalyzeRequest request,
            Authentication authentication
    ) {
        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String ingredients = request.getIngredientsText() == null ? "" : request.getIngredientsText().trim();
        if (ingredients.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        NutritionSnapshot snapshot = composition.composeFromText(ingredients, "", request.getFallbackCarbs());
        log.info("Nutrition analyze user={} source={} carbs={} gi={}",
                authentication.getName(), snapshot.getSource(), snapshot.getTotalCarbs(), snapshot.getEstimatedGi());
        return ResponseEntity.ok(snapshot);
    }
}
