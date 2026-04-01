package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.InsulinCatalogDTO;
import che.glucosemonitorbe.entity.InsulinCatalog;
import che.glucosemonitorbe.service.InsulinCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/insulin-catalog")
@RequiredArgsConstructor
public class InsulinCatalogController {

    private final InsulinCatalogService insulinCatalogService;

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
