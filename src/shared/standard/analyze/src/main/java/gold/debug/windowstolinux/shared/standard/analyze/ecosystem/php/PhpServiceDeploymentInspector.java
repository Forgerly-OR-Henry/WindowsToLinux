package gold.debug.windowstolinux.shared.standard.analyze.ecosystem.php;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.standard.analyze.contract.spi.DeploymentTypeAssessment;
import gold.debug.windowstolinux.shared.standard.analyze.contract.spi.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.standard.analyze.ecosystem.php.composer.PhpComposerDeploymentInspector;
import gold.debug.windowstolinux.shared.standard.analyze.ecosystem.php.phpcli.PhpCliDeploymentInspector;
import gold.debug.windowstolinux.shared.standard.analyze.service.ServiceMetadataInspector;
import gold.debug.windowstolinux.shared.standard.analyze.source.SourceInspectionFacts;

/**
 * Selects exactly one reviewed PHP architecture. / 恰好选择一个经审阅的 PHP 架构。
 */
public final class PhpServiceDeploymentInspector implements DeploymentTypeInspector {
    /**
     * Bound php composer deployment inspector collaborator for composer.
     * <p>处理Composer 构建的PHPComposer部署检查器协作对象。
     */
    private final PhpComposerDeploymentInspector composer = new PhpComposerDeploymentInspector();

    /**
     * Bound php cli deployment inspector collaborator for php cli.
     * <p>处理PHPCli 对应的输入或状态的PHPCli部署检查器协作对象。
     */
    private final PhpCliDeploymentInspector phpCli = new PhpCliDeploymentInspector();

    /**
     * Returns the PHP service type. / 返回 PHP 服务类型。
     *
     * @return the PHP service type /  PHP 服务类型
     */
    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.PHP_SERVICE;
    }

    /**
     * Rejects Composer/PHP CLI conflicts and delegates to one architecture. / 拒绝 Composer/PHP CLI 冲突并委派给一个架构。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param languageFacts language facts / 语言事实
     * @param rejections reasons preventing admission to the next stage / 阻止进入下一阶段的原因
     * @return constructed or resolved deployment type assessment; null when no matching value is available / 构造或解析得到的部署类型评估；没有匹配值时为 null
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
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
        return nativeMetadata
                ? phpCli.inspect(root, source, languageFacts, rejections)
                : composer.inspect(root, source, languageFacts, rejections);
    }
}
