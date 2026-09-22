package gold.debug.windowstolinux.shared.backup.contract.validation;

import java.util.Objects;

import gold.debug.windowstolinux.shared.backup.manifest.BackupManifest;
import gold.debug.windowstolinux.shared.backup.manifest.BackupMember;

/**
 * Applies resource policy to a decoded manifest before any extraction. / 在任何提取前把资源策略应用到已解码清单。
 */
public final class BackupManifestValidator {
    /**
     * Bound backup archive policy collaborator for explicit validation and resource-bound policy.
     * <p>处理显式校验及资源边界策略的备份归档策略协作对象。
     */
    private final BackupArchivePolicy policy;

    /**
     * Creates a validator using explicit resource bounds. / 使用显式资源边界创建校验器。
     *
     * @param policy explicit validation and resource-bound policy / 显式校验及资源边界策略
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public BackupManifestValidator(BackupArchivePolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * Rejects member count, path and declared-size violations. / 拒绝成员数量、路径和声明大小违规。
     *
     * @param manifest validated ownership or backup inventory document / 已验证归属或备份资源清单文档
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public void validate(BackupManifest manifest) throws BackupException {
        Objects.requireNonNull(manifest, "manifest");
        if (manifest.members().size() > policy.maximumMembers()) {
            throw BackupException.create(BackupFailureType.LIMIT_EXCEEDED, "manifest member count exceeds policy");
        }
        long total = 0;
        for (BackupMember member : manifest.members()) {
            if (member.path().length() > policy.maximumPathLength()) {
                throw BackupException.create(BackupFailureType.LIMIT_EXCEEDED, "manifest member path exceeds policy");
            }
            if (member.size() > policy.maximumMemberBytes()) {
                throw BackupException.create(BackupFailureType.LIMIT_EXCEEDED, "manifest member size exceeds policy");
            }
            try {
                total = Math.addExact(total, member.size());
            } catch (ArithmeticException exception) {
                throw BackupException.create(BackupFailureType.LIMIT_EXCEEDED, "manifest total size overflow",
                        exception);
            }
            if (total > policy.maximumTotalBytes()) {
                throw BackupException.create(BackupFailureType.LIMIT_EXCEEDED, "manifest total size exceeds policy");
            }
        }
    }
}
