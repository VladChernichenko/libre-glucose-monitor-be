package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.OFFProductDto;
import che.glucosemonitorbe.service.nutrition.NutritionSnapshot;
import che.glucosemonitorbe.service.nutrition.OpenFoodFactsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Nutrition", description = "Nutrition analysis - ingredient GL/GI enrichment via Spoonacular and Edamam")
@ConditionalOnProperty(name = "mongodb.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
@RestController
@RequestMapping("/api/nutrition")
@RequiredArgsConstructor
public class NutritionController {

    private final OpenFoodFactsService openFoodFactsService;

    // ---
    // OpenFoodFacts endpoints
    // ---

    @Operation(summary = "Look up a food product by barcode and return a NutritionSnapshot",
               description = "Queries the OpenFoodFacts database. Results are cached for 7 days. " +
                             "servingGrams defaults to 100 (per-100g values from OFF).")
    @ApiResponses({ @ApiResponse(responseCode = "200", description = "Product found, snapshot returned"),
                    @ApiResponse(responseCode = "404", description = "Barcode not found in OpenFoodFacts"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized") })
    @GetMapping("/product/{barcode}")
    public ResponseEntity<NutritionSnapshot> getProductByBarcode(
            @PathVariable String barcode,
            @Parameter(description = "Serving size in grams to scale nutrition values (default 100)")
            @RequestParam(defaultValue = "100") double servingGrams,
            Authentication authentication
    ) {
        if (authentication == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        return openFoodFactsService.lookupByBarcode(barcode)
                .map(product -> {
                    NutritionSnapshot snap = openFoodFactsService.toNutritionSnapshot(product, servingGrams);
                    log.info("OFF barcode lookup user={} barcode={} product='{}' carbs={}",
                            authentication.getName(), barcode, product.getProductName(), snap.getTotalCarbs());
                    return ResponseEntity.ok(snap);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Search OpenFoodFacts products by name",
               description = "Returns a ranked list of matching products (name, serving info, image). " +
                             "Use the barcode or re-call /product/{barcode} for the full NutritionSnapshot.")
    @ApiResponses({ @ApiResponse(responseCode = "200", description = "Search results (may be empty)"),
                    @ApiResponse(responseCode = "400", description = "Query is blank"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized") })
    @GetMapping("/search")
    public ResponseEntity<List<OFFProductDto>> searchProducts(
            @Parameter(description = "Free-text product name query") @RequestParam String q,
            @Parameter(description = "Maximum results to return (1-50, default 10)")
            @RequestParam(defaultValue = "10") int pageSize,
            Authentication authentication
    ) {
        if (authentication == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (q == null || q.isBlank())  return ResponseEntity.badRequest().build();

        List<OFFProductDto> results = openFoodFactsService.searchProducts(q.trim(), pageSize);
        log.info("OFF search user={} q='{}' hits={}", authentication.getName(), q, results.size());
        return ResponseEntity.ok(results);
    }
}
