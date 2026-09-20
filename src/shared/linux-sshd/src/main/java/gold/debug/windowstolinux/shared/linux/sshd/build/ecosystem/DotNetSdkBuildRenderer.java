package gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem;

import gold.debug.windowstolinux.shared.linux.sshd.build.contract.spi.DeploymentBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.generation.script.SafeBuildScriptEnvelope;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

/** Renders the fixed .NET SDK build architecture. / 渲染固定 .NET SDK 构建架构。 */
public final class DotNetSdkBuildRenderer implements DeploymentBuildRenderer {
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.DOTNET_SERVICE; }
    @Override public java.util.Set<DeploymentBuildToolType> buildTools() { return java.util.Set.of(DeploymentBuildToolType.DOTNET_LOCKED); }

    @Override public String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                                   RemoteWorkspace workspace, BuildLimitConfiguration limits) {
        if (!(runtime instanceof DeploymentRuntimeSpecification.DotNetService service)
                || facts.buildTool() != DeploymentBuildToolType.DOTNET_LOCKED) {
            throw new IllegalArgumentException(".NET SDK renderer requires reviewed locked inputs");
        }
        String artifact = SafeBuildScriptEnvelope.shellQuote(service.artifactName());
        String version = SafeBuildScriptEnvelope.shellQuote("^" + service.version().replace(".", "[.]") + "$");
        String command = """
                artifact_name=%s
                export DOTNET_CLI_TELEMETRY_OPTOUT=1
                export DOTNET_NOLOGO=1
                export DOTNET_GCHeapHardLimit=0x40000000
                command -v dotnet >/dev/null
                dotnet_command=(dotnet)
                if [ -n "${WTL_DOTNET_VERSION:-}" ]; then
                  test -f "$DOTNET_ROOT/sdk/$WTL_DOTNET_VERSION/dotnet.dll"
                  dotnet_command=("$DOTNET_ROOT/dotnet" exec "$DOTNET_ROOT/sdk/$WTL_DOTNET_VERSION/dotnet.dll")
                  export MSBuildSDKsPath="$DOTNET_ROOT/sdk/$WTL_DOTNET_VERSION/Sdks"
                  "${dotnet_command[@]}" --version | grep -Fx "$WTL_DOTNET_VERSION"
                else dotnet --version | grep -Eq %s; fi
                test -f ./global.json
                test -f ./packages.lock.json
                run "${dotnet_command[@]}" restore --locked-mode
                mkdir -p ./.w2l/dotnet
                run "${dotnet_command[@]}" publish --no-restore --configuration Release "-p:PublishDir=$PWD/.w2l/dotnet/"
                test -f "./.w2l/dotnet/$artifact_name.dll"
                test ! -L "./.w2l/dotnet/$artifact_name.dll"
                printf 'ARTIFACT=%%s\n' "./.w2l/dotnet/$artifact_name.dll"
                """.formatted(artifact, version);
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits, command);
    }
}
