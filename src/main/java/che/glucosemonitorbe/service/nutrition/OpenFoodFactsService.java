package che.glucosemonitorbe.service.nutrition;

import che.glucosemonitorbe.config.CacheConfig;
import che.glucosemonitorbe.dto.OFFProductDto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Client for the OpenFoodFacts REST API v2.
 * https://openfoodfacts.github.io/openfoodfacts-server/api/
 *
 * Two main operations:
 *   • lookupByBarcode  – exact EAN/UPC lookup, O(1) by database index
 *   • searchProducts   – full-text search by product name (for manual entry)
 *
 * Results are cached for 7 days via "nutritionApiResponses" Caffeine cache.
 *
 * GI/GL estimation: OpenFoodFacts does not publish glycemic index.
 * We estimate GI from category tags (provided by the OFF taxonomy) using the
 * same dampening formula as NutritionEnrichmentService: fat×0.30 + protein×0.20,
 * capped at 20 GI units, floor 15.
 *
 * MongoDB dump note: when the local OFF dump is imported into MongoDB,
 * swap the RestTemplate calls for a MongoRepository lookup — the
 * toNutritionSnapshot() conversion is identical.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OpenFoodFactsService {

    private final RestTemplate restTemplate;

    @Value("${app.openfoodfacts.base-url:https://world.openfoodfacts.org}")
    private String baseUrl;

    /** OFF API etiquette: identify your app in the User-Agent header. */
    @Value("${app.openfoodfacts.user-agent:GlucoseMonitor/1.0 (open-source, contact via GitHub)}")
    private String userAgent;

    private static final String PRODUCT_FIELDS =
            "code,product_name,nutriments,serving_size,serving_quantity,categories_tags,image_url,brands";

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Fetch a single product by barcode (EAN-13, UPC-A, etc.).
     * Returns {@code Optional.empty()} when the barcode is not in the database
     * or the remote call fails.
     */
    @Cacheable(value = CacheConfig.CACHE_NUTRITION_API, key = "'off:barcode:' + #barcode")
    public Optional<OFFProductDto> lookupByBarcode(String barcode) {
        String url = baseUrl + "/api/v2/product/" + barcode + "?fields=" + PRODUCT_FIELDS;
        try {
            ResponseEntity<ProductApiResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET, headersEntity(), ProductApiResponse.class);

            if (response.getBody() == null || response.getBody().getStatus() != 1) {
                log.debug("OFF product not found: barcode={}", barcode);
                return Optional.empty();
            }
            OFFProductDto product = response.getBody().getProduct();
            if (product != null) product.setBarcode(barcode);
            return Optional.ofNullable(product);

        } catch (Exception e) {
            log.warn("OFF barcode lookup failed: barcode={} error={}", barcode, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Search products by name (free text). Results are ranked by OFF relevance score.
     * pageSize is capped at 50 to respect OFF rate limits.
     */
    @Cacheable(value = CacheConfig.CACHE_NUTRITION_API,
               key = "'off:search:' + #query.toLowerCase() + ':' + #pageSize")
    public List<OFFProductDto> searchProducts(String query, int pageSize) {
        String encoded = UriUtils.encode(query, StandardCharsets.UTF_8);
        String url = baseUrl + "/api/v2/search?search_terms=" + encoded
                + "&fields=" + PRODUCT_FIELDS
                + "&page_size=" + Math.min(pageSize, 50)
                + "&json=1";
        try {
            ResponseEntity<SearchApiResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET, headersEntity(), SearchApiResponse.class);

            if (response.getBody() == null || response.getBody().getProducts() == null) {
                return List.of();
            }
            return response.getBody().getProducts();

        } catch (Exception e) {
            log.warn("OFF search failed: query={} error={}", query, e.getMessage());
            return List.of();
        }
    }

    /**
     * Convert an OFFProductDto into a NutritionSnapshot scaled to {@code servingGrams}.
     * All macro values from OFF are per-100g; we scale to the requested serving.
     * GI is estimated from category tags; GL uses net carbs after fiber.
     */
    public NutritionSnapshot toNutritionSnapshot(OFFProductDto product, double servingGrams) {
        OFFProductDto.Nutriments n = product.getNutriments();
        if (n == null) {
            return NutritionSnapshot.builder()
                    .source("OPEN_FOOD_FACTS")
                    .confidence(0.3)
                    .absorptionMode("DEFAULT_DECAY")
                    .absorptionSpeedClass("DEFAULT")
                    .normalizedFoods(nameList(product))
                    .build();
        }

        double scale   = servingGrams / 100.0;
        double carbs   = safe(n.getCarbohydrates100g()) * scale;
        double fat     = safe(n.getFat100g())           * scale;
        double protein = safe(n.getProteins100g())      * scale;
        double fiber   = safe(n.getFiber100g())         * scale;

        double rawGi = estimateGi(product);
        // Fat+protein dampen glycemic response (Moghaddam et al.; Venn & Green)
        double dampening    = Math.min(fat * 0.30 + protein * 0.20, 20.0);
        double effectiveGi  = Math.max(rawGi - dampening, 15.0);
        double availCarbs   = Math.max(0, carbs - fiber);
        double gl           = effectiveGi * availCarbs / 100.0;

        log.debug("OFF snapshot: product='{}' serving={}g carbs={} fat={} protein={} fiber={} gi={} gl={}",
                product.getProductName(), servingGrams,
                round1(carbs), round1(fat), round1(protein), round1(fiber),
                round1(effectiveGi), round1(gl));

        return NutritionSnapshot.builder()
                .absorptionMode("GI_GL_ENHANCED")
                .source("OPEN_FOOD_FACTS")
                .confidence(0.9)
                .totalCarbs(round1(carbs))
                .fat(round1(fat))
                .protein(round1(protein))
                .fiber(round1(fiber))
                .estimatedGi(round1(effectiveGi))
                .glycemicLoad(round1(gl))
                .absorptionSpeedClass(classify(effectiveGi, fiber, protein, fat))
                .normalizedFoods(nameList(product))
                .build();
    }

    // -------------------------------------------------------------------------
    // GI estimation from OFF category tags
    // -------------------------------------------------------------------------

    /**
     * Map OpenFoodFacts taxonomy category tags to approximate GI values.
     * Taxonomy reference: https://world.openfoodfacts.org/categories
     *
     * Priority: category tags are checked first (more reliable than name matching).
     * Falls back to product-name substring matching for uncategorised products.
     */
    private double estimateGi(OFFProductDto product) {
        List<String> tags = product.getCategoriesTags();
        if (tags != null) {
            for (String tag : tags) {
                String t = tag.toLowerCase();
                // Refined grains / high GI
                if (t.contains("white-breads") || t.contains("baguettes"))    return 75.0;
                if (t.contains("breads") || t.contains("bread"))               return 65.0;
                if (t.contains("corn-flakes") || t.contains("puffed-"))        return 75.0;
                if (t.contains("breakfast-cereals"))                           return 66.0;
                if (t.contains("crackers") || t.contains("pretzels"))          return 70.0;
                if (t.contains("white-rices"))                                 return 72.0;
                if (t.contains("rices"))                                       return 64.0;
                if (t.contains("potatoes") && !t.contains("sweet"))            return 78.0;
                if (t.contains("chips") || t.contains("crisps"))               return 70.0;
                if (t.contains("sodas") || t.contains("carbonated-drinks"))    return 63.0;
                // Medium GI
                if (t.contains("pastas") || t.contains("noodles"))             return 55.0;
                if (t.contains("oatmeal") || t.contains("porridges"))          return 55.0;
                if (t.contains("sweet-spreads") || t.contains("jams"))         return 60.0;
                if (t.contains("chocolates") || t.contains("confectioneries")) return 60.0;
                if (t.contains("biscuits") || t.contains("cookies"))           return 55.0;
                if (t.contains("bananas"))                                     return 60.0;
                if (t.contains("sweet-potatoes"))                              return 44.0;
                // Low GI
                if (t.contains("whole-wheat") || t.contains("wholegrain"))     return 50.0;
                if (t.contains("rye-breads") || t.contains("sourdoughs"))      return 48.0;
                if (t.contains("fruits") && !t.contains("dried"))              return 38.0;
                if (t.contains("dried-fruits"))                                return 60.0;
                if (t.contains("berries"))                                     return 25.0;
                if (t.contains("dairy") || t.contains("milks"))                return 35.0;
                if (t.contains("yogurts"))                                     return 33.0;
                if (t.contains("cheeses"))                                     return 10.0;
                if (t.contains("legumes") || t.contains("beans")
                        || t.contains("lentils") || t.contains("chickpeas"))   return 30.0;
                if (t.contains("nuts") || t.contains("seeds"))                 return 15.0;
                if (t.contains("vegetables") || t.contains("salads"))          return 15.0;
                // Negligible GI
                if (t.contains("meats") || t.contains("fish")
                        || t.contains("seafood") || t.contains("eggs"))        return 0.0;
                if (t.contains("oils") || t.contains("fats"))                  return 0.0;
            }
        }

        // Product-name fallback
        String name = product.getProductName() != null
                ? product.getProductName().toLowerCase() : "";
        if (name.contains("bread") || name.contains("хлеб"))            return 65.0;
        if (name.contains("rice") || name.contains("рис"))              return 64.0;
        if (name.contains("pasta") || name.contains("макарон"))         return 55.0;
        if (name.contains("potato") || name.contains("картоф"))         return 78.0;
        if (name.contains("oat") || name.contains("овсян"))             return 55.0;
        if (name.contains("yogurt") || name.contains("йогурт"))         return 33.0;
        if (name.contains("milk") || name.contains("молоко"))           return 35.0;
        if (name.contains("sugar") || name.contains("сахар"))           return 65.0;

        return 55.0; // default: medium GI
    }

    private String classify(double gi, double fiber, double protein, double fat) {
        if (fiber >= 8 || (protein + fat) >= 20) return "SLOW";
        if (gi >= 70 && fiber < 4)               return "FAST";
        return "MEDIUM";
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private HttpEntity<Void> headersEntity() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", userAgent);
        return new HttpEntity<>(headers);
    }

    private static double safe(Double v) { return v != null ? v : 0.0; }

    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }

    private static List<String> nameList(OFFProductDto p) {
        return p.getProductName() != null ? List.of(p.getProductName()) : List.of();
    }

    // -------------------------------------------------------------------------
    // Internal response wrappers (not exposed to the rest of the app)
    // -------------------------------------------------------------------------

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ProductApiResponse {
        private int status;          // 1 = found, 0 = not found
        private OFFProductDto product;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class SearchApiResponse {
        private int count;
        @JsonProperty("page_size")
        private int pageSize;
        private List<OFFProductDto> products;
    }
}
