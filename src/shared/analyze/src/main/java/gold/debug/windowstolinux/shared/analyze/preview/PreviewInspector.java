package gold.debug.windowstolinux.shared.analyze.preview;

import gold.debug.windowstolinux.shared.analyze.contract.spi.DeploymentTypeAssessment;
import gold.debug.windowstolinux.shared.analyze.contract.spi.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.analyze.source.BoundedMetadataInspector;
import gold.debug.windowstolinux.shared.analyze.source.ProjectIdentityResolver;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeAssessment;
import gold.debug.windowstolinux.shared.model.project.DeploymentSupportCatalog;
import gold.debug.windowstolinux.shared.model.project.DeploymentSupportProfile;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Produces a static recognition result that cannot enter archive preparation or deployment. / 生成无法进入归档准备或部署的静态识别结果。 */
public final class PreviewInspector implements DeploymentTypeInspector {
    /** Returns the supported deployment project type. / 返回支持的部署项目类型。 */
    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.RECOGNITION_PREVIEW;
    }

    /** Inspects source facts for this deployment type. / 检查此部署类型的源码事实。 */
    @Override
    public DeploymentTypeAssessment inspect(Path root, SourceInspectionFacts source, ProjectLanguageFacts languageFacts,
                                            List<RejectionReason> rejections) {
        SourceLanguageType language = languageFacts.sourceLanguages().size() == 1
                ? languageFacts.sourceLanguages().iterator().next() : SourceLanguageType.UNKNOWN;
        DeploymentSupportProfile support = languageFacts.sourceLanguages().isEmpty()
                ? DeploymentSupportCatalog.unrecognized() : DeploymentSupportCatalog.preview(language);
        DeploymentProjectFacts facts = new DeploymentProjectFacts(root, ProjectIdentityResolver.rootApplicationId(root),
                projectType(), DeploymentBuildToolType.NONE_PREVIEW, support, languageFacts, languageFacts.evidence(), List.of(),
                List.of(LocalizedMessage.of("analysis.preview.noDeployment")));
        return new DeploymentTypeAssessment(facts, new DeploymentRuntimeAssessment(projectType(), Map.of(), Optional.empty(),
                Map.of(), List.of(), List.of(), List.of()));
    }
}
