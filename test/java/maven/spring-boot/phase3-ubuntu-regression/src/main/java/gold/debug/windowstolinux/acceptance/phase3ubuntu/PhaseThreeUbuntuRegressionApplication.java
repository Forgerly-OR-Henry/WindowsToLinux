package gold.debug.windowstolinux.acceptance.phase3ubuntu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Dedicated application identity for repeatable Phase Three Ubuntu acceptance. / 用于可重复第三期 Ubuntu 验收的专用应用身份。 */
@SpringBootApplication
@RestController
public class PhaseThreeUbuntuRegressionApplication {
    public static void main(String[] arguments) {
        SpringApplication.run(PhaseThreeUbuntuRegressionApplication.class, arguments);
    }

    @GetMapping("/actuator/health")
    Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/")
    Map<String, String> landing() {
        return Map.of("message", "phase three ubuntu regression");
    }
}
