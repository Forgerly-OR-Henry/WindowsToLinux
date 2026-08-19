package gold.debug.windowstolinux.shared.analyze.ecosystem.python;

import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;

import java.util.Objects;

/** A selected Python dependency architecture and its lockfile. / 选中的 Python 依赖架构及其锁文件。 */
public record PythonBuildArchitectureFacts(DeploymentBuildToolType buildTool, String lockFile) {
    /** Validates one fixed architecture selection. / 验证一个固定架构选择。 */
    public PythonBuildArchitectureFacts {
        buildTool = Objects.requireNonNull(buildTool, "buildTool");
        lockFile = Objects.requireNonNull(lockFile, "lockFile");
    }
}
