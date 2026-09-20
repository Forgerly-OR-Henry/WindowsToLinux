package gold.debug.windowstolinux.web.api.error;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public final class WebExceptionAdvice {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<WebErrorResponse> handle(Exception failure, HttpServletResponse response) {
        if (response.isCommitted()) return null;
        WebErrorResponse error = WebErrorResponse.from(failure);
        return ResponseEntity.status(error.status()).body(error);
    }
}
