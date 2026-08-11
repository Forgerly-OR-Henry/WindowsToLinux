package gold.debug.windowstolinux.acceptance.hellolifecycle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@SpringBootApplication
@RestController
public class HelloLifecycleApplication {
    public static void main(String[] args) {
        SpringApplication.run(HelloLifecycleApplication.class, args);
    }

    @GetMapping("/")
    Map<String, String> hello() {
        return Map.of("message", "hello from phase-one lifecycle acceptance");
    }

    @GetMapping("/actuator/health")
    Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
