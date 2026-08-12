package gold.debug.windowstolinux.acceptance.startuptcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class StartupTcpApplication {
    public static void main(String[] args) {
        SpringApplication.run(StartupTcpApplication.class, args);
    }

    @RestController
    static class HealthController {
        @GetMapping("/health")
        String health() {
            return "wrong-port-candidate";
        }
    }
}
