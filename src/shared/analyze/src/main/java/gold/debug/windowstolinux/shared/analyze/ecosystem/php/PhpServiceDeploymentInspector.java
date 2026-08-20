package gold.debug.windowstolinux.shared.analyze.ecosystem.php;

import gold.debug.windowstolinux.shared.analyze.ecosystem.php.composer.PhpComposerDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.php.phpcli.PhpCliDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.service.ServiceMetadataInspector;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.shared.analyze.contract.spi.DeploymentTypeAssessment;
import gold.debug.windowstolinux.shared.analyze.contract.spi.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Selects exactly one reviewed PHP architecture. / 恰好选择一个经审阅的 PHP 架构。 */
public final class PhpServiceDeploymentInspector implements DeploymentTypeInspector {
    private final PhpComposerDeploymentInspector composer = new PhpComposerDeploymentInspector();
    private final PhpCliDeploymentInspector phpCli = new PhpCliDeploymentInspector();

    /** Returns the PHP service type. / 返回 PHP 服务类型。 */
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.PHP_SERVICE; }

    /** Rejects Composer/PHP CLI conflicts and delegates to one architecture. / 拒绝 Composer/PHP CLI 冲突并委派给一个架构。 */
    @Override
    public DeploymentTypeAssessment inspect(Path root, SourceInspectionFacts source, ProjectLanguageFacts languageFacts,
                                            List<RejectionReason> rejections) throws IOException {
        boolean nativeMetadata = ServiceMetadataInspector.present(root, "windowstolinux-php.properties");
        boolean composerMetadata = ServiceMetadataInspector.present(root, "composer.json")
                || ServiceMetadataInspector.present(root, "composer.lock");
        if (nativeMetadata && composerMetadata) {
            rejections.add(new RejectionReason("PHP_ARCHITECTURE_CONFLICT",
                    LocalizedMessage.of("analysis.php.architectureConflict"), "deployment"));
            return null;
        }
        return nativeMetadata ? phpCli.inspect(root, source, languageFacts, rejections)
                : composer.inspect(root, source, languageFacts, rejections);
    }
}
