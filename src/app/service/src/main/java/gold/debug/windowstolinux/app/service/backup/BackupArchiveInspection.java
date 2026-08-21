package gold.debug.windowstolinux.app.service.backup;

import gold.debug.windowstolinux.shared.backup.contract.validation.BackupArchiveValidation;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupProvenanceStatus;

import java.util.Objects;

/** Safe local backup summary after complete archive validation. / 完整归档校验后的安全本地备份摘要。 */
public record BackupArchiveInspection(
        String applicationId,
        String schemaVersion,
        String createdAtUtc,
        int componentCount,
        int memberCount,
        long verifiedBytes,
        String archiveSha256,
        BackupProvenanceStatus provenanceStatus
) {
    /** Creates an immutable inspection from complete validation evidence. / 从完整校验证据创建不可变检查结果。 */
    public BackupArchiveInspection {
        applicationId = Objects.requireNonNull(applicationId, "applicationId");
        schemaVersion = Objects.requireNonNull(schemaVersion, "schemaVersion");
        createdAtUtc = Objects.requireNonNull(createdAtUtc, "createdAtUtc");
        archiveSha256 = Objects.requireNonNull(archiveSha256, "archiveSha256");
        provenanceStatus = Objects.requireNonNull(provenanceStatus, "provenanceStatus");
        if (componentCount < 1 || memberCount < 1 || verifiedBytes < 0
                || !archiveSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("backup inspection evidence is invalid");
        }
    }

    /** Projects trusted validation fields without exposing archive internals. / 投影可信校验字段且不公开归档内部对象。 */
    public static BackupArchiveInspection from(BackupArchiveValidation validation) {
        Objects.requireNonNull(validation, "validation");
        var manifest = validation.manifest();
        return new BackupArchiveInspection(manifest.applicationId(), manifest.schemaVersion(), manifest.createdAtUtc(),
                manifest.inventory().components().size(), manifest.members().size(), validation.verifiedBytes(),
                validation.archiveSha256(), validation.provenanceStatus());
    }
}
