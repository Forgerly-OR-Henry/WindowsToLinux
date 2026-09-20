package gold.debug.windowstolinux.web.api.controller;

import tools.jackson.databind.JsonNode;
import gold.debug.windowstolinux.web.service.WebApplicationService;
import gold.debug.windowstolinux.web.service.contract.*;
import gold.debug.windowstolinux.web.task.scheduler.WebTaskScheduler;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ai")
public final class WebAiController {
    private final WebApplicationService service;
    private final WebRequestContext context;
    private final WebTaskScheduler tasks;
    public WebAiController(WebApplicationService service, WebRequestContext context, WebTaskScheduler tasks) {
        this.service = service; this.context = context; this.tasks = tasks;
    }
    @GetMapping("/profiles") public JsonNode list() throws Exception { return service.listAi(context); }
    @PostMapping("/profiles") @ResponseStatus(HttpStatus.ACCEPTED)
    public JsonNode create(@RequestBody JsonNode body) throws Exception {
        return WebJson.object().put("id", tasks.submit(context, service.saveAi(context, null, body)));
    }
    @PutMapping("/profiles/{id}") @ResponseStatus(HttpStatus.ACCEPTED)
    public JsonNode update(@PathVariable String id, @RequestBody JsonNode body) throws Exception {
        return WebJson.object().put("id", tasks.submit(context, service.saveAi(context, id, body)));
    }
    @DeleteMapping("/profiles/{id}")
    public JsonNode delete(@PathVariable String id, @RequestBody JsonNode body) throws Exception {
        WebJson.fields(body, "version"); service.deleteAi(context, id, body.path("version").asLong()); return WebJson.object();
    }
    @PutMapping("/profiles/{id}/enabled")
    public JsonNode enabled(@PathVariable String id, @RequestBody JsonNode body) throws Exception { return service.enableAi(context, id, body); }
    @PutMapping("/order")
    public JsonNode reorder(@RequestBody JsonNode body) throws Exception { return service.reorderAi(context, body); }
}
