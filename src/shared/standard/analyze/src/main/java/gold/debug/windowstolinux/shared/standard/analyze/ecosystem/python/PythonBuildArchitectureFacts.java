package gold.debug.windowstolinux.shared.standard.analyze.ecosystem.python;

import java.util.Objects;

import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;

/**
 * A selected Python dependency architecture and its lockfile. / 选中的 Python 依赖架构及其锁文件。
 *
 * @param buildTool the fixed build entrypoint / 固定构建入口
 * @param lockFile fixed lockfile name / 固定锁文件名
 */
public record PythonBuildArchitectureFacts(DeploymentBuildToolType buildTool, String lockFile) {
    /**
     * Validates one fixed architecture selection. / 验证一个固定架构选择。
     *
     * @param buildTool the fixed build entrypoint / 固定构建入口
     * @param lockFile fixed lockfile name / 固定锁文件名
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public PythonBuildArchitectureFacts {
        buildTool = Objects.requireNonNull(buildTool, "buildTool");
        lockFile = Objects.requireNonNull(lockFile, "lockFile");
    }
}
