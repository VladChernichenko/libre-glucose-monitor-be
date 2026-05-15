package che.glucosemonitorbe.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Slim projection of an OpenFoodFacts product returned to the client.
 * Only fields needed for nutrition analysis and UI display are mapped.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OFFProductDto {

    /** EAN barcode — injected after lookup, not part of the nested product object. */
    private String barcode;

    @JsonProperty("product_name")
    private String productName;

    /** e.g. "15 g", "1 cup (240 ml)" */
    @JsonProperty("serving_size")
    private String servingSize;

    /** Numeric serving quantity in grams (or ml for liquids). */
    @JsonProperty("serving_quantity")
    private Double servingQuantityG;

    private Nutriments nutriments;

    @JsonProperty("categories_tags")
    private List<String> categoriesTags;

    @JsonProperty("image_url")
    private String imageUrl;

    private String brands;

    // -------------------------------------------------------------------------

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Nutriments {

        @JsonProperty("carbohydrates_100g")
        private Double carbohydrates100g;

        @JsonProperty("proteins_100g")
        private Double proteins100g;

        @JsonProperty("fat_100g")
        private Double fat100g;

        @JsonProperty("fiber_100g")
        private Double fiber100g;

        /** Some products use "energy-kcal_100g" (with hyphen) */
        @JsonProperty("energy-kcal_100g")
        private Double energyKcal100g;

        /** Fallback when hyphen form is absent */
        @JsonProperty("energy_kcal_100g")
        private Double energyKcal100gAlt;

        @JsonProperty("sugars_100g")
        private Double sugars100g;

        public double effectiveEnergyKcal() {
            if (energyKcal100g != null) return energyKcal100g;
            if (energyKcal100gAlt != null) return energyKcal100gAlt;
            return 0.0;
        }
    }
}
