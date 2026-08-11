package gold.debug.windowstolinux.acceptance.complex.api;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A minimal business entry point used to prove the user-facing deployment URL.
 *
 * <p>用于验证用户可见部署 URL 的最小业务入口。
 */
@RestController
class LandingController {
    @GetMapping(path = "/", produces = MediaType.TEXT_HTML_VALUE)
    String landing() {
        return "<!doctype html><html><head><title>Phase One Quote Service</title></head>"
                + "<body><h1>Phase One Quote Service</h1><p>Quote API is ready.</p></body></html>";
    }
}
