package gold.debug.windowstolinux.app.windows.uninstall;

import gold.debug.windowstolinux.shared.model.failure.FailureCarrier;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.io.IOException;
import java.util.Objects;

/** Structured checked failure for desktop uninstall boundaries. / 桌面卸载边界的结构化受检失败。 */
public final class DesktopUninstallException extends IOException implements FailureCarrier {
    private final FailureDescriptor failure;

    private DesktopUninstallException(FailureDescriptor failure, Throwable cause) {
        super(Objects.requireNonNull(failure, "failure").diagnostic(), cause);
        this.failure = failure;
    }

    /** Creates a typed uninstall failure. / 创建类型化卸载失败。 */
    public static DesktopUninstallException create(DesktopUninstallFailureType type, String diagnostic) {
        return new DesktopUninstallException(
                FailureDescriptor.create(type, OperationIdentity.create(), diagnostic), null);
    }

    @Override public FailureDescriptor failure() { return failure; }
}
