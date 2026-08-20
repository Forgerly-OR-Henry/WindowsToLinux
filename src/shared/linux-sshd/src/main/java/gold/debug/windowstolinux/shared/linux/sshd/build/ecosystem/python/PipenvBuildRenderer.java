package gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.python;

import gold.debug.windowstolinux.shared.linux.sshd.build.contract.spi.DeploymentBuildRenderer;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.util.Set;

/** Renders the Pipenv locked architecture. / 渲染 Pipenv 锁定架构。 */
public final class PipenvBuildRenderer implements DeploymentBuildRenderer {
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.PYTHON_SERVICE; }
    @Override public Set<DeploymentBuildToolType> buildTools() { return Set.of(DeploymentBuildToolType.PIPENV_LOCKED); }
    @Override public String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                                   RemoteWorkspace workspace, BuildLimitConfiguration limits) {
        return PythonArchitectureBuildRenderer.render(DeploymentBuildToolType.PIPENV_LOCKED, facts, runtime, workspace,
                limits, "pipenv", "test -f ./Pipfile\ntest -f ./Pipfile.lock\n"
                        + "run pipenv verify\nexport PIPENV_IGNORE_VIRTUALENVS=0\nrun pipenv sync");
    }
}
