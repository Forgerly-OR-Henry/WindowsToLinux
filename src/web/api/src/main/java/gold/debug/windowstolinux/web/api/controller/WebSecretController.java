package gold.debug.windowstolinux.web.api.controller;

import tools.jackson.databind.JsonNode;
import gold.debug.windowstolinux.web.service.WebApplicationService;
import gold.debug.windowstolinux.web.service.contract.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/secrets")
public final class WebSecretController {
    private final WebApplicationService service;
    private final WebRequestContext context;
    public WebSecretController(WebApplicationService service, WebRequestContext context) { this.service = service; this.context = context; }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public JsonNode save(@RequestBody JsonNode body) throws Exception { return service.saveSecret(context, body); }
}
