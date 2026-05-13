package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.service.nutrition.LogMealService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Nutrition", description = "Nutrition analysis — ingredient GL/GI enrichment via Spoonacular and Edamam")
@Slf4j
@RestController
@RequestMapping("/api/nutrition")
@RequiredArgsConstructor
public class NutritionImageController {

    private final LogMealService logMealService;

    @Operation(summary = "Analyze a meal photo with LogMeal and return GI/GL/carbs/fiber")
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

        NutritionSnapshot snapshot = logMealService.analyzeImage(photo);

        log.info(
                "Vision nutrition analyze user={} photoSize={} source={} foods={} totalCarbs={}",
                authentication.getName(),
                photo.getSize(),
                snapshot.getSource(),
                snapshot.getNormalizedFoods() == null ? 0 : snapshot.getNormalizedFoods().size(),
                snapshot.getTotalCarbs());
        return ResponseEntity.ok(snapshot);
    }
}
