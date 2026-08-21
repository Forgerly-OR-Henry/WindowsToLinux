package gold.debug.windowstolinux.shared.backup.manifest;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Stable managed-application, server and version-set identity. / 稳定的受管应用、服务器与版本集合身份。 */
public record BackupIdentity(
        String applicationId,
        String serverId,
        String managedRoot,
        Optional<String> legacyReleaseIdentity,
        Optional<String> releaseSetSha256
) {
    /** Creates one schema-v4 identity with an exact release-set digest. / 创建带精确发布集合摘要的 schema v4 身份。 */
    public BackupIdentity(String applicationId, String serverId, String managedRoot, String releaseSetSha256) {
        this(applicationId, serverId, managedRoot, Optional.empty(), Optional.of(releaseSetSha256));
    }

    /** Validates the mutually exclusive legacy or exact release identity. / 校验互斥的旧版或精确发布身份。 */
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

    static BackupIdentity legacy(
            String applicationId, String serverId, String managedRoot, String releaseIdentity) {
        return new BackupIdentity(applicationId, serverId, managedRoot,
                Optional.of(releaseIdentity), Optional.empty());
    }

    private static String canonicalSha256(String value, String field) {
        value = Objects.requireNonNull(value, field).trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be canonical SHA-256");
        }
        return value;
    }
}
