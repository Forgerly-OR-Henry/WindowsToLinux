package gold.debug.windowstolinux.web.api.controller;

import gold.debug.windowstolinux.web.api.error.WebErrorResponse;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Uses the same safe response contract for servlet error dispatches. */
@RestController
public final class WebErrorController implements ErrorController {
    @RequestMapping("/error")
    public ResponseEntity<WebErrorResponse> error(HttpServletRequest request) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        var error = WebErrorResponse.http(status instanceof Integer value ? value : 500);
        return ResponseEntity.status(error.status()).body(error);
    }
}
