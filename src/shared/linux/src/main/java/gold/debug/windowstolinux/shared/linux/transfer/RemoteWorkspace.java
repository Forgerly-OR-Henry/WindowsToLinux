package gold.debug.windowstolinux.shared.linux.transfer;

import java.util.Locale;
import java.util.Objects;

/**
 * Fixed, server-side paths derived only from a validated application ID and source digest.
 *
 *  <p>仅根据已验证应用 ID 和源码摘要推导的固定服务端路径。
 *
 * @param applicationId managed application identifier / 受管应用标识
 * @param sourceSha256 SHA-256 identity of the frozen source snapshot / 已冻结源码快照的 SHA-256 身份
 */
public record RemoteWorkspace(String applicationId, String sourceSha256) {
    /**
     * Validates and binds the inputs required by remote workspace.
     * <p>校验并绑定远端工作区所需输入。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param sourceSha256 SHA-256 identity of the frozen source snapshot / 已冻结源码快照的 SHA-256 身份
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public RemoteWorkspace {
        applicationId = Objects.requireNonNull(applicationId, "applicationId").toLowerCase(Locale.ROOT);
        sourceSha256 = Objects.requireNonNull(sourceSha256, "sourceSha256");
        if (!applicationId.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("applicationId must use lowercase letters, digits and hyphens");
        }
        if (!sourceSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sourceSha256 must be lowercase SHA-256");
        }
    }

    /**
     * Checks the condition represented by {@code candidateId}.
     *
     *  <p>检查 {@code candidateId} 表示的条件。
     *
     * @return the operation result / 操作结果
     */
    public String candidateId() {
        return applicationId + "-" + sourceSha256.substring(0, 16);
    }

    /**
     * Checks the condition represented by {@code candidateRoot}.
     *
     *  <p>检查 {@code candidateRoot} 表示的条件。
     *
     * @return the operation result / 操作结果
     */
    public String candidateRoot() {
        return "/var/lib/windowstolinux/work/" + candidateId();
    }
}
