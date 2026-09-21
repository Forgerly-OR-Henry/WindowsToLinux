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
 * Renders the fixed Go Module build architecture. / 渲染固定 Go Module 构建架构。
 */
public final class GoBuildRenderer implements DeploymentBuildRenderer {
    /**
     * Returns the supported project category handled by this strategy.
     * <p>返回当前策略处理的受支持项目类别。
     *
     * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
     */
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.GO_SERVICE; }
    /**
     * Returns the supported build-tool identifiers recognized by this strategy.
     * <p>返回当前策略识别的受支持构建工具标识。
     *
     * @return the supported build-tool identifiers recognized by this strategy / 当前策略识别的受支持构建工具标识
     */
    @Override public java.util.Set<DeploymentBuildToolType> buildTools() { return java.util.Set.of(DeploymentBuildToolType.GO_MODULE); }

    /**
     * Renders go build as text without executing the rendered command.
     * <p>渲染Go构建为文本，不执行所渲染命令。
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
