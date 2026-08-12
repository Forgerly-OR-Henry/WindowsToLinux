package gold.debug.windowstolinux.acceptance.disconnect;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class DisconnectApplication {
    public static void main(String[] args) {
        SpringApplication.run(DisconnectApplication.class, args);
    }

    @RestController
    static class ProofController {
        @GetMapping("/disconnect-proof")
        ResponseEntity<String> proof() {
            return ResponseEntity.status(503).body("candidate-was-published-before-session-loss");
        }
    }
}
