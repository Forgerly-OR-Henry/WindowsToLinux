package gold.debug.windowstolinux.shared.source.archive;

import gold.debug.windowstolinux.shared.model.failure.FailureCarrier;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.io.IOException;
import java.util.Objects;

/** Structured checked failure for source archive preparation. / 源码归档准备的结构化受检失败。 */
public final class SourceArchiveException extends IOException implements FailureCarrier {
    private final FailureDescriptor failure;

    /** Creates one source archive failure. / 创建一次源码归档失败。 */
    public SourceArchiveException(FailureDescriptor failure, Throwable cause) {
        super(Objects.requireNonNull(failure, "failure").diagnostic(), cause);
        this.failure = failure;
    }

    /** Creates a typed source archive failure. / 创建类型化源码归档失败。 */
    public static SourceArchiveException create(
            SourceArchiveFailureType type, String diagnostic, Throwable cause) {
        return new SourceArchiveException(
                FailureDescriptor.create(type, OperationIdentity.create(), diagnostic), cause);
    }

    @Override public FailureDescriptor failure() { return failure; }
}
