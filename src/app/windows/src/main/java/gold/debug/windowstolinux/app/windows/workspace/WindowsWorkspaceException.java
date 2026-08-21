package gold.debug.windowstolinux.app.windows.workspace;

import gold.debug.windowstolinux.shared.model.failure.FailureCarrier;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.io.IOException;
import java.util.Objects;

/** Structured checked failure for the desktop-owned Windows workspace. / 桌面持有的 Windows 工作区结构化受检失败。 */
public final class WindowsWorkspaceException extends IOException implements FailureCarrier {
    private final FailureDescriptor failure;

    /** Creates one workspace failure. / 创建一次工作区失败。 */
    public WindowsWorkspaceException(FailureDescriptor failure, Throwable cause) {
        super(Objects.requireNonNull(failure, "failure").diagnostic(), cause);
        this.failure = failure;
    }

    /** Creates a typed workspace failure. / 创建类型化工作区失败。 */
    public static WindowsWorkspaceException create(
            WindowsWorkspaceFailureType type, String diagnostic, Throwable cause) {
        return new WindowsWorkspaceException(
                FailureDescriptor.create(type, OperationIdentity.create(), diagnostic), cause);
    }

    @Override public FailureDescriptor failure() { return failure; }
}
