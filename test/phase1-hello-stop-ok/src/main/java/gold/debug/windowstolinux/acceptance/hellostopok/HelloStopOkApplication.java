package gold.debug.windowstolinux.acceptance.hellostopok;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@SpringBootApplication
@RestController
public class HelloStopOkApplication {
    public static void main(String[] args) {
        SpringApplication.run(HelloStopOkApplication.class, args);
    }

    @GetMapping("/")
    Map<String, String> hello() {
        return Map.of("message", "hello from phase-one stop-success acceptance");
    }

    @GetMapping("/actuator/health")
    Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
