package gold.debug.windowstolinux.acceptance.firstfailure;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class FirstFailureApplication {
    public static void main(String[] args) {
        SpringApplication.run(FirstFailureApplication.class, args);
    }

    @RestController
    static class HealthController {
        @GetMapping("/health")
        String health() {
            return "UP";
        }
    }
}
