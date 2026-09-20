package gold.debug.windowstolinux.shared.backup.crypto;

import gold.debug.windowstolinux.shared.model.failure.FailureCarrier;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.Objects;

/** Structured checked failure that never carries backup plaintext or passwords. / 绝不携带备份明文或密码的结构化受检失败。 */
public final class BackupSecretException extends Exception implements FailureCarrier {
    private final FailureDescriptor failure;

    /** Creates one backup-secret failure. / 创建一次备份秘密失败。 */
    public BackupSecretException(FailureDescriptor failure, Throwable cause) {
        super(Objects.requireNonNull(failure, "failure").diagnostic(), cause);
        this.failure = failure;
    }

    /** Creates a module-owned backup-secret failure. / 创建模块自有备份秘密失败。 */
    public static BackupSecretException create(BackupSecretFailureType type, String diagnostic) {
        return create(type, diagnostic, null);
    }

    /** Creates a module-owned backup-secret failure with its internal cause. / 创建带内部原因的模块自有备份秘密失败。 */
    public static BackupSecretException create(
            BackupSecretFailureType type, String diagnostic, Throwable cause) {
        return new BackupSecretException(FailureDescriptor.create(Objects.requireNonNull(type, "type"),
                OperationIdentity.create(), diagnostic), cause);
    }

    @Override public FailureDescriptor failure() { return failure; }
}
