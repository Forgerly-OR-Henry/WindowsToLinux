package gold.debug.windowstolinux.shared.linux.error;

import gold.debug.windowstolinux.shared.model.failure.FailureCarrier;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.Map;
import java.util.Objects;

/** A structured connection, protocol, transfer or controlled-operation failure. / 结构化连接、协议、传输或受控操作失败。 */
public final class LinuxOperationException extends Exception implements FailureCarrier {
    private final FailureDescriptor failure;

    /** Creates a Linux failure occurrence. / 创建一次 Linux 失败。 */
    public LinuxOperationException(FailureDescriptor failure, Throwable cause) {
        super(Objects.requireNonNull(failure, "failure").diagnostic(), cause);
        this.failure = failure;
    }

    /** Creates a typed Linux failure. / 创建类型化 Linux 失败。 */
    public static LinuxOperationException create(LinuxOperationFailureType type, String diagnostic) {
        return create(type, Map.of(), diagnostic, null);
    }

    /** Creates a typed Linux failure with its original cause. / 创建带原始原因的类型化 Linux 失败。 */
    public static LinuxOperationException create(
            LinuxOperationFailureType type, String diagnostic, Throwable cause) {
        return create(type, Map.of(), diagnostic, cause);
    }

    /** Creates a typed Linux failure with safe message arguments. / 创建带安全消息参数的类型化 Linux 失败。 */
    public static LinuxOperationException create(
            LinuxOperationFailureType type, Map<String, ?> arguments, String diagnostic) {
        return create(type, arguments, diagnostic, null);
    }

    /** Creates a fully described Linux failure. / 创建完整描述的 Linux 失败。 */
    public static LinuxOperationException create(
            LinuxOperationFailureType type, Map<String, ?> arguments, String diagnostic, Throwable cause) {
        return new LinuxOperationException(
                FailureDescriptor.create(type, OperationIdentity.create(), arguments, diagnostic), cause);
    }

    @Override public FailureDescriptor failure() { return failure; }
}
