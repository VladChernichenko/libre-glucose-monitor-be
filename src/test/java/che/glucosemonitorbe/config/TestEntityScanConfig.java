package che.glucosemonitorbe.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EntityScan(basePackages = "che.glucosemonitorbe.domain")
@EnableJpaRepositories(basePackages = "che.glucosemonitorbe.repository")
public class TestEntityScanConfig {
}
