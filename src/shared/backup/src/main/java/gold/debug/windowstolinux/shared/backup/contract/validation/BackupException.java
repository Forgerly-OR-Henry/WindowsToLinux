package gold.debug.windowstolinux.shared.backup.contract.validation;

import gold.debug.windowstolinux.shared.model.failure.FailureCarrier;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.io.IOException;
import java.util.Objects;

/** Structured checked failure for backup operations. / 备份操作的结构化受检失败。 */
public final class BackupException extends IOException implements FailureCarrier {
    private final FailureDescriptor failure;

    /** Creates one structured backup failure. / 创建一次结构化备份失败。 */
    public BackupException(FailureDescriptor failure, Throwable cause) {
        super(Objects.requireNonNull(failure, "failure").diagnostic(), cause);
        this.failure = failure;
    }

    /** Creates a module-owned failure with no nested cause. / 创建不带嵌套原因的模块自有失败。 */
    public static BackupException create(BackupFailureType type, String diagnostic) {
        return create(type, diagnostic, null);
    }

    /** Creates a module-owned failure. / 创建模块自有失败。 */
    public static BackupException create(BackupFailureType type, String diagnostic, Throwable cause) {
        return new BackupException(FailureDescriptor.create(Objects.requireNonNull(type, "type"),
                OperationIdentity.create(), diagnostic), cause);
    }

    @Override public FailureDescriptor failure() { return failure; }
}
