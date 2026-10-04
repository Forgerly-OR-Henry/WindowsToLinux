package gold.debug.windowstolinux.app.ai.http;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Shared exact HTTP endpoint validation for model and audio calls. / 模型和音频调用共用的精确 HTTP 端点校验。 */
public final class HttpUrlChecker {
    /** Prevents instances of the stateless validator. / 禁止无状态校验器实例。 */
    private HttpUrlChecker() {
    }

    /** Validates the exact endpoint without guessing paths or forwarding credentials. / 校验精确端点，不猜路径或转发凭据。
     * @param endpoint configured complete URL / 配置的完整 URL
     * @throws IllegalArgumentException if the URL is not authorized by platform rules / URL 不满足平台规则时 */
    public static void validate(URI endpoint) {
        if (endpoint == null || endpoint.getHost() == null || endpoint.getUserInfo() != null
            || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
            || !("https".equalsIgnoreCase(endpoint.getScheme()) || "http".equalsIgnoreCase(endpoint.getScheme())))
            throw new IllegalArgumentException(
                "Model endpoint must be a complete HTTP(S) URL without credentials, query or fragment");
        if ("http".equalsIgnoreCase(endpoint.getScheme()) && !Set.of("localhost", "127.0.0.1", "::1", "[::1]")
            .contains(endpoint.getHost().toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("Remote model endpoints require HTTPS");
    }
}
