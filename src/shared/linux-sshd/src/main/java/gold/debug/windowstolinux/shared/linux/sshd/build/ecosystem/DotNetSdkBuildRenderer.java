package gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem;

import gold.debug.windowstolinux.shared.linux.sshd.build.spi.DeploymentBuildRenderer;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

/** Renders the fixed .NET SDK build architecture. / 渲染固定 .NET SDK 构建架构。 */
public final class DotNetSdkBuildRenderer implements DeploymentBuildRenderer {
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.DOTNET_SERVICE; }

    @Override public String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                                   RemoteWorkspace workspace, BuildLimitConfiguration limits) {
        return EcosystemBuildScript.render(projectType(), facts, runtime, workspace, limits);
    }
}
