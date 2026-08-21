package gold.debug.windowstolinux.app.windows.workspace;

import java.nio.file.Path;
import java.util.Objects;

/** One unforgeable platform-owned parent and digest-bound restore candidate path. / 单个不可伪造的平台持有父目录及摘要绑定恢复候选路径。 */
public final class WindowsRestoreAttempt {
    private final Path parent;
    private final Path candidateRoot;
    private final String candidateId;
    private final Object parentFileKey;

    WindowsRestoreAttempt(Path parent, Path candidateRoot, String candidateId) {
        this(parent, candidateRoot, candidateId, null);
    }

    WindowsRestoreAttempt(Path parent, Path candidateRoot, String candidateId, Object parentFileKey) {
        this.parent = Objects.requireNonNull(parent, "parent").toAbsolutePath().normalize();
        this.candidateRoot = Objects.requireNonNull(candidateRoot, "candidateRoot").toAbsolutePath().normalize();
        this.candidateId = Objects.requireNonNull(candidateId, "candidateId").trim();
        this.parentFileKey = parentFileKey;
        if (!this.candidateId.matches("[a-z0-9][a-z0-9-]{0,79}")
                || !this.candidateRoot.getParent().equals(this.parent)
                || !this.candidateRoot.getFileName().toString().equals(this.candidateId)) {
            throw new IllegalArgumentException("restore attempt is outside its exact candidate parent");
        }
    }

    /** Returns the private parent created for this attempt. / 返回为本次尝试创建的私有父目录。 */
    public Path parent() {
        return parent;
    }

    /** Returns the digest-bound candidate root. / 返回摘要绑定的候选根。 */
    public Path candidateRoot() {
        return candidateRoot;
    }

    /** Returns the digest-bound candidate identity. / 返回摘要绑定的候选身份。 */
    public String candidateId() {
        return candidateId;
    }

    Object parentFileKey() {
        return parentFileKey;
    }
}
