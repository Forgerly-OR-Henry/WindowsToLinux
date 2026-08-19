package gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem;

import gold.debug.windowstolinux.shared.linux.sshd.build.spi.DeploymentBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.script.SafeBuildScriptEnvelope;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

/** Renders the fixed Cargo build architecture. / 渲染固定 Cargo 构建架构。 */
public final class CargoBuildRenderer implements DeploymentBuildRenderer {
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.RUST_SERVICE; }
    @Override public java.util.Set<DeploymentBuildToolType> buildTools() { return java.util.Set.of(DeploymentBuildToolType.CARGO_LOCKED); }

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
                rustc --version | grep -Eq %s
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
