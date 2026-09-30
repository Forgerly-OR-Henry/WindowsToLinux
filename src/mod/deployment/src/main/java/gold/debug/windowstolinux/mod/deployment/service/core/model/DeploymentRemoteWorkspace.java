package gold.debug.windowstolinux.mod.deployment.service.core.model;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Fixed, server-side paths derived only from a validated application ID and compressed archive digest.
 *
 *  <p>仅根据已验证应用 ID 和压缩归档字节摘要推导的固定服务端路径。
 *
 * @param applicationId managed application identifier / 受管应用标识
 * @param archiveSha256 SHA-256 of actual tar.gz bytes, distinct from the source manifest / 实际 tar.gz 字节SHA-256，区别于源码清单
 * @param operation explicit later publication attempt, absent for the original candidate / 明确后续发布尝试；原候选为空
 */
public record DeploymentRemoteWorkspace(String applicationId, String archiveSha256, Optional<UUID> operation) {
    /** Preserves original source-derived candidate bytes. / 保留原来源派生候选字节。
     * @param applicationId original managed unit / 原受管单元
     * @param archiveSha256 original compressed source / 原压缩来源 */
    public DeploymentRemoteWorkspace(String applicationId, String archiveSha256) {
        this(applicationId, archiveSha256, Optional.empty());
    }
    /**
     * Validates and binds the inputs required by remote workspace.
     * <p>校验并绑定远端工作区所需输入。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param archiveSha256 actual compressed archive SHA-256 / 实际压缩归档SHA-256
     * @param operation explicit later operation, never the task context / 明确后续操作；绝非任务上下文
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DeploymentRemoteWorkspace {
        applicationId = Objects.requireNonNull(applicationId, "applicationId").toLowerCase(Locale.ROOT);
        archiveSha256 = Objects.requireNonNull(archiveSha256, "archiveSha256");
        operation = Objects.requireNonNull(operation, "operation");
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
        return applicationId + "-" + candidateSuffix();
    }

    /** Binds a later candidate to both the same source and its explicit operation. / 将后续候选同时绑定至同一来源及明确操作。
     * @return exact sixteen-character candidate suffix / 精确十六字符候选后缀 */
    private String candidateSuffix() {
        if (operation.isEmpty()) return archiveSha256.substring(0, 16);
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = ("deployment-publication-attempt-v1\n" + archiveSha256 + "\n" + operation.orElseThrow())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return java.util.HexFormat.of().formatHex(digest.digest(bytes)).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("JDK SHA-256 unavailable", unavailable);
        }
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
