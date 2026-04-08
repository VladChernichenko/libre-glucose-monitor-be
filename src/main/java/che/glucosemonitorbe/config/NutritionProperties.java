package che.glucosemonitorbe.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "nutrition.spoonacular")
public class NutritionProperties {
    private boolean enabled = false;
    private String baseUrl = "https://api.spoonacular.com";
    private String apiKey = "";
    private int timeoutMs = 3000;
}
