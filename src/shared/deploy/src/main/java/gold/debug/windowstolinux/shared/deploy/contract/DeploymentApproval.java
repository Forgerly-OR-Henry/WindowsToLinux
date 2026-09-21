package gold.debug.windowstolinux.shared.deploy.contract;

import java.time.Instant;
import java.util.Objects;

/**
 * A per-source, per-server, per-application user approval; it is never reusable.
 *
 *  <p>按源码、服务器和应用分别绑定的用户批准，绝不可复用。
 *
 * @param applicationId managed application identifier / 受管应用标识
 * @param sourceSha256 SHA-256 identity of the frozen source snapshot / 已冻结源码快照的 SHA-256 身份
 * @param serverId persisted server identifier / 持久化服务器标识
 * @param rootBuildAccepted root build accepted / 根目录构建已接受
 * @param confirmedAt confirmed at / 已确认时刻
 */
public record DeploymentApproval(
        String applicationId,
        String sourceSha256,
        String serverId,
        boolean rootBuildAccepted,
        Instant confirmedAt
) {
    /**
     * Validates and binds the inputs required by deployment approval.
     * <p>校验并绑定部署Approval所需输入。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param sourceSha256 SHA-256 identity of the frozen source snapshot / 已冻结源码快照的 SHA-256 身份
     * @param serverId persisted server identifier / 持久化服务器标识
     * @param rootBuildAccepted root build accepted / 根目录构建已接受
     * @param confirmedAt confirmed at / 已确认时刻
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DeploymentApproval {
        applicationId = identifier(applicationId, "applicationId");
        serverId = identifier(serverId, "serverId");
        sourceSha256 = Objects.requireNonNull(sourceSha256, "sourceSha256");
        if (!sourceSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sourceSha256 must be lowercase SHA-256");
        }
        confirmedAt = Objects.requireNonNull(confirmedAt, "confirmedAt");
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
        value = Objects.requireNonNull(value, name);
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }
}
