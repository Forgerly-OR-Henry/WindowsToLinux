package gold.debug.windowstolinux.web.api.controller;

import tools.jackson.databind.JsonNode;
import gold.debug.windowstolinux.web.service.WebApplicationService;
import gold.debug.windowstolinux.web.service.contract.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/servers")
public final class WebServerController {
    private final WebApplicationService service;
    private final WebRequestContext context;
    public WebServerController(WebApplicationService service, WebRequestContext context) { this.service = service; this.context = context; }

    @GetMapping public JsonNode list() throws Exception { return service.listServers(context); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public JsonNode create(@RequestBody JsonNode body) throws Exception { return service.saveServer(context, null, body); }
    @PutMapping("/{id}")
    public JsonNode update(@PathVariable String id, @RequestBody JsonNode body) throws Exception { return service.saveServer(context, id, body); }
    @DeleteMapping("/{id}")
    public JsonNode delete(@PathVariable String id, @RequestBody JsonNode body) throws Exception {
        WebJson.fields(body, "version"); service.deleteServer(context, id, body.path("version").asLong()); return WebJson.object();
    }
}
