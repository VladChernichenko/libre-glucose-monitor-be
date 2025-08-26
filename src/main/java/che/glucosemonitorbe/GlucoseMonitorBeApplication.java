package che.glucosemonitorbe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;

@SpringBootApplication
@EntityScan("che.glucosemonitorbe.domain")
public class GlucoseMonitorBeApplication {

    public static void main(String[] args) {
        SpringApplication.run(GlucoseMonitorBeApplication.class, args);
    }

}
