package che.glucosemonitorbe.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        // Enable trailing slash matching for all paths
        // This allows both /api/cob-settings and /api/cob-settings/ to work
        configurer.setUseTrailingSlashMatch(true);
    }
}
