package gold.debug.windowstolinux.shared.analyze.ecosystem.node;

import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;

import java.util.Objects;

/**
 * A selected Node package-manager architecture and its lockfile.
 *
 * <p>选中的 Node 包管理器架构及其锁文件。
 *
 * @param buildTool fixed build tool / 固定构建工具
 * @param lockFile fixed lockfile name / 固定锁文件名
 */
public record NodeBuildArchitectureFacts(DeploymentBuildToolType buildTool, String lockFile) {
    /** Validates the fixed architecture facts. / 验证固定架构事实。 */
    public NodeBuildArchitectureFacts {
        buildTool = Objects.requireNonNull(buildTool, "buildTool");
        lockFile = Objects.requireNonNull(lockFile, "lockFile");
    }
}
