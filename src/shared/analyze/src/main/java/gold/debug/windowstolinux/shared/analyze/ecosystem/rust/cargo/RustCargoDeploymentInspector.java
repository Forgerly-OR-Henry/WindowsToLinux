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

/**
 * Inspects one locked Cargo service without executing Rust tools. / 在不执行 Rust 工具的情况下检查一个锁定的 Cargo 服务。
 */
public final class RustCargoDeploymentInspector implements DeploymentTypeInspector {
    /**
     * Pattern recognizing RUST VERSION.
     * <p>用于识别RUST版本的匹配模式。
     */
    private static final Pattern RUST_VERSION = Pattern.compile(
            "(?:channel\\s*=\\s*[\"']|^)([A-Za-z0-9][A-Za-z0-9._+-]*)[\"']?", Pattern.MULTILINE);
    /**
     * Pattern recognizing CRATE NAME.
     * <p>用于识别CRATE名称的匹配模式。
     */
    private static final Pattern CRATE_NAME = Pattern.compile(
            "(?m)^name\\s*=\\s*[\"']([A-Za-z0-9][A-Za-z0-9._-]{0,127})[\"']");

    /**
     * Returns the supported project category handled by this strategy.
     * <p>返回当前策略处理的受支持项目类别。
     *
     * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
     */
    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.RUST_SERVICE;
    }

    /**
     * Inspects deployment type assessment.
     * <p>检查部署类型评估。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param languageFacts language facts / 语言事实
     * @param rejections reasons preventing admission to the next stage / 阻止进入下一阶段的原因
     * @return constructed or resolved deployment type assessment / 构造或解析得到的部署类型评估
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
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
