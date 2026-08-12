package gold.debug.windowstolinux.acceptance.buildlimits;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class BuildLimitsApplication {
    public static void main(String[] args) {
        SpringApplication.run(BuildLimitsApplication.class, args);
    }

    @RestController
    static class HealthController {
        @GetMapping("/health")
        String health() {
            return "baseline-build-limits";
        }
    }
}
