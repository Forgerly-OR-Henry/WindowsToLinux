package gold.debug.windowstolinux.app.service.backup;

import java.util.Objects;

import gold.debug.windowstolinux.shared.backup.contract.validation.BackupArchiveValidation;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupProvenanceStatus;

/**
 * Safe local backup summary after complete archive validation. / 完整归档校验后的安全本地备份摘要。
 *
 * @param applicationId managed application identifier / 受管应用标识
 * @param schemaVersion the configuration schema version / 配置模式版本
 * @param createdAtUtc created at utc / 已创建时刻UTC
 * @param componentCount component count / 组件数量
 * @param memberCount member count / 成员数量
 * @param verifiedBytes verified bytes / 已验证字节
 * @param archiveSha256 SHA-256 identity of the reviewed archive / 已审阅归档的 SHA-256 身份
 * @param provenanceStatus provenance status / 来源证据状态
 */
public record BackupArchiveInspection(String applicationId, String schemaVersion, String createdAtUtc,
        int componentCount, int memberCount, long verifiedBytes, String archiveSha256,
        BackupProvenanceStatus provenanceStatus) {
    /**
     * Creates an immutable inspection from complete validation evidence. / 从完整校验证据创建不可变检查结果。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param schemaVersion the configuration schema version / 配置模式版本
     * @param createdAtUtc created at utc / 已创建时刻UTC
     * @param componentCount component count / 组件数量
     * @param memberCount member count / 成员数量
     * @param verifiedBytes verified bytes / 已验证字节
     * @param archiveSha256 SHA-256 identity of the reviewed archive / 已审阅归档的 SHA-256 身份
     * @param provenanceStatus provenance status / 来源证据状态
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public BackupArchiveInspection {
        applicationId = Objects.requireNonNull(applicationId, "applicationId");
        schemaVersion = Objects.requireNonNull(schemaVersion, "schemaVersion");
        createdAtUtc = Objects.requireNonNull(createdAtUtc, "createdAtUtc");
        archiveSha256 = Objects.requireNonNull(archiveSha256, "archiveSha256");
        provenanceStatus = Objects.requireNonNull(provenanceStatus, "provenanceStatus");
        if (componentCount < 1 || memberCount < 1 || verifiedBytes < 0 || !archiveSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("backup inspection evidence is invalid");
        }
    }

    /**
     * Projects trusted validation fields without exposing archive internals. / 投影可信校验字段且不公开归档内部对象。
     *
     * @param validation validation / 校验
     * @return constructed or resolved backup archive inspection / 构造或解析得到的备份归档检查
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static BackupArchiveInspection from(BackupArchiveValidation validation) {
        Objects.requireNonNull(validation, "validation");
        var manifest = validation.manifest();
        return new BackupArchiveInspection(manifest.applicationId(), manifest.schemaVersion(), manifest.createdAtUtc(),
                manifest.inventory().components().size(), manifest.members().size(), validation.verifiedBytes(),
                validation.archiveSha256(), validation.provenanceStatus());
    }
}
