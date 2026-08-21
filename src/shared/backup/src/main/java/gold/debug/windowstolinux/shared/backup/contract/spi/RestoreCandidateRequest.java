package gold.debug.windowstolinux.shared.backup.contract.spi;

import gold.debug.windowstolinux.shared.backup.manifest.BackupManifest;

import java.nio.file.Path;
import java.util.Objects;

/** Narrow platform request for one already validated restore candidate. / 单个已验证恢复候选的平台窄请求。 */
public record RestoreCandidateRequest(
        BackupManifest manifest,
        Path localCandidateRoot,
        String archiveSha256,
        long verifiedBytes,
        String candidateId,
        String targetServerId,
        boolean rebuildFromSource
) {
    /** Binds immutable archive evidence to a controlled candidate and target. / 将不可变归档证据绑定到受控候选及目标。 */
    public RestoreCandidateRequest {
        manifest = Objects.requireNonNull(manifest, "manifest");
        localCandidateRoot = Objects.requireNonNull(localCandidateRoot, "localCandidateRoot")
                .toAbsolutePath().normalize();
        archiveSha256 = Objects.requireNonNull(archiveSha256, "archiveSha256").trim();
        if (!archiveSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("archiveSha256 is invalid");
        }
        if (verifiedBytes < 0) throw new IllegalArgumentException("verifiedBytes must not be negative");
        candidateId = Objects.requireNonNull(candidateId, "candidateId").trim();
        String expectedCandidateId = manifest.applicationId() + "-" + archiveSha256.substring(0, 16);
        if (!candidateId.equals(expectedCandidateId)) {
            throw new IllegalArgumentException("candidateId is not bound to the backup digest");
        }
        if (!localCandidateRoot.getFileName().toString().equals(candidateId)) {
            throw new IllegalArgumentException("local candidate root differs from candidateId");
        }
        targetServerId = identifier(targetServerId, "targetServerId");
    }

    private static String identifier(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }
}
