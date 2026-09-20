package gold.debug.windowstolinux.web.api.controller;

import tools.jackson.databind.JsonNode;
import gold.debug.windowstolinux.web.service.WebApplicationService;
import gold.debug.windowstolinux.web.service.contract.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/preferences")
public final class WebPreferenceController {
    private final WebApplicationService service;
    private final WebRequestContext context;
    public WebPreferenceController(WebApplicationService service, WebRequestContext context) { this.service = service; this.context = context; }

    @GetMapping public JsonNode get() throws Exception { return service.preferences(context); }
    @PutMapping public JsonNode save(@RequestBody JsonNode body) throws Exception { return service.savePreferences(context, body); }
}
