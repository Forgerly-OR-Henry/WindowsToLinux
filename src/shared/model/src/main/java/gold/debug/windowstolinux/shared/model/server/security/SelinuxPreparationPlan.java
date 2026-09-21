package gold.debug.windowstolinux.shared.model.server.security;

import java.util.Objects;

/**
 * Binds system consent to one server, boot, configuration and observed checkpoint. / 将系统批准绑定到服务器、启动、配置及观测检查点。
 *
 * @param serverId persisted server identifier / 持久化服务器标识
 * @param bootId boot id / 启动标识
 * @param configurationSha256 the immutable configuration digest / 不可变配置摘要
 * @param securityState security state / 安全状态
 * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
 */
public record SelinuxPreparationPlan(String serverId, String bootId, String configurationSha256,
                                     LinuxSecurityState securityState, SelinuxPreparationState state) {
    /**
     * Validates and binds the inputs required by selinux preparation plan.
     * <p>校验并绑定Selinux准备计划所需输入。
     *
     * @param serverId persisted server identifier / 持久化服务器标识
     * @param bootId boot id / 启动标识
     * @param configurationSha256 the immutable configuration digest / 不可变配置摘要
     * @param securityState security state / 安全状态
     * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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
