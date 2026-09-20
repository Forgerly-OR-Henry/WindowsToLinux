package gold.debug.windowstolinux.shared.model.health;

import java.net.URI;

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
        url = gold.debug.windowstolinux.shared.model.project.application.ApplicationEndpoint.requireAccessUrl(url);
    }

}
