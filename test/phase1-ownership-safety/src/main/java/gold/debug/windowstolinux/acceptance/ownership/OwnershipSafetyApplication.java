package gold.debug.windowstolinux.acceptance.ownership;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class OwnershipSafetyApplication {
    public static void main(String[] args) {
        SpringApplication.run(OwnershipSafetyApplication.class, args);
    }

    @RestController
    static class HealthController {
        @GetMapping("/health")
        String health() {
            return "ownership-safety";
        }
    }
}
