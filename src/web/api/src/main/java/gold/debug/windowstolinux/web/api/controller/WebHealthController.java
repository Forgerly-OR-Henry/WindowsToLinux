package gold.debug.windowstolinux.web.api.controller;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * Exposes health HTTP operations through the Web application service.
 * <p>通过 Web 应用服务公开健康 HTTP 操作。
 */
@RestController
public final class WebHealthController {
    /**
     * Handles the health HTTP request through the reviewed Web service boundary.
     * <p>通过已审阅 Web 服务边界处理健康 HTTP 请求。
     *
     * @return constructed or resolved map / 构造或解析得到的映射
     */
    @GetMapping("/api/v1/health")
    public Map<String,String> health() { return Map.of("status", "ok", "mode", "internal-test"); }
}
