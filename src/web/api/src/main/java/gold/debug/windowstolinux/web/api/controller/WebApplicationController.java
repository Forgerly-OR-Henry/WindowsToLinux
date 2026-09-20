package gold.debug.windowstolinux.web.api.controller;

import tools.jackson.databind.JsonNode;
import gold.debug.windowstolinux.web.service.WebApplicationService;
import gold.debug.windowstolinux.web.service.contract.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/applications")
public final class WebApplicationController {
    private final WebApplicationService service;
    private final WebRequestContext context;
    public WebApplicationController(WebApplicationService service, WebRequestContext context) { this.service = service; this.context = context; }

    @GetMapping public JsonNode list() throws Exception { return service.listApplications(context); }
    @PutMapping("/{id}")
    public JsonNode update(@PathVariable String id, @RequestBody JsonNode body) throws Exception { return service.editApplication(context, id, body); }
}
