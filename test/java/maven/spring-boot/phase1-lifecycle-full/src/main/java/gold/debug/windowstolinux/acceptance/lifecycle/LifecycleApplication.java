package gold.debug.windowstolinux.acceptance.lifecycle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class LifecycleApplication {
    public static void main(String[] args) {
        SpringApplication.run(LifecycleApplication.class, args);
    }

    @RestController
    static class HealthController {
        @GetMapping("/health")
        String health() {
            return "lifecycle-full";
        }
    }
}
