package gold.debug.windowstolinux.shared.backup.manifest;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Stable managed-application, server and version-set identity. / 稳定的受管应用、服务器与版本集合身份。
 *
 * @param applicationId managed application identifier / 受管应用标识
 * @param serverId persisted server identifier / 持久化服务器标识
 * @param managedRoot managed root / 受管根目录
 * @param legacyReleaseIdentity legacy release identity / 历史发布身份
 * @param releaseSetSha256 release set sha 256 / 发布集合SHA256
 */
public record BackupIdentity(
        String applicationId,
        String serverId,
        String managedRoot,
        Optional<String> legacyReleaseIdentity,
        Optional<String> releaseSetSha256
) {
    /**
     * Creates one schema-v5 identity with an exact release-set digest. / 创建带精确发布集合摘要的 schema v5 身份。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param serverId persisted server identifier / 持久化服务器标识
     * @param managedRoot managed root / 受管根目录
     * @param releaseSetSha256 release set sha 256 / 发布集合SHA256
     */
    public BackupIdentity(String applicationId, String serverId, String managedRoot, String releaseSetSha256) {
        this(applicationId, serverId, managedRoot, Optional.empty(), Optional.of(releaseSetSha256));
    }

    /**
     * Validates the mutually exclusive legacy or exact release identity. / 校验互斥的旧版或精确发布身份。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param serverId persisted server identifier / 持久化服务器标识
     * @param managedRoot managed root / 受管根目录
     * @param legacyReleaseIdentity legacy release identity / 历史发布身份
     * @param releaseSetSha256 release set sha 256 / 发布集合SHA256
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public BackupIdentity {
        applicationId = BackupManifestRules.identifier(applicationId, "applicationId");
        serverId = BackupManifestRules.identifier(serverId, "serverId");
        managedRoot = BackupManifestRules.requiredText(managedRoot, "managedRoot", 512);
        legacyReleaseIdentity = Objects.requireNonNull(legacyReleaseIdentity, "legacyReleaseIdentity")
                .map(value -> BackupManifestRules.identifier(value, "legacyReleaseIdentity"));
        releaseSetSha256 = Objects.requireNonNull(releaseSetSha256, "releaseSetSha256")
                .map(value -> canonicalSha256(value, "releaseSetSha256"));
        if (legacyReleaseIdentity.isPresent() == releaseSetSha256.isPresent()) {
            throw new IllegalArgumentException("exactly one release identity representation is required");
        }
    }

    /**
     * Builds backup identity from the supplied legacy inputs.
     * <p>根据所提供历史输入构建备份身份。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param serverId persisted server identifier / 持久化服务器标识
     * @param managedRoot managed root / 受管根目录
     * @param releaseIdentity digest identifying the exact published release / 标识精确已发布版本的摘要
     * @return backup identity from the supplied legacy inputs / 根据所提供历史输入构建备份身份
     */
    static BackupIdentity legacy(
            String applicationId, String serverId, String managedRoot, String releaseIdentity) {
        return new BackupIdentity(applicationId, serverId, managedRoot,
                Optional.of(releaseIdentity), Optional.empty());
    }

    /**
     * Validates and canonicalizes a SHA-256 identity digest.
     * <p>校验并规范化 SHA-256 身份摘要。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return canonical sha 256 text / 规范SHA256文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String canonicalSha256(String value, String field) {
        value = Objects.requireNonNull(value, field).trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be canonical SHA-256");
        }
        return value;
    }
}
