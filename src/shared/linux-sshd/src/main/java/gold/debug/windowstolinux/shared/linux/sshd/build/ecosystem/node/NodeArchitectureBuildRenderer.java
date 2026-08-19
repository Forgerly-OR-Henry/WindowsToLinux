package gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.node;

import gold.debug.windowstolinux.shared.linux.sshd.build.script.NodePackageBuildScript;
import gold.debug.windowstolinux.shared.linux.sshd.build.script.SafeBuildScriptEnvelope;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

/** Shares only the invariant Node service envelope across named architecture renderers. / 仅在具名架构 Renderer 间共享不变的 Node 服务外壳。 */
final class NodeArchitectureBuildRenderer {
    private NodeArchitectureBuildRenderer() { }

    static String render(DeploymentBuildToolType expected, DeploymentProjectFacts facts,
                         DeploymentRuntimeSpecification runtime, RemoteWorkspace workspace,
                         BuildLimitConfiguration limits) {
        if (!(runtime instanceof DeploymentRuntimeSpecification.NodeService node) || facts.buildTool() != expected) {
            throw new IllegalArgumentException("Node architecture renderer requires matching reviewed inputs");
        }
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits,
                NodePackageBuildScript.render(expected, node.nodeMajorVersion(), false, null));
    }
}
