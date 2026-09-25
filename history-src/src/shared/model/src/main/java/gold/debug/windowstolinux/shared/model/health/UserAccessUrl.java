package gold.debug.windowstolinux.shared.model.health;

import java.net.URI;

/**
 * A user-declared HTTP(S) URL for reaching the deployed application. It is deliberately distinct from the target-local health endpoint.
 *
 *  <p>用户声明的已部署应用 HTTP(S) 访问 URL。它被刻意设计为与目标机本地健康端点相互独立。
 *
 * @param url URL address / URL 地址
 */
public record UserAccessUrl(URI url) {
    /**
     * Binds the supplied dependencies and state for user access url.
     * <p>为用户访问URL绑定传入的依赖及状态。
     *
     * @param url URL address / URL 地址
     */
    public UserAccessUrl {
        url = gold.debug.windowstolinux.shared.model.project.application.ApplicationEndpoint.requireAccessUrl(url);
    }

}
