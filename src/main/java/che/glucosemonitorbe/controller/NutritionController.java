package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.NutritionAnalyzeRequest;
import che.glucosemonitorbe.service.nutrition.NutritionEnrichmentService;
import che.glucosemonitorbe.service.nutrition.NutritionSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/nutrition")
@RequiredArgsConstructor
public class NutritionController {

    private final NutritionEnrichmentService nutritionEnrichmentService;

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
        NutritionSnapshot snapshot = nutritionEnrichmentService.enrichFromText(
                ingredients,
                "",
                request.getFallbackCarbs()
        );
        int foodsCount = snapshot.getNormalizedFoods() == null ? 0 : snapshot.getNormalizedFoods().size();
        log.info(
                "Nutrition analyze user={} ingredientsLength={} source={} foodsCount={} totalCarbs={}",
                authentication.getName(),
                ingredients.length(),
                snapshot.getSource(),
                foodsCount,
                snapshot.getTotalCarbs());
        return ResponseEntity.ok(snapshot);
    }
}
