package gold.debug.windowstolinux.mod.deployment.service.core.model;

import java.util.Locale;
import java.util.Objects;

/**
 * Fixed, server-side paths derived only from a validated application ID and compressed archive digest.
 *
 *  <p>仅根据已验证应用 ID 和压缩归档字节摘要推导的固定服务端路径。
 *
 * @param applicationId managed application identifier / 受管应用标识
 * @param archiveSha256 SHA-256 of actual tar.gz bytes, distinct from the source manifest / 实际 tar.gz 字节SHA-256，区别于源码清单
 */
public record DeploymentRemoteWorkspace(String applicationId, String archiveSha256) {
    /**
     * Validates and binds the inputs required by remote workspace.
     * <p>校验并绑定远端工作区所需输入。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param archiveSha256 actual compressed archive SHA-256 / 实际压缩归档SHA-256
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DeploymentRemoteWorkspace {
        applicationId = Objects.requireNonNull(applicationId, "applicationId").toLowerCase(Locale.ROOT);
        archiveSha256 = Objects.requireNonNull(archiveSha256, "archiveSha256");
        if (!applicationId.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("applicationId must use lowercase letters, digits and hyphens");
        }
        if (!archiveSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("archiveSha256 must be lowercase SHA-256");
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
        return applicationId + "-" + archiveSha256.substring(0, 16);
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
