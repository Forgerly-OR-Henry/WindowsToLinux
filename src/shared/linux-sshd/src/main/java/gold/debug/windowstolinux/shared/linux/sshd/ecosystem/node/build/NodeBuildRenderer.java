package gold.debug.windowstolinux.shared.linux.sshd.ecosystem.node.build;

import gold.debug.windowstolinux.shared.linux.sshd.build.shell.SafeBuildScriptEnvelope;
import gold.debug.windowstolinux.shared.linux.sshd.build.spi.DeploymentBuildRenderer;

import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

/** Renders a fixed-lockfile Node service build. / 渲染固定锁文件的 Node 服务构建。 */
public final class NodeBuildRenderer implements DeploymentBuildRenderer {
    /** Returns the supported deployment project type. / 返回支持的部署项目类型。 */
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.NODE_SERVICE; }

    /** Renders the controlled output. / 渲染受控输出。 */
    @Override public String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                                   RemoteWorkspace workspace, BuildLimits limits) {
        if (!(runtime instanceof DeploymentRuntimeSpecification.NodeService node)) {
            throw new IllegalArgumentException("Node renderer requires reviewed Node service inputs");
        }
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits,
                NodePackageBuildScript.render(facts.buildTool(), node.nodeMajorVersion(), false, null));
    }
}
