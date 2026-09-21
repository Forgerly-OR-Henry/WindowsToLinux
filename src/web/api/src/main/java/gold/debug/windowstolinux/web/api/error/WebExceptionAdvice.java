package gold.debug.windowstolinux.web.api.error;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Translates controller failures into stable responses without exposing exception internals.
 * <p>将控制器失败转换为稳定响应，不暴露异常内部信息。
 */
@RestControllerAdvice
public final class WebExceptionAdvice {
    /**
     * Maps an uncommitted HTTP failure to the safe structured error response; returns null after response commitment.
     * <p>将尚未提交响应的 HTTP 失败映射为安全结构化错误响应；响应提交后返回 null。
     *
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @param response response / 响应
     * @return safe error response, or null if the HTTP response was already committed / 安全错误响应；HTTP 响应已提交时为 null
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<WebErrorResponse> handle(Exception failure, HttpServletResponse response) {
        if (response.isCommitted()) return null;
        WebErrorResponse error = WebErrorResponse.from(failure);
        return ResponseEntity.status(error.status()).body(error);
    }
}
