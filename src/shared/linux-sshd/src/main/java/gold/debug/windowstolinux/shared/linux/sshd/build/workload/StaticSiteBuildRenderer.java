package gold.debug.windowstolinux.shared.linux.sshd.build.workload;

import gold.debug.windowstolinux.shared.linux.sshd.build.script.SafeBuildScriptEnvelope;
import gold.debug.windowstolinux.shared.linux.sshd.build.spi.DeploymentBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.script.NodePackageBuildScript;

import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

/** Renders pure or Node-built static-site artifacts without assuming a Node version. / 在不假设 Node 版本的情况下渲染纯静态或 Node 构建型站点产物。 */
public final class StaticSiteBuildRenderer implements DeploymentBuildRenderer {
    /** Returns the supported deployment project type. / 返回支持的部署项目类型。 */
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.STATIC_SITE; }

    /** Renders the controlled output. / 渲染受控输出。 */
    @Override public String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                                   RemoteWorkspace workspace, BuildLimitConfiguration limits) {
        if (!(runtime instanceof DeploymentRuntimeSpecification.StaticSite site)) {
            throw new IllegalArgumentException("Static-site renderer requires reviewed static-site inputs");
        }
        String command;
        if (facts.buildTool() == DeploymentBuildToolType.STATIC_SITE_BUILD) {
            if (site.nodeMajorVersion().isPresent()) {
                throw new IllegalArgumentException("Pure static sites must not declare a Node version");
            }
            String output = SafeBuildScriptEnvelope.shellQuote("./" + site.outputDirectory());
            command = """
                    test -f ./index.html
                    test -d %s
                    printf 'ARTIFACT=%%s\n' %s
                    """.formatted(output, output);
        } else {
            if (site.nodeMajorVersion().isEmpty()) {
                throw new IllegalArgumentException("Node-built static sites require an explicit reviewed Node major version");
            }
            command = NodePackageBuildScript.render(facts.buildTool(), site.nodeMajorVersion().getAsInt(), true,
                    site.outputDirectory());
        }
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits, command);
    }
}
