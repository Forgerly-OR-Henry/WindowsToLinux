package gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem;

import gold.debug.windowstolinux.shared.linux.sshd.build.contract.spi.DeploymentBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.generation.script.SafeBuildScriptEnvelope;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

/** Renders the fixed Go Module build architecture. / 渲染固定 Go Module 构建架构。 */
public final class GoBuildRenderer implements DeploymentBuildRenderer {
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.GO_SERVICE; }
    @Override public java.util.Set<DeploymentBuildToolType> buildTools() { return java.util.Set.of(DeploymentBuildToolType.GO_MODULE); }

    @Override public String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                                   RemoteWorkspace workspace, BuildLimitConfiguration limits) {
        if (!(runtime instanceof DeploymentRuntimeSpecification.GoService service)
                || facts.buildTool() != DeploymentBuildToolType.GO_MODULE) {
            throw new IllegalArgumentException("Go renderer requires reviewed Go Module inputs");
        }
        String artifact = SafeBuildScriptEnvelope.shellQuote(service.artifactName());
        String version = SafeBuildScriptEnvelope.shellQuote("^go version go"
                + service.version().replace(".", "[.]") + "([.][0-9]+)? ");
        String command = """
                artifact_name=%s
                command -v go >/dev/null
                if [ -n "${WTL_GO_VERSION:-}" ]; then
                  go version | grep -F "go version go${WTL_GO_VERSION} "
                else go version | grep -Eq %s; fi
                test -f ./go.mod
                test -f ./go.sum
                test -f ./main.go
                mkdir -p ./.w2l/bin
                CGO_ENABLED=0 run go build -mod=readonly -trimpath -o "./.w2l/bin/$artifact_name" .
                test -x "./.w2l/bin/$artifact_name"
                test ! -L "./.w2l/bin/$artifact_name"
                printf 'ARTIFACT=%%s\n' "./.w2l/bin/$artifact_name"
                """.formatted(artifact, version);
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits, command);
    }
}
