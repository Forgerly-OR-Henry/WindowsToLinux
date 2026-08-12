package gold.debug.windowstolinux.acceptance.hellostopwait;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@SpringBootApplication
@RestController
public class HelloStopWaitApplication {
    public static void main(String[] args) {
        SpringApplication.run(HelloStopWaitApplication.class, args);
    }

    @GetMapping("/")
    Map<String, String> hello() {
        return Map.of("message", "hello from phase-one stop-wait acceptance");
    }

    @GetMapping("/actuator/health")
    Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
