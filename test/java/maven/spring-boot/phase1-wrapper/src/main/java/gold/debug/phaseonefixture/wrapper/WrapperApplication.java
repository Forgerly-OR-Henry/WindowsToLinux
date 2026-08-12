package gold.debug.phaseonefixture.wrapper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@SpringBootApplication
public class WrapperApplication {
    public static void main(String[] args) {
        SpringApplication.run(WrapperApplication.class, args);
    }

    @RestController
    static class HealthController {
        @GetMapping(path = "/", produces = MediaType.TEXT_HTML_VALUE)
        String landing() {
            return "<!doctype html><html><head><title>Phase One Wrapper Service</title></head>"
                    + "<body><h1>Phase One Wrapper Service</h1><p>Wrapper application is ready.</p></body></html>";
        }

        @GetMapping("/wrapper-health")
        Map<String, String> health() {
            return Map.of("status", "UP", "builder", "maven-wrapper");
        }
    }
}
