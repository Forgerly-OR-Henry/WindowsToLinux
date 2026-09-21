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

/**
 * Inspects one Composer-locked PHP service without executing PHP. / 在不执行 PHP 的情况下检查一个 Composer 锁定的 PHP 服务。
 */
public final class PhpComposerDeploymentInspector implements DeploymentTypeInspector {
    /**
     * Pattern recognizing PHP VERSION.
     * <p>用于识别PHP版本的匹配模式。
     */
    private static final Pattern PHP_VERSION = Pattern.compile(
            "(?s)[\"']platform[\"']\\s*:\\s*\\{[^}]*[\"']php[\"']\\s*:\\s*[\"']((?:8)\\.(?:2|3|4))[\"']");

    /**
     * Returns the supported project category handled by this strategy.
     * <p>返回当前策略处理的受支持项目类别。
     *
     * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
     */
    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.PHP_SERVICE;
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
