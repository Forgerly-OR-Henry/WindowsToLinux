package gold.debug.windowstolinux.web.api.controller;

import tools.jackson.databind.JsonNode;
import gold.debug.windowstolinux.web.service.WebApplicationService;
import gold.debug.windowstolinux.web.service.contract.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Exposes backup HTTP operations through the Web application service.
 * <p>通过 Web 应用服务公开备份 HTTP 操作。
 */
@RestController
@RequestMapping("/api/v1/backups")
public final class WebBackupController {
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
     * Binds the supplied dependencies and state for web backup controller.
     * <p>为Web备份控制器绑定传入的依赖及状态。
     *
     * @param service application service used by the caller / 调用方使用的应用服务
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     */
    public WebBackupController(WebApplicationService service, WebRequestContext context) { this.service = service; this.context = context; }

    /**
     * Lists json node.
     * <p>列出JSON节点。
     *
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    @GetMapping public JsonNode list() throws Exception { return service.listBackups(context); }
    /**
     * Handles the upload HTTP request through the reviewed Web service boundary.
     * <p>通过已审阅 Web 服务边界处理上传 HTTP 请求。
     *
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    @PutMapping(value = "/upload", consumes = "application/octet-stream") @ResponseStatus(HttpStatus.CREATED)
    public JsonNode upload(@RequestParam String name, jakarta.servlet.http.HttpServletRequest request) throws Exception {
        return service.uploadBackup(context, name, request.getInputStream());
    }
    /**
     * Handles the download HTTP request through the reviewed Web service boundary.
     * <p>通过已审阅 Web 服务边界处理下载 HTTP 请求。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param response response / 响应
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    @GetMapping(value = "/{id}/download", produces = "application/zip")
    public void download(@PathVariable String id, jakarta.servlet.http.HttpServletResponse response) throws Exception {
        var file = service.downloadBackup(context, id);
        response.setContentType("application/zip"); response.setContentLengthLong(java.nio.file.Files.size(file));
        response.setHeader("Content-Disposition", "attachment; filename=\"backup.zip\"");
        try (var input = java.nio.file.Files.newInputStream(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            input.transferTo(response.getOutputStream());
        }
    }
}
