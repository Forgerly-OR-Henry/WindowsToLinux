package gold.debug.windowstolinux.shared.analyze.ecosystem.python;

import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;

import java.util.List;

/**
 * Fixed Python project and lockfile facts.
 *
 * <p>固定的 Python 项目与锁文件事实。
 *
 * @param applicationId managed identifier / 受管标识
 * @param buildTool lockfile-selected build tool / 锁文件选择的构建工具
 * @param lockFiles present supported lockfiles / 存在的受支持锁文件
 */
public record PythonBuildFacts(String applicationId, DeploymentBuildToolType buildTool, List<String> lockFiles) {
    /** Makes lockfile evidence immutable. / 使锁文件证据不可变。 */
    public PythonBuildFacts {
        lockFiles = List.copyOf(lockFiles);
    }
}
