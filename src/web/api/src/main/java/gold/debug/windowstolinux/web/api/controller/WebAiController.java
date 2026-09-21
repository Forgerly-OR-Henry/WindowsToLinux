package gold.debug.windowstolinux.web.api.controller;

import gold.debug.windowstolinux.web.service.contract.validation.WebRequestValidator;

import gold.debug.windowstolinux.web.service.persistence.serialization.WebJsonCodec;

import tools.jackson.databind.JsonNode;
import gold.debug.windowstolinux.web.service.WebApplicationService;
import gold.debug.windowstolinux.web.service.contract.*;
import gold.debug.windowstolinux.web.task.scheduler.WebTaskScheduler;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Exposes ai HTTP operations through the Web application service.
 * <p>通过 Web 应用服务公开AI HTTP 操作。
 */
@RestController
@RequestMapping("/api/v1/ai")
public final class WebAiController {
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
     * Binds the supplied dependencies and state for web ai controller.
     * <p>为WebAI控制器绑定传入的依赖及状态。
     *
     * @param service application service used by the caller / 调用方使用的应用服务
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param tasks tasks / 任务集合
     */
    public WebAiController(WebApplicationService service, WebRequestContext context, WebTaskScheduler tasks) {
        this.service = service; this.context = context; this.tasks = tasks;
    }
    /**
     * Handles the list HTTP request through the reviewed Web service boundary.
     * <p>通过已审阅 Web 服务边界处理列表 HTTP 请求。
     *
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    @GetMapping("/profiles") public JsonNode list() throws Exception { return service.listAi(context); }
    /**
     * Handles the create HTTP request through the reviewed Web service boundary.
     * <p>通过已审阅 Web 服务边界处理创建 HTTP 请求。
     *
     * @param body body / 正文
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    @PostMapping("/profiles") @ResponseStatus(HttpStatus.ACCEPTED)
    public JsonNode create(@RequestBody JsonNode body) throws Exception {
        return WebJsonCodec.object().put("id", tasks.submit(context, service.saveAi(context, null, body)));
    }
    /**
     * Handles the update HTTP request through the reviewed Web service boundary.
     * <p>通过已审阅 Web 服务边界处理更新 HTTP 请求。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param body body / 正文
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    @PutMapping("/profiles/{id}") @ResponseStatus(HttpStatus.ACCEPTED)
    public JsonNode update(@PathVariable String id, @RequestBody JsonNode body) throws Exception {
        return WebJsonCodec.object().put("id", tasks.submit(context, service.saveAi(context, id, body)));
    }
    /**
     * Handles the delete HTTP request through the reviewed Web service boundary.
     * <p>通过已审阅 Web 服务边界处理删除 HTTP 请求。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param body body / 正文
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    @DeleteMapping("/profiles/{id}")
    public JsonNode delete(@PathVariable String id, @RequestBody JsonNode body) throws Exception {
        WebRequestValidator.fields(body, "version"); service.deleteAi(context, id, body.path("version").asLong()); return WebJsonCodec.object();
    }
    /**
     * Handles the enabled HTTP request through the reviewed Web service boundary.
     * <p>通过已审阅 Web 服务边界处理启用 HTTP 请求。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param body body / 正文
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    @PutMapping("/profiles/{id}/enabled")
    public JsonNode enabled(@PathVariable String id, @RequestBody JsonNode body) throws Exception { return service.enableAi(context, id, body); }
    /**
     * Handles the reorder HTTP request through the reviewed Web service boundary.
     * <p>通过已审阅 Web 服务边界处理排序 HTTP 请求。
     *
     * @param body body / 正文
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    @PutMapping("/order")
    public JsonNode reorder(@RequestBody JsonNode body) throws Exception { return service.reorderAi(context, body); }
}
