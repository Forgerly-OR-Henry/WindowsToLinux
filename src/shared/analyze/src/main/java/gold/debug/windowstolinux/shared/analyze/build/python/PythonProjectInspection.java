package gold.debug.windowstolinux.shared.analyze.build.python;

import java.util.List;

/**
 * Fixed Python project and lockfile facts.
 *
 * <p>固定的 Python 项目与锁文件事实。
 *
 * @param applicationId managed identifier / 受管标识
 * @param lockFiles present supported lockfiles / 存在的受支持锁文件
 */
public record PythonProjectInspection(String applicationId, List<String> lockFiles) {
    /** Makes lockfile evidence immutable. / 使锁文件证据不可变。 */
    public PythonProjectInspection {
        lockFiles = List.copyOf(lockFiles);
    }
}
