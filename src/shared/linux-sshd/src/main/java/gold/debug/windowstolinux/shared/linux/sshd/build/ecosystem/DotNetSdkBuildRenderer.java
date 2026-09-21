package gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem;

import gold.debug.windowstolinux.shared.linux.sshd.build.contract.spi.DeploymentBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.generation.script.SafeBuildScriptEnvelope;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

/**
 * Renders the fixed .NET SDK build architecture. / 渲染固定 .NET SDK 构建架构。
 */
public final class DotNetSdkBuildRenderer implements DeploymentBuildRenderer {
    /**
     * Returns the supported project category handled by this strategy.
     * <p>返回当前策略处理的受支持项目类别。
     *
     * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
     */
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.DOTNET_SERVICE; }
    /**
     * Returns the supported build-tool identifiers recognized by this strategy.
     * <p>返回当前策略识别的受支持构建工具标识。
     *
     * @return the supported build-tool identifiers recognized by this strategy / 当前策略识别的受支持构建工具标识
     */
    @Override public java.util.Set<DeploymentBuildToolType> buildTools() { return java.util.Set.of(DeploymentBuildToolType.DOTNET_LOCKED); }

    /**
     * Renders dot net sdk build as text without executing the rendered command.
     * <p>渲染DotNetSdk构建为文本，不执行所渲染命令。
     *
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @return render text / 渲染文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
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
