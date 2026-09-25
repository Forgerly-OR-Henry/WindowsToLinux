package gold.debug.windowstolinux.shared.backup.contract.validation;

import java.io.IOException;
import java.util.Objects;

import gold.debug.windowstolinux.shared.model.failure.FailureCarrier;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

/**
 * Structured checked failure for backup operations. / 备份操作的结构化受检失败。
 */
public final class BackupException extends IOException implements FailureCarrier {
    /**
     * Structured failure occurrence retained for safe reporting.
     * <p>保留用于安全报告的结构化失败实例。
     */
    private final FailureDescriptor failure;

    /**
     * Creates one structured backup failure. / 创建一次结构化备份失败。
     *
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public BackupException(FailureDescriptor failure, Throwable cause) {
        super(Objects.requireNonNull(failure, "failure").diagnostic(), cause);
        this.failure = failure;
    }

    /**
     * Creates a module-owned failure with no nested cause. / 创建不带嵌套原因的模块自有失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @return a module-owned failure with no nested cause / 不带嵌套原因的模块自有失败
     */
    public static BackupException create(BackupFailureType type, String diagnostic) {
        return create(type, diagnostic, null);
    }

    /**
     * Creates a module-owned failure. / 创建模块自有失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @return a module-owned failure / 模块自有失败
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static BackupException create(BackupFailureType type, String diagnostic, Throwable cause) {
        return new BackupException(
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
