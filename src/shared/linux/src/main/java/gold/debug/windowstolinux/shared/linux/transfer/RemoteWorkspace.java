package gold.debug.windowstolinux.shared.linux.transfer;

import java.util.Locale;
import java.util.Objects;

/**
 * Fixed, server-side paths derived only from a validated application ID and source digest.
 *
 * <p>仅根据已验证应用 ID 和源码摘要推导的固定服务端路径。
 *
 * @param applicationId the {@code applicationId} value / {@code applicationId} 值
 * @param sourceSha256 the {@code sourceSha256} value / {@code sourceSha256} 值
 */
public record RemoteWorkspace(String applicationId, String sourceSha256) {
    /**
     * Creates a {@code RemoteWorkspace} instance.
     *
     * <p>创建 {@code RemoteWorkspace} 实例。
     *
     * @param applicationId the {@code applicationId} value / {@code applicationId} 值
     * @param sourceSha256 the {@code sourceSha256} value / {@code sourceSha256} 值
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
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
     * <p>检查 {@code candidateId} 表示的条件。
     *
     * @return the operation result / 操作结果
     */
    public String candidateId() {
        return applicationId + "-" + sourceSha256.substring(0, 16);
    }

    /**
     * Checks the condition represented by {@code candidateRoot}.
     *
     * <p>检查 {@code candidateRoot} 表示的条件。
     *
     * @return the operation result / 操作结果
     */
    public String candidateRoot() {
        return "/var/lib/windowstolinux/work/" + candidateId();
    }
}
