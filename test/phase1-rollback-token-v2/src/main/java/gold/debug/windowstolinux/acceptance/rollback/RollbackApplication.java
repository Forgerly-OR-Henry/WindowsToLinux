package gold.debug.windowstolinux.acceptance.rollback;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class RollbackApplication {
    public static void main(String[] args) {
        SpringApplication.run(RollbackApplication.class, args);
    }

    @RestController
    static class ProofController {
        @GetMapping("/health")
        String health() {
            return "UP-v2";
        }

        @GetMapping("/rollback-proof")
        ResponseEntity<String> rollbackProof() {
            return ResponseEntity.status(503).body("v2-must-be-rolled-back");
        }
    }
}
