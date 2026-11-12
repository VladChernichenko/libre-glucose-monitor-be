package che.glucosemonitorbe.service.nutrition;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FoodCatalogTest {

    @TempDir
    Path dir;

    private FoodCatalog catalog;

    @BeforeEach
    void open() throws Exception {
        catalog = new FoodCatalog(dir.resolve("food.sqlite"),
                new String(getClass().getResourceAsStream("/food/schema.sql").readAllBytes()),
                new String(getClass().getResourceAsStream("/food/seed.sql").readAllBytes()));
    }

    @Test
    void englishPluralsCoverCommonFoodMorphology() {
        assertEquals(List.of("grapes"), FoodCatalog.englishPlurals("grape"));
        assertEquals(List.of("apples"), FoodCatalog.englishPlurals("apple"));
        assertEquals(List.of("tomatoes"), FoodCatalog.englishPlurals("tomato"));
        assertEquals(List.of("potatoes"), FoodCatalog.englishPlurals("potato"));
        assertEquals(List.of("strawberries"), FoodCatalog.englishPlurals("strawberry"));
        assertEquals(List.of("blueberries"), FoodCatalog.englishPlurals("blueberry"));
        assertEquals(List.of("peaches"), FoodCatalog.englishPlurals("peach"));
        assertEquals(List.of("bay leaves"), FoodCatalog.englishPlurals("bay leaf"));
        assertEquals(List.of(), FoodCatalog.englishPlurals("grapes")); // already plural
        assertEquals(List.of(), FoodCatalog.englishPlurals("french fries"));
    }

    @Test
    void findResolvesPluralsAndQualifiedPlurals() {
        assertEquals(18.0, catalog.find("grapes").carbs100g(), 0.01);
        assertEquals(18.0, catalog.find("green grapes").carbs100g(), 0.01);
        assertEquals(14.0, catalog.find("apples").carbs100g(), 0.01);
        assertEquals(4.0, catalog.find("tomatoes").carbs100g(), 0.01);
        assertEquals(17.0, catalog.find("potatoes").carbs100g(), 0.01);
        assertEquals(8.0, catalog.find("strawberries").carbs100g(), 0.01);
        assertEquals(14.0, catalog.find("blueberries").carbs100g(), 0.01);
        assertEquals(16.0, catalog.find("cherries").carbs100g(), 0.01);
        assertEquals(23.0, catalog.find("bananas").carbs100g(), 0.01);
        assertEquals(1.0, catalog.find("eggs").carbs100g(), 0.01);
    }

    @Test
    void findStillHitsExactAliases() {
        assertEquals(18.0, catalog.find("grape").carbs100g(), 0.01);
        assertNotNull(catalog.find("french fries"));
        assertNull(catalog.find("zzzz-not-a-food"));
    }
}
