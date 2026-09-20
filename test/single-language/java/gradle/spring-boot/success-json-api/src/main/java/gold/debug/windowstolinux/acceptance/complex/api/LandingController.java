package gold.debug.windowstolinux.acceptance.complex.api;
import gold.debug.windowstolinux.acceptance.complex.config.FixtureConfiguration;
import gold.debug.windowstolinux.acceptance.complex.service.SummaryService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
@RestController
public final class LandingController {
    private final FixtureConfiguration configuration;
    private final SummaryService service;
    public LandingController(FixtureConfiguration configuration, SummaryService service) {
        this.configuration = configuration; this.service = service;
    }
    @GetMapping({"/", "/health", "/actuator/health", "/rollback-proof", "/disconnect-proof", "/wrapper-health"})
    public ResponseEntity<?> root() {
        if (FixtureConfiguration.status() == 503) return ResponseEntity.status(503).body(configuration.label());
        if (FixtureConfiguration.mode().equals("json")) return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(service.summarize(null));
        return ResponseEntity.ok().contentType(new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8)).body(configuration.label());
    }
    @GetMapping("/api/summary")
    public ResponseEntity<?> summary(@RequestParam(name = "values", required = false) String values) {
        if (FixtureConfiguration.status() == 503) return ResponseEntity.status(503).body(configuration.label());
        try { return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(service.summarize(values)); }
        catch (IllegalArgumentException error) { return ResponseEntity.badRequest().body("invalid-values"); }
    }
}
