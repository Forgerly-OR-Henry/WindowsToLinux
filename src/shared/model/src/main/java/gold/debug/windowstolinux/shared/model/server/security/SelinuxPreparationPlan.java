package gold.debug.windowstolinux.shared.model.server.security;

import java.util.Objects;

/** Binds system consent to one server, boot, configuration and observed checkpoint. / 将系统批准绑定到服务器、启动、配置及观测检查点。 */
public record SelinuxPreparationPlan(String serverId, String bootId, String configurationSha256,
                                     LinuxSecurityState securityState, SelinuxPreparationState state) {
    public SelinuxPreparationPlan {
        if (!Objects.requireNonNull(serverId, "serverId").matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("Invalid server identity");
        }
        if (!Objects.requireNonNull(bootId, "bootId").matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("Invalid boot identity");
        }
        if (!Objects.requireNonNull(configurationSha256, "configurationSha256").matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid configuration digest");
        }
        Objects.requireNonNull(securityState, "securityState");
        Objects.requireNonNull(state, "state");
    }
}
