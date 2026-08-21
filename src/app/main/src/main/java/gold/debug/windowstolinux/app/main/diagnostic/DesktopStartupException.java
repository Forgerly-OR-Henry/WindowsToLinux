package gold.debug.windowstolinux.app.main.diagnostic;

import gold.debug.windowstolinux.shared.model.failure.FailureCarrier;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.Objects;

/** Structured failure that prevents the desktop from starting safely. / 阻止桌面安全启动的结构化失败。 */
public final class DesktopStartupException extends RuntimeException implements FailureCarrier {
    private final FailureDescriptor failure;

    /** Creates a startup failure occurrence. / 创建一次启动失败。 */
    public DesktopStartupException(FailureDescriptor failure, Throwable cause) {
        super(Objects.requireNonNull(failure, "failure").diagnostic(), cause);
        this.failure = failure;
    }

    /** Creates a typed startup failure. / 创建类型化启动失败。 */
    public static DesktopStartupException create(
            DesktopSystemFailureType type, String diagnostic, Throwable cause) {
        return new DesktopStartupException(
                FailureDescriptor.create(type, OperationIdentity.create(), diagnostic), cause);
    }

    @Override public FailureDescriptor failure() { return failure; }
}
