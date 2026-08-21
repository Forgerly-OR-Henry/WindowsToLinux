package gold.debug.windowstolinux.app.db.failure;

import gold.debug.windowstolinux.shared.model.failure.FailureCarrier;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.sql.SQLException;
import java.util.Objects;

/** Structured desktop persistence failure. / 结构化桌面持久化失败。 */
public final class DesktopPersistenceException extends SQLException implements FailureCarrier {
    private final FailureDescriptor failure;

    /** Creates one persistence failure occurrence. / 创建一次持久化失败。 */
    public DesktopPersistenceException(FailureDescriptor failure, Throwable cause) {
        super(Objects.requireNonNull(failure, "failure").diagnostic(), cause);
        this.failure = failure;
    }

    /** Creates a typed persistence failure. / 创建类型化持久化失败。 */
    public static DesktopPersistenceException create(
            DesktopPersistenceFailureType type, String diagnostic, Throwable cause) {
        return new DesktopPersistenceException(
                FailureDescriptor.create(type, OperationIdentity.create(), diagnostic), cause);
    }

    @Override public FailureDescriptor failure() { return failure; }
}
