package gold.debug.samples.tasks;

import static gold.debug.samples.tasks.Models.*;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class TaskController {
    private final TaskStore store;
    public TaskController(TaskStore store) {
        this.store = store;
    }

    @GetMapping("/healthz")
    Object health() throws Exception {
        store.projects();
        return Map.of("status", "ok", "component", "java-tasks", "version", 2);
    }

    @GetMapping("/api/projects")
    Object projects() throws Exception {
        return store.projects();
    }

    @PostMapping("/api/projects")
    Object createProject(@RequestBody Project v) throws Exception {
        return store.createProject(v);
    }

    @GetMapping("/api/members")
    Object members(@RequestParam long projectId) throws Exception {
        return store.members(projectId);
    }

    @GetMapping("/api/tasks")
    Object list(@RequestParam long projectId, @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String label, @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "0") int priority, @RequestParam(defaultValue = "0") long ownerId,
            @RequestParam(defaultValue = "false") boolean overdue, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "25") int size) throws Exception {
        return store.list(projectId, q, label, status, priority, ownerId, overdue, page, size);
    }

    @GetMapping("/api/tasks/{id}")
    Object detail(@PathVariable long id, @RequestParam long projectId) throws Exception {
        return store.detail(id, projectId);
    }

    @GetMapping("/api/stats")
    Object stats(@RequestParam long projectId) throws Exception {
        return store.stats(projectId);
    }

    @PostMapping("/api/tasks")
    Object create(@RequestBody TaskInput v) throws Exception {
        return store.save(null, v);
    }

    @PatchMapping("/api/tasks/{id}")
    Object update(@PathVariable long id, @RequestBody TaskInput v) throws Exception {
        return store.save(id, v);
    }

    @PostMapping("/api/tasks/{id}/transition")
    Object transition(@PathVariable long id, @RequestParam long projectId, @RequestBody Transition v) throws Exception {
        return store.transition(id, projectId, v);
    }

    @PostMapping("/api/tasks/{id}/comments")
    Object comment(@PathVariable long id, @RequestParam long projectId, @RequestBody Comment v) throws Exception {
        return store.comment(id, projectId, v);
    }

    @ExceptionHandler(BusinessError.class)
    ResponseEntity<?> business(BusinessError e) {
        return ResponseEntity.status(e.status).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    ResponseEntity<?> invalid(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", "请求字段或 JSON 格式无效"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> failed(Exception e) {
        org.slf4j.LoggerFactory.getLogger(TaskController.class).error("Task operation failed", e);
        return ResponseEntity.internalServerError().body(Map.of("error", "任务存储操作失败"));
    }
}
