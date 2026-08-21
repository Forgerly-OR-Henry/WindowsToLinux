package gold.debug.windowstolinux.shared.git;

import gold.debug.windowstolinux.shared.model.failure.FailureCarrier;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.Objects;

/** A structured Git snapshot failure that never exposes remote output or credentials. / 不暴露远端输出或凭据的结构化 Git 快照失败。 */
public final class GitSnapshotException extends Exception implements FailureCarrier {
    private final FailureDescriptor failure;

    /** Creates one Git snapshot failure occurrence. / 创建一次 Git 快照失败。 */
    public GitSnapshotException(FailureDescriptor failure, Throwable cause) {
        super(Objects.requireNonNull(failure, "failure").diagnostic(), cause);
        this.failure = failure;
    }

    /** Creates a typed Git snapshot failure. / 创建类型化 Git 快照失败。 */
    public static GitSnapshotException create(GitSnapshotFailureType type, String diagnostic) {
        return create(type, diagnostic, null);
    }

    /** Creates a typed Git snapshot failure with its original cause. / 创建带原始原因的类型化 Git 快照失败。 */
    public static GitSnapshotException create(GitSnapshotFailureType type, String diagnostic, Throwable cause) {
        return new GitSnapshotException(
                FailureDescriptor.create(type, OperationIdentity.create(), diagnostic), cause);
    }

    @Override public FailureDescriptor failure() { return failure; }
}
