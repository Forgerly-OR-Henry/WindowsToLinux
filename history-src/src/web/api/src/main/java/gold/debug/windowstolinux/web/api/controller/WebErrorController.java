package gold.debug.windowstolinux.web.api.controller;

import gold.debug.windowstolinux.web.api.error.WebErrorResponse;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Uses the safe error-response contract for servlet error dispatches.
 * <p>为 servlet 错误分派使用安全错误响应契约。
 */
@RestController
public final class WebErrorController implements ErrorController {
    /**
     * Handles the error HTTP request through the reviewed Web service boundary.
     * <p>通过已审阅 Web 服务边界处理错误 HTTP 请求。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved response entity / 构造或解析得到的响应Entity
     */
    @RequestMapping("/error")
    public ResponseEntity<WebErrorResponse> error(HttpServletRequest request) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        var error = WebErrorResponse.http(status instanceof Integer value ? value : 500);
        return ResponseEntity.status(error.status()).body(error);
    }
}
