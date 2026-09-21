package gold.debug.windowstolinux.app.windows.workspace;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Exact private local directory for one remote backup materialization attempt. / 一次远端备份取材尝试的精确私有本地目录。
 *
 * @param directory directory within the caller's controlled storage boundary / 调用方受控存储边界内的目录
 * @param fileKey file key / 文件键
 */
public record WindowsBackupMaterialAttempt(Path directory, Object fileKey) {
    /**
     * Validates the captured attempt identity. / 校验已捕获尝试身份。
     *
     * @param directory directory within the caller's controlled storage boundary / 调用方受控存储边界内的目录
     * @param fileKey file key / 文件键
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public WindowsBackupMaterialAttempt {
        directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    }
}
