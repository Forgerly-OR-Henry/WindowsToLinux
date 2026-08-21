package gold.debug.windowstolinux.app.windows.workspace;

import java.nio.file.Path;
import java.util.Objects;

/** One platform-owned parent and still-uncreated digest-bound restore candidate path. / 单个平台持有父目录及尚未创建的摘要绑定恢复候选路径。 */
public record WindowsRestoreAttempt(Path parent, Path candidateRoot, String candidateId) {
    /** Validates the exact parent/candidate relationship. / 校验精确父目录与候选关系。 */
    public WindowsRestoreAttempt {
        parent = Objects.requireNonNull(parent, "parent").toAbsolutePath().normalize();
        candidateRoot = Objects.requireNonNull(candidateRoot, "candidateRoot").toAbsolutePath().normalize();
        candidateId = Objects.requireNonNull(candidateId, "candidateId").trim();
        if (!candidateId.matches("[a-z0-9][a-z0-9-]{0,79}")
                || !candidateRoot.getParent().equals(parent)
                || !candidateRoot.getFileName().toString().equals(candidateId)) {
            throw new IllegalArgumentException("restore attempt is outside its exact candidate parent");
        }
    }
}
