package gold.debug.windowstolinux.web.api.controller;

import tools.jackson.databind.JsonNode;
import gold.debug.windowstolinux.web.service.WebApplicationService;
import gold.debug.windowstolinux.web.service.contract.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sources")
public final class WebSourceController {
    private final WebApplicationService service;
    private final WebRequestContext context;
    public WebSourceController(WebApplicationService service, WebRequestContext context) { this.service = service; this.context = context; }

    @GetMapping public JsonNode list() throws Exception { return service.listSources(context); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public JsonNode begin(@RequestBody JsonNode body) throws Exception { return service.beginSource(context, body); }
    @PostMapping("/{id}/complete")
    public JsonNode complete(@PathVariable String id, @RequestBody JsonNode body) throws Exception {
        WebJson.fields(body); return service.finishSource(context, id);
    }
    @PutMapping(value = "/{id}/files", consumes = "application/octet-stream")
    public JsonNode upload(@PathVariable String id, @RequestParam String path, jakarta.servlet.http.HttpServletRequest request) throws Exception {
        service.uploadSource(context, id, path, request.getInputStream()); return WebJson.object();
    }
    @PutMapping(value = "/{id}/archive", consumes = "application/octet-stream")
    public JsonNode archive(@PathVariable String id, @RequestParam String format, jakarta.servlet.http.HttpServletRequest request) throws Exception {
        service.uploadArchive(context, id, format, request.getInputStream()); return WebJson.object();
    }
}
