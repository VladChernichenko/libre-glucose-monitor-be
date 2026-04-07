package che.glucosemonitorbe.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "nutrition.api-ninjas")
public class NutritionProperties {
    private boolean enabled = false;
    private String baseUrl = "https://api.api-ninjas.com/v1/nutrition";
    private String apiKey = "";
    private int timeoutMs = 3000;
}
