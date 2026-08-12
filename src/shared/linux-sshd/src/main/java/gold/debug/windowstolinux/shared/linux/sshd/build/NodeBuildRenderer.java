package gold.debug.windowstolinux.shared.linux.sshd.build;

import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

/** Renders a fixed-lockfile Node service build. / 渲染固定锁文件的 Node 服务构建。 */
public final class NodeBuildRenderer implements DeploymentBuildRenderer {
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.NODE_SERVICE; }

    @Override public String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                                   RemoteWorkspace workspace, BuildLimits limits) {
        if (!(runtime instanceof DeploymentRuntimeSpecification.NodeService node)) {
            throw new IllegalArgumentException("Node renderer requires reviewed Node service inputs");
        }
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits,
                NodePackageBuildScript.render(facts.buildTool(), node.nodeMajorVersion(), false, null));
    }
}
