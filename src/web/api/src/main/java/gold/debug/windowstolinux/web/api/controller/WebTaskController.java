package gold.debug.windowstolinux.web.api.controller;

import tools.jackson.databind.JsonNode;
import gold.debug.windowstolinux.web.api.config.WebHttpPolicy;
import gold.debug.windowstolinux.web.service.WebApplicationService;
import gold.debug.windowstolinux.web.service.contract.*;
import gold.debug.windowstolinux.web.task.scheduler.WebTaskScheduler;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/tasks")
public final class WebTaskController {
    private final WebApplicationService service;
    private final WebRequestContext context;
    private final WebTaskScheduler tasks;
    private final WebHttpPolicy policy;
    private volatile boolean closing;
    public WebTaskController(WebApplicationService service, WebRequestContext context, WebTaskScheduler tasks, WebHttpPolicy policy) {
        this.service = service; this.context = context; this.tasks = tasks; this.policy = policy;
    }
    @GetMapping public JsonNode list() { return tasks.list(context); }
    @GetMapping("/{id}") public JsonNode get(@PathVariable String id) { return tasks.get(context, id); }
    @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
    public JsonNode create(@RequestBody JsonNode body) throws Exception {
        WebJson.fields(body, "kind", "input");
        return WebJson.object().put("id", tasks.submit(context, service.prepare(context, WebJson.text(body, "kind", 60), body.path("input"))));
    }
    @PostMapping("/{id}/cancel")
    public JsonNode cancel(@PathVariable String id, @RequestBody JsonNode body) {
        WebJson.fields(body); tasks.cancel(context, id); return tasks.get(context, id);
    }
    @PostMapping("/{id}/decisions/{decision}")
    public JsonNode answer(@PathVariable String id, @PathVariable String decision, @RequestBody JsonNode body) {
        tasks.answer(context, id, decision, body); return tasks.get(context, id);
    }
    @GetMapping(value = "/{id}/events", produces = "text/event-stream")
    public void events(@PathVariable String id, @RequestParam(defaultValue = "0") long after,
                       @RequestHeader(value = "Last-Event-ID", required = false) String lastEvent, HttpServletResponse response) throws Exception {
        tasks.get(context, id);
        long cursor = lastEvent == null ? after : Long.parseLong(lastEvent);
        if (cursor < 0) throw new IllegalArgumentException("Invalid event cursor");
        response.setContentType("text/event-stream;charset=UTF-8");
        var output = response.getOutputStream(); long heartbeat = System.nanoTime(), started = heartbeat;
        while (!closing) {
            String state = tasks.get(context, id).path("state").asText();
            JsonNode events = tasks.events(context, id, cursor);
            for (var event : events) {
                cursor = event.path("sequence").asLong();
                output.write(("id: " + cursor + "\nevent: task\ndata: " + WebJson.write(event) + "\n\n").getBytes(StandardCharsets.UTF_8));
            }
            output.flush();
            if (Set.of("SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED", "REVALIDATION_REQUIRED").contains(state) && events.size() < 500) break;
            if (!policy.streamTimeout().isZero() && System.nanoTime() - started >= policy.streamTimeout().toNanos()) break;
            if (System.nanoTime() - heartbeat >= policy.heartbeat().toNanos()) {
                output.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8)); output.flush(); heartbeat = System.nanoTime();
            }
            Thread.sleep(policy.eventPoll());
        }
    }
    @EventListener(ContextClosedEvent.class) public void stopping() { closing = true; }
}
