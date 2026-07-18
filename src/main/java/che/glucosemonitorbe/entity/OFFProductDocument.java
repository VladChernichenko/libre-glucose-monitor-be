package che.glucosemonitorbe.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.util.List;

@Data
@Document(collection = "products")
@JsonIgnoreProperties(ignoreUnknown = true)
public class OFFProductDocument {

    @Id
    private String id;

    /** EAN / UPC barcode - the primary lookup key. */
    @Indexed
    @Field("code")
    private String code;

    @Field("product_name")
    @JsonProperty("product_name")
    private String productName;

    @Field("serving_size")
    @JsonProperty("serving_size")
    private String servingSize;

    @Field("serving_quantity")
    @JsonProperty("serving_quantity")
    private Double servingQuantityG;

    @Field("brands")
    private String brands;

    @Field("image_url")
    @JsonProperty("image_url")
    private String imageUrl;

    @Field("categories_tags")
    @JsonProperty("categories_tags")
    private List<String> categoriesTags;

    @Field("nutriments")
    private Nutriments nutriments;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Nutriments {

        @Field("carbohydrates_100g")
        @JsonProperty("carbohydrates_100g")
        private Double carbohydrates100g;

        @Field("proteins_100g")
        @JsonProperty("proteins_100g")
        private Double proteins100g;

        @Field("fat_100g")
        @JsonProperty("fat_100g")
        private Double fat100g;

        @Field("fiber_100g")
        @JsonProperty("fiber_100g")
        private Double fiber100g;

        @Field("energy-kcal_100g")
        @JsonProperty("energy-kcal_100g")
        private Double energyKcal100g;

        @Field("sugars_100g")
        @JsonProperty("sugars_100g")
        private Double sugars100g;
    }
}
