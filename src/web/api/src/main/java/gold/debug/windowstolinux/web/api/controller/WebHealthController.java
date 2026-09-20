package gold.debug.windowstolinux.web.api.controller;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public final class WebHealthController {
    @GetMapping("/api/v1/health")
    public Map<String,String> health() { return Map.of("status", "ok", "mode", "internal-test"); }
}
