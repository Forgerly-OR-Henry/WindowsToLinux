package gold.debug.windowstolinux.shared.analyze.ecosystem.php.composer;

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

/** Inspects one Composer-locked PHP service without executing PHP. / 在不执行 PHP 的情况下检查一个 Composer 锁定的 PHP 服务。 */
public final class PhpComposerDeploymentInspector implements DeploymentTypeInspector {
    private static final Pattern PHP_VERSION = Pattern.compile(
            "(?s)[\"']platform[\"']\\s*:\\s*\\{[^}]*[\"']php[\"']\\s*:\\s*[\"']((?:8)\\.(?:2|3|4))[\"']");

    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.PHP_SERVICE;
    }

    @Override
    public DeploymentTypeAssessment inspect(Path root, SourceInspectionFacts source, ProjectLanguageFacts languageFacts,
                                            List<RejectionReason> rejections) throws IOException {
        String entrypoint = ServiceMetadataInspector.applicationEntrypoint(root, "public/index.php");
        var declaration = gold.debug.windowstolinux.shared.analyze.source.ApplicationBundleInspector.declaration(root);
        String declaredVersion = declaration.getProperty("runtime.version", ServiceMetadataInspector.match(
                ServiceMetadataInspector.readIfPresent(root.resolve("composer.json")), PHP_VERSION));
        PhpComposerFacts facts = new PhpComposerFacts(declaredVersion,
                ServiceMetadataInspector.missing(root, "composer.json", "composer.lock", entrypoint));
        ServiceProjectFacts shape = new ServiceProjectFacts("composer.json", facts.version(),
                java.nio.file.Files.isDirectory(root.resolve("public")) ? "public" : "source",
                ServiceMetadataInspector.present(root, entrypoint) ? entrypoint : null,
                facts.missingFiles());
        return ServiceInspectionAssembler.assemble(root, projectType(), DeploymentBuildToolType.COMPOSER_LOCKED,
                languageFacts, shape, true);
    }
}
