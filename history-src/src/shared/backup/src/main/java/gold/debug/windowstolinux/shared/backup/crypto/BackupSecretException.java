package gold.debug.windowstolinux.shared.backup.crypto;

import java.util.Objects;

import gold.debug.windowstolinux.shared.model.failure.FailureCarrier;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

/**
 * Structured checked failure that never carries backup plaintext or passwords. / 绝不携带备份明文或密码的结构化受检失败。
 */
public final class BackupSecretException extends Exception implements FailureCarrier {
    /**
     * Structured failure occurrence retained for safe reporting.
     * <p>保留用于安全报告的结构化失败实例。
     */
    private final FailureDescriptor failure;

    /**
     * Creates one backup-secret failure. / 创建一次备份秘密失败。
     *
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public BackupSecretException(FailureDescriptor failure, Throwable cause) {
        super(Objects.requireNonNull(failure, "failure").diagnostic(), cause);
        this.failure = failure;
    }

    /**
     * Creates a module-owned backup-secret failure. / 创建模块自有备份秘密失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @return a module-owned backup-secret failure / 模块自有备份秘密失败
     */
    public static BackupSecretException create(BackupSecretFailureType type, String diagnostic) {
        return create(type, diagnostic, null);
    }

    /**
     * Creates a module-owned backup-secret failure with its internal cause. / 创建带内部原因的模块自有备份秘密失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @return a module-owned backup-secret failure with its internal cause / 带内部原因的模块自有备份秘密失败
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static BackupSecretException create(BackupSecretFailureType type, String diagnostic, Throwable cause) {
        return new BackupSecretException(
                FailureDescriptor.create(Objects.requireNonNull(type, "type"), OperationIdentity.create(), diagnostic),
                cause);
    }

    /**
     * Returns structured failure occurrence retained for safe reporting.
     * <p>返回保留用于安全报告的结构化失败实例。
     *
     * @return structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     */
    @Override
    public FailureDescriptor failure() {
        return failure;
    }
}
