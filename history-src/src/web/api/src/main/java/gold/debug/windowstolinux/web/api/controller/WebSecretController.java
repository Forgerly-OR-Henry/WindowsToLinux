package gold.debug.windowstolinux.web.api.controller;

import gold.debug.windowstolinux.web.service.WebApplicationService;
import gold.debug.windowstolinux.web.service.contract.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/**
 * Exposes secret HTTP operations through the Web application service.
 * <p>通过 Web 应用服务公开秘密 HTTP 操作。
 */
@RestController
@RequestMapping("/api/v1/secrets")
public final class WebSecretController {
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
     * Binds the supplied dependencies and state for web secret controller.
     * <p>为Web秘密控制器绑定传入的依赖及状态。
     *
     * @param service application service used by the caller / 调用方使用的应用服务
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     */
    public WebSecretController(WebApplicationService service, WebRequestContext context) {
        this.service = service;
        this.context = context;
    }

    /**
     * Persists json node.
     * <p>持久化JSON节点。
     *
     * @param body body / 正文
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode save(@RequestBody JsonNode body) throws Exception {
        return service.saveSecret(context, body);
    }
}
