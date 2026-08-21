package gold.debug.windowstolinux.shared.backup.contract.spi;

import java.util.Locale;
import java.util.Objects;

/** Immutable identity and safety approvals for one offline migration attempt. / 单次离线迁移尝试的不可变身份及安全批准。 */
public record OfflineMigrationRequest(
        String migrationId,
        String applicationId,
        String sourceServerId,
        String targetServerId,
        String backupSha256,
        String targetCandidateId,
        long estimatedBytes,
        boolean stopWindowApproved
) {
    /** Validates distinct endpoints and archive-bound candidate identity. / 校验不同端点及归档绑定的候选身份。 */
    public OfflineMigrationRequest {
        migrationId = identifier(migrationId, "migrationId");
        applicationId = identifier(applicationId, "applicationId");
        sourceServerId = identifier(sourceServerId, "sourceServerId");
        targetServerId = identifier(targetServerId, "targetServerId");
        if (sourceServerId.equals(targetServerId)) {
            throw new IllegalArgumentException("offline migration requires different source and target servers");
        }
        backupSha256 = Objects.requireNonNull(backupSha256, "backupSha256").trim();
        if (!backupSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("backupSha256 is invalid");
        }
        targetCandidateId = Objects.requireNonNull(targetCandidateId, "targetCandidateId").trim();
        String expectedCandidate = applicationId + "-" + backupSha256.substring(0, 16);
        if (!targetCandidateId.equals(expectedCandidate)) {
            throw new IllegalArgumentException("targetCandidateId is not bound to the backup digest");
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
