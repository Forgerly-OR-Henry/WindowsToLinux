package gold.debug.windowstolinux.shared.backup.contract.spi;

import gold.debug.windowstolinux.shared.backup.manifest.BackupManifest;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Narrow platform request for one already validated restore candidate. / 单个已验证恢复候选的平台窄请求。
 *
 * @param manifest validated ownership or backup inventory document / 已验证归属或备份资源清单文档
 * @param localCandidateRoot local candidate root / 本地候选根目录
 * @param archiveSha256 SHA-256 identity of the reviewed archive / 已审阅归档的 SHA-256 身份
 * @param verifiedBytes verified bytes / 已验证字节
 * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
 * @param targetServerId target server id / 目标服务器标识
 * @param rebuildFromSource rebuild from source / 重建来源源码
 */
public record RestoreCandidateRequest(
        BackupManifest manifest,
        Path localCandidateRoot,
        String archiveSha256,
        long verifiedBytes,
        String candidateId,
        String targetServerId,
        boolean rebuildFromSource
) {
    /**
     * Binds immutable archive evidence to a controlled candidate and target. / 将不可变归档证据绑定到受控候选及目标。
     *
     * @param manifest validated ownership or backup inventory document / 已验证归属或备份资源清单文档
     * @param localCandidateRoot local candidate root / 本地候选根目录
     * @param archiveSha256 SHA-256 identity of the reviewed archive / 已审阅归档的 SHA-256 身份
     * @param verifiedBytes verified bytes / 已验证字节
     * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
     * @param targetServerId target server id / 目标服务器标识
     * @param rebuildFromSource rebuild from source / 重建来源源码
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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

    /**
     * Validates an identifier against the bounded syntax of the owning contract.
     * <p>按所属契约的有界语法验证标识。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return identifier text / 标识文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String identifier(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }
}
