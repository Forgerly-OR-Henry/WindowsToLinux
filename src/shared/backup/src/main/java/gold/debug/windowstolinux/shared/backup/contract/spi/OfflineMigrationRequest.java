package gold.debug.windowstolinux.shared.backup.contract.spi;

import java.util.Locale;
import java.util.Objects;

/** Immutable identity and safety approvals for one offline migration attempt. / 单次离线迁移尝试的不可变身份及安全批准。 */
public record OfflineMigrationRequest(
        String migrationId,
        String applicationId,
        String sourceServerId,
        String targetServerId,
        long estimatedBytes,
        boolean stopWindowApproved
) {
    /** Validates distinct endpoints before the stopped-write final archive exists. / 在停写最终归档产生前校验不同端点。 */
    public OfflineMigrationRequest {
        migrationId = identifier(migrationId, "migrationId");
        applicationId = identifier(applicationId, "applicationId");
        sourceServerId = identifier(sourceServerId, "sourceServerId");
        targetServerId = identifier(targetServerId, "targetServerId");
        if (sourceServerId.equals(targetServerId)) {
            throw new IllegalArgumentException("offline migration requires different source and target servers");
        }
        if (estimatedBytes < 1) throw new IllegalArgumentException("estimatedBytes must be positive");
    }

    private static String identifier(String value, String field) {
        value = Objects.requireNonNull(value, field).trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }
}
