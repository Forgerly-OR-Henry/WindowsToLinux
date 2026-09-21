package gold.debug.windowstolinux.app.windows.workspace;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One unforgeable same-directory temporary archive and its final destination. / 单个不可伪造的同目录临时归档及其最终目标。
 */
public final class WindowsBackupArchiveAttempt {
    /**
     * Caller-selected destination inside the permitted boundary.
     * <p>调用方选择的许可边界内目的地。
     */
    private final Path destination;
    /**
     * Temporary.
     * <p>临时。
     */
    private final Path temporary;
    /**
     * File key.
     * <p>文件键。
     */
    private final Object fileKey;

    /**
     * Validates and binds the inputs required by windows backup archive attempt.
     * <p>校验并绑定Windows备份归档尝试所需输入。
     *
     * @param destination caller-selected destination inside the permitted boundary / 调用方选择的许可边界内目的地
     * @param temporary temporary / 临时
     * @param fileKey file key / 文件键
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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

    /**
     * Returns the user-selected final destination. / 返回用户选择的最终目标。
     *
     * @return the user-selected final destination / 用户选择的最终目标
     */
    public Path destination() {
        return destination;
    }

    /**
     * Returns the private temporary file created for this attempt. / 返回为本次尝试创建的私有临时文件。
     *
     * @return the private temporary file created for this attempt / 为本次尝试创建的私有临时文件
     */
    public Path temporary() {
        return temporary;
    }

    /**
     * Returns file key.
     * <p>返回文件键。
     *
     * @return file key / 文件键
     */
    Object fileKey() {
        return fileKey;
    }
}
