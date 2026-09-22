package gold.debug.windowstolinux.web.api.controller;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import gold.debug.windowstolinux.web.api.config.WebHttpPolicy;
import gold.debug.windowstolinux.web.service.WebApplicationService;
import gold.debug.windowstolinux.web.service.contract.*;
import gold.debug.windowstolinux.web.service.contract.validation.WebRequestValidator;
import gold.debug.windowstolinux.web.service.persistence.serialization.WebJsonCodec;
import gold.debug.windowstolinux.web.task.scheduler.WebTaskScheduler;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/**
 * Exposes task HTTP operations through the Web application service.
 * <p>通过 Web 应用服务公开任务 HTTP 操作。
 */
@RestController
@RequestMapping("/api/v1/tasks")
public final class WebTaskController {
    /**
     * Bound web application service collaborator for application service used by the caller.
     * <p>处理调用方使用的应用服务的Web应用服务协作对象。
     */
    private final WebApplicationService service;

    /**
     * Facts and dependencies scoped to the current operation.
     * <p>限定于当前操作的事实及依赖。
     */
    private final WebRequestContext context;

    /**
     * Tasks.
     * <p>任务集合。
     */
    private final WebTaskScheduler tasks;

    /**
     * Bound web http policy collaborator for explicit validation and resource-bound policy.
     * <p>处理显式校验及资源边界策略的WebHTTP策略协作对象。
     */
    private final WebHttpPolicy policy;

    /**
     * Closing.
     * <p>关闭中。
     */
    private volatile boolean closing;
    /**
     * Binds the supplied dependencies and state for web task controller.
     * <p>为Web任务控制器绑定传入的依赖及状态。
     *
     * @param service application service used by the caller / 调用方使用的应用服务
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param tasks tasks / 任务集合
     * @param policy explicit validation and resource-bound policy / 显式校验及资源边界策略
     */
    public WebTaskController(WebApplicationService service, WebRequestContext context, WebTaskScheduler tasks,
            WebHttpPolicy policy) {
        this.service = service;
        this.context = context;
        this.tasks = tasks;
        this.policy = policy;
    }

    /**
     * Lists json node.
     * <p>列出JSON节点。
     *
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     */
    @GetMapping
    public JsonNode list() {
        return tasks.list(context);
    }

    /**
     * Handles the get HTTP request through the reviewed Web service boundary.
     * <p>通过已审阅 Web 服务边界处理取得 HTTP 请求。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     */
    @GetMapping("/{id}")
    public JsonNode get(@PathVariable String id) {
        return tasks.get(context, id);
    }

    /**
     * Creates json node.
     * <p>创建JSON节点。
     *
     * @param body body / 正文
     * @return json node / JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JsonNode create(@RequestBody JsonNode body) throws Exception {
        WebRequestValidator.fields(body, "kind", "input");
        return WebJsonCodec.object().put("id", tasks.submit(context,
                service.prepare(context, WebRequestValidator.text(body, "kind", 60), body.path("input"))));
    }

    /**
     * Handles the cancel HTTP request through the reviewed Web service boundary.
     * <p>通过已审阅 Web 服务边界处理取消 HTTP 请求。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param body body / 正文
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     */
    @PostMapping("/{id}/cancel")
    public JsonNode cancel(@PathVariable String id, @RequestBody JsonNode body) {
        WebRequestValidator.fields(body);
        tasks.cancel(context, id);
        return tasks.get(context, id);
    }

    /**
     * Handles the answer HTTP request through the reviewed Web service boundary.
     * <p>通过已审阅 Web 服务边界处理回答 HTTP 请求。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param decision decision / 决定
     * @param body body / 正文
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     */
    @PostMapping("/{id}/decisions/{decision}")
    public JsonNode answer(@PathVariable String id, @PathVariable String decision, @RequestBody JsonNode body) {
        tasks.answer(context, id, decision, body);
        return tasks.get(context, id);
    }

    /**
     * Streams bounded persisted task events from the requested cursor until completion, disconnect or controller shutdown.
     * <p>从请求游标流式传输有界持久化任务事件，直至完成、断连或控制器关闭。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param after after / 之后
     * @param lastEvent last event / 上次事件
     * @param response response / 响应
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    @GetMapping(value = "/{id}/events", produces = "text/event-stream")
    public void events(@PathVariable String id, @RequestParam(defaultValue = "0") long after,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEvent, HttpServletResponse response)
            throws Exception {
        tasks.get(context, id);
        long cursor = lastEvent == null ? after : Long.parseLong(lastEvent);
        if (cursor < 0)
            throw new IllegalArgumentException("Invalid event cursor");
        response.setContentType("text/event-stream;charset=UTF-8");
        var output = response.getOutputStream();
        long heartbeat = System.nanoTime(), started = heartbeat;
        while (!closing) {
            String state = tasks.get(context, id).path("state").asText();
            JsonNode events = tasks.events(context, id, cursor);
            for (var event : events) {
                cursor = event.path("sequence").asLong();
                output.write(("id: " + cursor + "\nevent: task\ndata: " + WebJsonCodec.write(event) + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            output.flush();
            if (Set.of("SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED", "REVALIDATION_REQUIRED").contains(state)
                    && events.size() < 500)
                break;
            if (!policy.streamTimeout().isZero() && System.nanoTime() - started >= policy.streamTimeout().toNanos())
                break;
            if (System.nanoTime() - heartbeat >= policy.heartbeat().toNanos()) {
                output.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
                heartbeat = System.nanoTime();
            }
            Thread.sleep(policy.eventPoll());
        }
    }

    /**
     * Marks the controller as closing when the Spring context shuts down.
     * <p>Spring 上下文关闭时将控制器标记为正在关闭。
     */
    @EventListener(ContextClosedEvent.class)
    public void stopping() {
        closing = true;
    }
}
