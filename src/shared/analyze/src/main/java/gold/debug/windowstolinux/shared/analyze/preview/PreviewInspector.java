package gold.debug.windowstolinux.shared.analyze.preview;

import gold.debug.windowstolinux.shared.analyze.spi.DeploymentTypeInspection;
import gold.debug.windowstolinux.shared.analyze.spi.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.analyze.source.metadata.BoundedMetadataReader;
import gold.debug.windowstolinux.shared.analyze.source.metadata.ProjectIdentityResolver;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspection;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildTool;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSuggestion;
import gold.debug.windowstolinux.shared.model.project.DeploymentSupportCatalog;
import gold.debug.windowstolinux.shared.model.project.DeploymentSupportProfile;
import gold.debug.windowstolinux.shared.model.project.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.project.SourceLanguage;

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
    public DeploymentTypeInspection inspect(Path root, SourceInspection source, ProjectLanguageFacts languageFacts,
                                            List<RejectionReason> rejections) {
        SourceLanguage language = languageFacts.sourceLanguages().size() == 1
                ? languageFacts.sourceLanguages().iterator().next() : SourceLanguage.UNKNOWN;
        DeploymentSupportProfile support = languageFacts.sourceLanguages().isEmpty()
                ? DeploymentSupportCatalog.unrecognized() : DeploymentSupportCatalog.preview(language);
        DeploymentProjectFacts facts = new DeploymentProjectFacts(root, ProjectIdentityResolver.rootApplicationId(root),
                projectType(), DeploymentBuildTool.NONE_PREVIEW, support, languageFacts, languageFacts.evidence(), List.of(),
                List.of(LocalizedMessage.of("analysis.preview.noDeployment")));
        return new DeploymentTypeInspection(facts, new DeploymentRuntimeSuggestion(projectType(), Map.of(), Optional.empty(),
                Map.of(), List.of(), List.of(), List.of()));
    }
}
