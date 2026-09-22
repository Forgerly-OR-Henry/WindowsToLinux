package gold.debug.windowstolinux.shared.linux.error;

import java.util.Objects;

import gold.debug.windowstolinux.shared.model.failure.FailureCarrier;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

/**
 * An unchecked native database failure containing only a fixed reason. / 只携带固定原因的原生数据库非受检失败。
 */
public final class NativeDatabaseException extends RuntimeException implements FailureCarrier {
    /**
     * Structured failure occurrence retained for safe reporting.
     * <p>保留用于安全报告的结构化失败实例。
     */
    private final FailureDescriptor failure;

    /**
     * Creates a structured failure without SQL, credentials or remote output. / 创建不含 SQL、凭据或远端输出的结构化失败。
     *
     * @param reason reason / 原因
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public NativeDatabaseException(NativeDatabaseFailureType reason) {
        super("Native database operation requires attention: " + Objects.requireNonNull(reason, "reason").name());
        failure = FailureDescriptor.create(reason, OperationIdentity.create(), getMessage());
    }

    /**
     * Returns the exact reason used by bounded recovery branches. / 返回有界恢复分支使用的精确原因。
     *
     * @return the exact reason used by bounded recovery branches / 有界恢复分支使用的精确原因
     */
    public NativeDatabaseFailureType reason() {
        return (NativeDatabaseFailureType) failure.definition();
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
