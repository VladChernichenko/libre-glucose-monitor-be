package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.InsulinCatalogDTO;
import che.glucosemonitorbe.entity.InsulinCatalog;
import che.glucosemonitorbe.service.InsulinCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Insulin Catalog", description = "Insulin product catalog — rapid, long-acting, mixed types")
@RestController
@RequestMapping("/api/insulin-catalog")
@RequiredArgsConstructor
public class InsulinCatalogController {

    private final InsulinCatalogService insulinCatalogService;

    @Operation(summary = "List insulin catalog, optionally filtered by category")
    @ApiResponses({ @ApiResponse(responseCode = "200", description = "Catalog list returned"),
                    @ApiResponse(responseCode = "400", description = "Unknown category value") })
    @GetMapping
    public ResponseEntity<List<InsulinCatalogDTO>> list(
            @RequestParam(required = false) String category) {
        if (category != null && !category.isBlank()) {
            try {
                InsulinCatalog.Category cat = InsulinCatalog.Category.valueOf(category.trim().toUpperCase());
                return ResponseEntity.ok(insulinCatalogService.findByCategory(cat));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        }
        return ResponseEntity.ok(insulinCatalogService.findAll());
    }
}
