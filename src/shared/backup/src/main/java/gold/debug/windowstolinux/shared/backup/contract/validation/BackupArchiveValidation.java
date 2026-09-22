package gold.debug.windowstolinux.shared.backup.contract.validation;

import java.util.Objects;

import gold.debug.windowstolinux.shared.backup.manifest.BackupManifest;

/**
 * Immutable evidence produced only after all archive members pass validation. / 所有归档成员通过校验后才生成的不可变证据。
 *
 * @param archiveSha256 SHA-256 identity of the reviewed archive / 已审阅归档的 SHA-256 身份
 * @param manifest validated ownership or backup inventory document / 已验证归属或备份资源清单文档
 * @param verifiedBytes verified bytes / 已验证字节
 * @param provenanceStatus provenance status / 来源证据状态
 */
public record BackupArchiveValidation(String archiveSha256, BackupManifest manifest, long verifiedBytes,
        BackupProvenanceStatus provenanceStatus) {
    /**
     * Validates completed integrity evidence. / 校验已完成的完整性证据。
     *
     * @param archiveSha256 SHA-256 identity of the reviewed archive / 已审阅归档的 SHA-256 身份
     * @param manifest validated ownership or backup inventory document / 已验证归属或备份资源清单文档
     * @param verifiedBytes verified bytes / 已验证字节
     * @param provenanceStatus provenance status / 来源证据状态
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public BackupArchiveValidation {
        archiveSha256 = Objects.requireNonNull(archiveSha256, "archiveSha256");
        if (!archiveSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("invalid archive SHA-256");
        manifest = Objects.requireNonNull(manifest, "manifest");
        if (verifiedBytes < 0)
            throw new IllegalArgumentException("verifiedBytes must not be negative");
        provenanceStatus = Objects.requireNonNull(provenanceStatus, "provenanceStatus");
    }
}
