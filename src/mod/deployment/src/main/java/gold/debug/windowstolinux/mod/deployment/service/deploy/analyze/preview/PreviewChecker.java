package gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.preview;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import gold.debug.windowstolinux.mod.deployment.service.core.model.analysis.RejectionReason;
import gold.debug.windowstolinux.mod.deployment.service.core.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.language.SourceLanguageType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.message.DeploymentLocalizedMessage;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentRuntimeReport;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentSupportCatalog;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentSupportProfile;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.contract.DeploymentTypeChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.contract.DeploymentTypeReport;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.source.ProjectIdentityResolver;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.source.SourceCheckFacts;

/**
 * Produces a static recognition result that cannot enter archive preparation or deployment. / 生成无法进入归档准备或部署的静态识别结果。
 */
public final class PreviewChecker implements DeploymentTypeChecker {
    /**
     * Returns the supported deployment project type. / 返回支持的部署项目类型。
     *
     * @return the supported deployment project type / 支持的部署项目类型
     */
    @Override
    public DeploymentProjectKind projectType() {
        return DeploymentProjectKind.RECOGNITION_PREVIEW;
    }

    /**
     * Inspects source facts for this deployment type. / 检查此部署类型的源码事实。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param languageFacts language facts / 语言事实
     * @param rejections reasons preventing admission to the next stage / 阻止进入下一阶段的原因
     * @return constructed or resolved deployment type assessment / 构造或解析得到的部署类型评估
     */
    @Override
    public DeploymentTypeReport inspect(Path root, SourceCheckFacts source, ProjectLanguageFacts languageFacts,
                                        List<RejectionReason> rejections) {
        SourceLanguageType language = languageFacts.sourceLanguages().size() == 1
            ? languageFacts.sourceLanguages().iterator().next()
            : SourceLanguageType.UNKNOWN;
        DeploymentSupportProfile support = languageFacts.sourceLanguages().isEmpty()
            ? DeploymentSupportCatalog.unrecognized()
            : DeploymentSupportCatalog.preview(language);
        DeploymentProjectFacts facts = new DeploymentProjectFacts(root,
            ProjectIdentityResolver.rootApplicationId(source.reviewedRootName()), projectType(),
            DeploymentBuildToolType.NONE_PREVIEW, support, languageFacts, languageFacts.evidence(), List.of(),
            List.of(DeploymentLocalizedMessage.of("analysis.preview.noDeployment")));
        return new DeploymentTypeReport(facts, new DeploymentRuntimeReport(projectType(), Map.of(),
            Optional.empty(), Map.of(), List.of(), List.of(), List.of()));
    }
}
