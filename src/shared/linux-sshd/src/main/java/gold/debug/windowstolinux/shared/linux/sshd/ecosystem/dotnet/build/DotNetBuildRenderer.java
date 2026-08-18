package gold.debug.windowstolinux.shared.linux.sshd.ecosystem.dotnet.build;
import gold.debug.windowstolinux.shared.linux.sshd.build.spi.DeploymentBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.ecosystem.service.EcosystemServiceBuildSupport;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.project.*;
/** Provides the {@code DotNetBuildRenderer} type. / 提供 {@code DotNetBuildRenderer} 类型。 */
public final class DotNetBuildRenderer implements DeploymentBuildRenderer {
 private final EcosystemServiceBuildSupport support=new EcosystemServiceBuildSupport(DeploymentProjectType.DOTNET_SERVICE);
 /** Returns the supported deployment project type. / 返回支持的部署项目类型。 */
 public DeploymentProjectType projectType(){return DeploymentProjectType.DOTNET_SERVICE;}
 /** Renders the controlled output. / 渲染受控输出。 */
 public String render(DeploymentProjectFacts facts,DeploymentRuntimeSpecification runtime,RemoteWorkspace workspace,BuildLimits limits){return support.render(facts,runtime,workspace,limits);}
}
