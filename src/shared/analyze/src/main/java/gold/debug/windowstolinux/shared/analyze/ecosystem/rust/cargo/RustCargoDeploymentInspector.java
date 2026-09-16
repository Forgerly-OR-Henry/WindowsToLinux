package gold.debug.windowstolinux.shared.analyze.ecosystem.rust.cargo;

import gold.debug.windowstolinux.shared.analyze.service.ServiceInspectionAssembler;
import gold.debug.windowstolinux.shared.analyze.service.ServiceMetadataInspector;
import gold.debug.windowstolinux.shared.analyze.service.ServiceProjectFacts;
import gold.debug.windowstolinux.shared.analyze.contract.spi.DeploymentTypeAssessment;
import gold.debug.windowstolinux.shared.analyze.contract.spi.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** Inspects one locked Cargo service without executing Rust tools. / 在不执行 Rust 工具的情况下检查一个锁定的 Cargo 服务。 */
public final class RustCargoDeploymentInspector implements DeploymentTypeInspector {
    private static final Pattern RUST_VERSION = Pattern.compile(
            "(?:channel\\s*=\\s*[\"']|^)([A-Za-z0-9][A-Za-z0-9._+-]*)[\"']?", Pattern.MULTILINE);
    private static final Pattern CRATE_NAME = Pattern.compile(
            "(?m)^name\\s*=\\s*[\"']([A-Za-z0-9][A-Za-z0-9._-]{0,127})[\"']");

    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.RUST_SERVICE;
    }

    @Override
    public DeploymentTypeAssessment inspect(Path root, SourceInspectionFacts source, ProjectLanguageFacts languageFacts,
                                            List<RejectionReason> rejections) throws IOException {
        RustCargoFacts facts = new RustCargoFacts(
                ServiceMetadataInspector.match(ServiceMetadataInspector.readIfPresent(
                        root.resolve("rust-toolchain.toml")), RUST_VERSION),
                ServiceMetadataInspector.match(ServiceMetadataInspector.readIfPresent(root.resolve("Cargo.toml")),
                        CRATE_NAME),
                ServiceMetadataInspector.missing(root, "Cargo.toml", "Cargo.lock", "src/main.rs",
                        "rust-toolchain.toml"));
        ServiceProjectFacts shape = new ServiceProjectFacts("Cargo.toml", facts.version(), facts.crateName(),
                ServiceMetadataInspector.present(root, "src/main.rs") ? "src/main.rs" : null,
                facts.missingFiles());
        return ServiceInspectionAssembler.assemble(root, projectType(), DeploymentBuildToolType.CARGO_LOCKED,
                languageFacts, shape, false);
    }
}
