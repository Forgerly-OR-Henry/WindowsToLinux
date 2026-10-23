package gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.python;

import java.util.Set;

import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentBuildLimits;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.contract.DeploymentBuildWriter;

/**
 * Renders the native fully-hashed pip architecture. / 渲染原生完整哈希 pip 架构。
 */
public final class PipBuildWriter implements DeploymentBuildWriter {
    /**
     * Returns the supported project category handled by this strategy.
     * <p>返回当前策略处理的受支持项目类别。
     *
     * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
     */
    @Override
    public DeploymentProjectKind projectType() {
        return DeploymentProjectKind.PYTHON_SERVICE;
    }

    /**
     * Returns the supported build-tool identifiers recognized by this strategy.
     * <p>返回当前策略识别的受支持构建工具标识。
     *
     * @return the supported build-tool identifiers recognized by this strategy / 当前策略识别的受支持构建工具标识
     */
    @Override
    public Set<DeploymentBuildToolType> buildTools() {
        return Set.of(DeploymentBuildToolType.PIP_LOCKED, DeploymentBuildToolType.PYTHON_STDLIB);
    }

    /**
     * Renders pip build as text without executing the rendered command.
     * <p>渲染Pip构建为文本，不执行所渲染命令。
     *
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @return render text / 渲染文本
     */
    @Override
    public String render(DeploymentProjectFacts facts, DeploymentRuntimeDefinition runtime,
                         DeploymentRemoteWorkspace workspace, DeploymentBuildLimits limits) {
        if (facts.buildTool() == DeploymentBuildToolType.PYTHON_STDLIB)
            return PythonArchitectureBuildWriter.render(DeploymentBuildToolType.PYTHON_STDLIB, facts, runtime,
                workspace, limits, null, "test -f ./pyproject.toml");
        return PythonArchitectureBuildWriter.render(DeploymentBuildToolType.PIP_LOCKED, facts, runtime, workspace,
            limits, null,
            "test -f ./requirements.lock\nrun ./.venv/bin/python -m pip install --disable-pip-version-check --require-hashes -r ./requirements.lock");
    }
}
