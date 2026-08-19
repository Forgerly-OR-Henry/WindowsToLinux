package gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.python;

import gold.debug.windowstolinux.shared.linux.sshd.build.spi.DeploymentBuildRenderer;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.util.Set;

/** Renders the Poetry locked architecture. / 渲染 Poetry 锁定架构。 */
public final class PoetryBuildRenderer implements DeploymentBuildRenderer {
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.PYTHON_SERVICE; }
    @Override public Set<DeploymentBuildToolType> buildTools() { return Set.of(DeploymentBuildToolType.POETRY_LOCKED); }
    @Override public String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                                   RemoteWorkspace workspace, BuildLimitConfiguration limits) {
        return PythonArchitectureBuildRenderer.render(DeploymentBuildToolType.POETRY_LOCKED, facts, runtime, workspace,
                limits, "poetry", "test -f ./poetry.lock\nrun poetry check --lock\n"
                        + "run poetry sync --only main --no-root --no-interaction");
    }
}
