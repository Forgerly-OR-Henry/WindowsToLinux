package gold.debug.windowstolinux.app.service.backup;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.shared.backup.contract.validation.BackupArchiveValidation;
import gold.debug.windowstolinux.shared.backup.crypto.BackupSecretDocument;
import gold.debug.windowstolinux.shared.backup.restore.BackupRestoreCandidate;
import gold.debug.windowstolinux.shared.config.secretref.ResolvedSecretRevision;

/**
 * Short-lived validated activation material whose secret revisions are cleared on close. / 关闭时清零秘密修订的短生命周期已验证激活材料。
 *
 * @param validation validation / 校验
 * @param restoreCandidate restore candidate / 恢复候选
 * @param localCandidate local candidate / 本地候选
 * @param secretDocument secret document / 秘密文档
 */
record PreparedBackupActivation(BackupArchiveValidation validation, BackupRestoreCandidate restoreCandidate,
        PreparedBackupCandidate localCandidate,
        Optional<BackupSecretDocument> secretDocument) implements AutoCloseable {
    /**
     * Validates and binds the inputs required by prepared backup activation.
     * <p>校验并绑定已准备备份激活所需输入。
     *
     * @param validation validation / 校验
     * @param restoreCandidate restore candidate / 恢复候选
     * @param localCandidate local candidate / 本地候选
     * @param secretDocument secret document / 秘密文档
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    PreparedBackupActivation {
        validation = Objects.requireNonNull(validation, "validation");
        restoreCandidate = Objects.requireNonNull(restoreCandidate, "restoreCandidate");
        localCandidate = Objects.requireNonNull(localCandidate, "localCandidate");
        secretDocument = Objects.requireNonNull(secretDocument, "secretDocument");
        if (secretDocument.isPresent() != !validation.manifest().inventory().secretReferences().isEmpty()) {
            throw new IllegalArgumentException("activation secret presence differs from the manifest");
        }
    }

    /**
     * Returns credential references or scoped secret-access service.
     * <p>返回凭据引用或限定作用域的秘密访问服务。
     *
     * @return credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     */
    List<ResolvedSecretRevision> secrets() {
        return secretDocument.map(BackupSecretDocument::revisions).orElseGet(List::of);
    }

    /**
     * Closes the resources owned by this instance and completes its cleanup boundary.
     * <p>关闭当前实例持有的资源并完成其清理边界。
     */
    @Override
    public void close() {
        secretDocument.ifPresent(BackupSecretDocument::close);
    }
}
