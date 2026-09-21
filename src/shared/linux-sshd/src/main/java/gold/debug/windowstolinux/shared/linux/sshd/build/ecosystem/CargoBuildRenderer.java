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
 * Renders the fixed Cargo build architecture. / 渲染固定 Cargo 构建架构。
 */
public final class CargoBuildRenderer implements DeploymentBuildRenderer {
    /**
     * Returns the supported project category handled by this strategy.
     * <p>返回当前策略处理的受支持项目类别。
     *
     * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
     */
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.RUST_SERVICE; }
    /**
     * Returns the supported build-tool identifiers recognized by this strategy.
     * <p>返回当前策略识别的受支持构建工具标识。
     *
     * @return the supported build-tool identifiers recognized by this strategy / 当前策略识别的受支持构建工具标识
     */
    @Override public java.util.Set<DeploymentBuildToolType> buildTools() { return java.util.Set.of(DeploymentBuildToolType.CARGO_LOCKED); }

    /**
     * Renders cargo build as text without executing the rendered command.
     * <p>渲染Cargo构建为文本，不执行所渲染命令。
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
        if (!(runtime instanceof DeploymentRuntimeSpecification.RustService service)
                || facts.buildTool() != DeploymentBuildToolType.CARGO_LOCKED) {
            throw new IllegalArgumentException("Cargo renderer requires reviewed Rust inputs");
        }
        String artifact = SafeBuildScriptEnvelope.shellQuote(service.artifactName());
        String version = SafeBuildScriptEnvelope.shellQuote("^rustc " + service.version().replace(".", "[.]") + " ");
        String command = """
                artifact_name=%s
                command -v rustc >/dev/null
                command -v cargo >/dev/null
                if [ -n "${WTL_RUST_VERSION:-}" ]; then
                  rustc --version | grep -F "rustc ${WTL_RUST_VERSION} "
                else rustc --version | grep -Eq %s; fi
                test -f ./Cargo.toml
                test -f ./Cargo.lock
                test -f ./rust-toolchain.toml
                run cargo build --locked --release
                mkdir -p ./.w2l/bin
                test -x "./target/release/$artifact_name"
                cp -- "./target/release/$artifact_name" "./.w2l/bin/$artifact_name"
                test -x "./.w2l/bin/$artifact_name"
                test ! -L "./.w2l/bin/$artifact_name"
                printf 'ARTIFACT=%%s\n' "./.w2l/bin/$artifact_name"
                """.formatted(artifact, version);
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits, command);
    }
}
