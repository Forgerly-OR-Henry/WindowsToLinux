package gold.debug.windowstolinux.shared.linux.sshd.build.spi;

import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

/** Renders one reviewed project type through a fixed, bounded target-host build entrypoint. / 通过固定且有界的目标机构建入口渲染一种经审阅项目类型。 */
public interface DeploymentBuildRenderer {
    /** Returns the only project type owned by this renderer. / 返回此渲染器唯一负责的项目类型。 */
    DeploymentProjectType projectType();

    /** Renders the complete safe build script. / 渲染完整安全构建脚本。 */
    String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                  RemoteWorkspace workspace, BuildLimits limits);
}
