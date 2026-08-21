package gold.debug.windowstolinux.app.windows.update;

import gold.debug.windowstolinux.shared.model.failure.FailureCarrier;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.io.IOException;
import java.util.Objects;

/** Structured checked failure for desktop update verification and replacement. / 桌面更新验证及替换的结构化受检失败。 */
public final class DesktopUpdateException extends IOException implements FailureCarrier {
    private final FailureDescriptor failure;

    private DesktopUpdateException(FailureDescriptor failure, Throwable cause) {
        super(Objects.requireNonNull(failure, "failure").diagnostic(), cause);
        this.failure = failure;
    }

    /** Creates a typed update failure. / 创建类型化更新失败。 */
    public static DesktopUpdateException create(DesktopUpdateFailureType type, String diagnostic) {
        return create(type, diagnostic, null);
    }

    /** Creates a typed update failure with a safe cause. / 创建带安全原因的类型化更新失败。 */
    public static DesktopUpdateException create(
            DesktopUpdateFailureType type, String diagnostic, Throwable cause) {
        return new DesktopUpdateException(
                FailureDescriptor.create(type, OperationIdentity.create(), diagnostic), cause);
    }

    @Override public FailureDescriptor failure() { return failure; }
}
