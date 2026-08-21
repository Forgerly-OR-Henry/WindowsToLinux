package gold.debug.windowstolinux.shared.backup.contract.validation;

import gold.debug.windowstolinux.shared.backup.manifest.BackupManifest;

import java.util.Objects;

/** Immutable evidence produced only after all archive members pass validation. / 所有归档成员通过校验后才生成的不可变证据。 */
public record BackupArchiveValidation(
        String archiveSha256,
        BackupManifest manifest,
        long verifiedBytes,
        BackupProvenanceStatus provenanceStatus
) {
    /** Validates completed integrity evidence. / 校验已完成的完整性证据。 */
    public BackupArchiveValidation {
        archiveSha256 = Objects.requireNonNull(archiveSha256, "archiveSha256");
        if (!archiveSha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid archive SHA-256");
        manifest = Objects.requireNonNull(manifest, "manifest");
        if (verifiedBytes < 0) throw new IllegalArgumentException("verifiedBytes must not be negative");
        provenanceStatus = Objects.requireNonNull(provenanceStatus, "provenanceStatus");
    }
}
