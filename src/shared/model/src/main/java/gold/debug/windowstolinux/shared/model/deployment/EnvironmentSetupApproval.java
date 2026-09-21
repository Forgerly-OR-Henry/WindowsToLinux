package gold.debug.windowstolinux.shared.model.deployment;

import java.time.Instant;
import java.util.Objects;

/**
 * A single, explicit confirmation to install the fixed managed-deployment Ubuntu toolset on one trusted target. It deliberately carries no package, command or path input.
 *
 *  <p>在一个可信目标上安装固定受管部署 Ubuntu 工具集的单次明确确认。它刻意不携带包、命令或路径输入。
 *
 * @param serverId persisted server identifier / 持久化服务器标识
 * @param installationAccepted installation accepted / 安装已接受
 * @param confirmedAt confirmed at / 已确认时刻
 */
public record EnvironmentSetupApproval(
        String serverId,
        boolean installationAccepted,
        Instant confirmedAt
) {
    /**
     * Validates and binds the inputs required by environment setup approval.
     * <p>校验并绑定环境SetupApproval所需输入。
     *
     * @param serverId persisted server identifier / 持久化服务器标识
     * @param installationAccepted installation accepted / 安装已接受
     * @param confirmedAt confirmed at / 已确认时刻
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public EnvironmentSetupApproval {
        serverId = identifier(serverId, "serverId");
        confirmedAt = Objects.requireNonNull(confirmedAt, "confirmedAt");
    }

    /**
     * Rejects a declined or differently targeted confirmation before SSH mutation begins.
     *
     *  <p>在 SSH 修改开始前拒绝被否决或目标不匹配的确认。
     *
     * @param expectedServerId expected server id / 预期服务器标识
     */
    public void requireAcceptedFor(String expectedServerId) {
        if (!installationAccepted) {
            throw new DeploymentApprovalException(DeploymentApprovalFailureType.CONFIRMATION_REQUIRED,
                    "Explicit confirmation is required before installing the managed-deployment target environment");
        }
        if (!serverId.equals(identifier(expectedServerId, "expectedServerId"))) {
            throw new DeploymentApprovalException(DeploymentApprovalFailureType.SERVER_MISMATCH,
                    "Environment preparation approval does not belong to the current target server");
        }
    }

    /**
     * Validates an identifier against the bounded syntax of the owning contract.
     * <p>按所属契约的有界语法验证标识。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return identifier text / 标识文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String identifier(String value, String name) {
        value = Objects.requireNonNull(value, name).toLowerCase(java.util.Locale.ROOT);
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }
}
