package gold.debug.windowstolinux.shared.model.health;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * A user-declared HTTP(S) URL for reaching the deployed application. It is deliberately distinct from the target-local health endpoint.
 *
 * <p>用户声明的已部署应用 HTTP(S) 访问 URL。它被刻意设计为与目标机本地健康端点相互独立。
 *
 * @param url the {@code url} value / {@code url} 值
 */
public record UserAccessUrl(URI url) {
    /**
     * Creates a {@code UserAccessUrl} instance.
     *
     * <p>创建 {@code UserAccessUrl} 实例。
     *
     * @param url the {@code url} value / {@code url} 值
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public UserAccessUrl {
        url = Objects.requireNonNull(url, "url");
        String scheme = url.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("user access URL must use http or https");
        }
        if (url.getHost() == null || url.getUserInfo() != null || url.getFragment() != null) {
            throw new IllegalArgumentException("user access URL must be an absolute credential-free URL");
        }
        if (isLoopbackOrWildcard(url.getHost())) {
            throw new IllegalArgumentException("user access URL must not use a loopback or wildcard host");
        }
    }

    private static boolean isLoopbackOrWildcard(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.equals("localhost")
                || normalized.equals("0.0.0.0")
                || normalized.equals("::")
                || normalized.equals("::1")
                || normalized.equals("0:0:0:0:0:0:0:1")
                || normalized.startsWith("127.");
    }
}
