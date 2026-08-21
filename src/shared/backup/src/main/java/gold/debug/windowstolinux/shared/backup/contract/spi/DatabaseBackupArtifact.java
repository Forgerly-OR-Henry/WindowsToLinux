package gold.debug.windowstolinux.shared.backup.contract.spi;

import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabase;

import java.util.List;
import java.util.Objects;

/** Opaque remote export artifact with exact integrity and consistency evidence. / 带精确完整性与一致性证据的不透明远程导出物。 */
public record DatabaseBackupArtifact(
        String artifactId,
        long byteCount,
        String sha256,
        BackupDatabase database,
        List<String> evidence
) {
    /** Validates an export artifact without exposing a remote path. / 在不暴露远程路径的情况下校验导出物。 */
    public DatabaseBackupArtifact {
        artifactId = DatabaseContractRules.identifier(artifactId, "artifactId");
        if (byteCount < 1) throw new IllegalArgumentException("database artifact must not be empty");
        sha256 = Objects.requireNonNull(sha256, "sha256");
        if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("database artifact hash is invalid");
        database = Objects.requireNonNull(database, "database");
        evidence = DatabaseContractRules.evidence(evidence);
    }
}
