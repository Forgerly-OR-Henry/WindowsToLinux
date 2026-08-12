package gold.debug.windowstolinux.shared.linux.sshd.build;

import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildTool;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.util.Locale;

/** Renders the fixed Dockerfile container image build. / 渲染固定 Dockerfile 容器镜像构建。 */
public final class ContainerBuildRenderer implements DeploymentBuildRenderer {
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.DOCKERFILE_CONTAINER; }

    @Override public String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                                   RemoteWorkspace workspace, BuildLimits limits) {
        if (!(runtime instanceof DeploymentRuntimeSpecification.Container container)
                || facts.buildTool() != DeploymentBuildTool.CONTAINER_BUILD) {
            throw new IllegalArgumentException("Container renderer requires reviewed Dockerfile inputs");
        }
        String engine = SafeBuildScriptEnvelope.shellQuote(container.engine().name().toLowerCase(Locale.ROOT));
        String tag = SafeBuildScriptEnvelope.shellQuote("windowstolinux-candidate:" + workspace.candidateId());
        String command = """
                command -v %s >/dev/null
                test -f ./Dockerfile
                run %s build --pull=true --tag %s --file ./Dockerfile .
                image_id=$(%s image inspect --format '{{.Id}}' %s)
                printf 'ARTIFACT=%%s\n' "$image_id"
                """.formatted(engine, engine, tag, engine, tag);
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits, command);
    }
}
