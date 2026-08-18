package gold.debug.windowstolinux.shared.analyze.ecosystem.node;

import gold.debug.windowstolinux.shared.model.project.DeploymentBuildTool;

import java.util.List;

/**
 * Fixed package-manager, lockfile, and script facts from package.json.
 *
 * <p>来自 package.json 的固定包管理器、锁文件和脚本事实。
 *
 * @param applicationId managed identifier / 受管标识
 * @param buildTool lockfile-selected build tool / 锁文件选择的构建工具
 * @param lockFiles present supported lockfiles / 存在的受支持锁文件
 * @param hasBuildScript whether a fixed build script exists / 是否存在固定构建脚本
 * @param hasStartScript whether a fixed start script exists / 是否存在固定启动脚本
 */
public record NodeBuildFacts(String applicationId, DeploymentBuildTool buildTool, List<String> lockFiles,
                                    boolean hasBuildScript, boolean hasStartScript) {
    /** Makes lockfile evidence immutable. / 使锁文件证据不可变。 */
    public NodeBuildFacts {
        lockFiles = List.copyOf(lockFiles);
    }
}
