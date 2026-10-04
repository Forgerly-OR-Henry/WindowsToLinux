package gold.debug.windowstolinux.mod.deployment.api;

import gold.debug.windowstolinux.appapi.server.model.ServerId;

/** Stable deployment application and exact server identity. / 稳定部署应用与精确服务器身份。
 * @param id application identifier / 应用标识
 * @param server exact platform server / 精确平台服务器 */
public record DeploymentApplicationId(String id, ServerId server) {
    /** Validates the public identity. / 校验公开身份。
     * @param id application identifier / 应用标识
     * @param server exact platform server / 精确平台服务器 */
    public DeploymentApplicationId {
        if (id == null || !id.matches("[a-z0-9][a-z0-9-]{0,62}"))
            throw new IllegalArgumentException("Invalid application identity");
        java.util.Objects.requireNonNull(server);
    }
}
