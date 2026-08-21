package gold.debug.windowstolinux.app.windows.workspace;

import java.nio.file.Path;
import java.util.Objects;

/** One unforgeable same-directory temporary archive and its final destination. / 单个不可伪造的同目录临时归档及其最终目标。 */
public final class WindowsBackupArchiveAttempt {
    private final Path destination;
    private final Path temporary;
    private final Object fileKey;

    WindowsBackupArchiveAttempt(Path destination, Path temporary, Object fileKey) {
        this.destination = Objects.requireNonNull(destination, "destination").toAbsolutePath().normalize();
        this.temporary = Objects.requireNonNull(temporary, "temporary").toAbsolutePath().normalize();
        this.fileKey = fileKey;
        if (this.destination.equals(this.temporary)
                || this.destination.getParent() == null
                || !this.destination.getParent().equals(this.temporary.getParent())) {
            throw new IllegalArgumentException("backup attempt paths must be distinct siblings");
        }
    }

    /** Returns the user-selected final destination. / 返回用户选择的最终目标。 */
    public Path destination() {
        return destination;
    }

    /** Returns the private temporary file created for this attempt. / 返回为本次尝试创建的私有临时文件。 */
    public Path temporary() {
        return temporary;
    }

    Object fileKey() {
        return fileKey;
    }
}
