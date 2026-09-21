package gold.debug.windowstolinux.shared.analyze.ecosystem.go.gomodule;

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
 * Inspects one locked Go module service without executing Go. / 在不执行 Go 的情况下检查一个锁定的 Go Module 服务。
 */
public final class GoModuleDeploymentInspector implements DeploymentTypeInspector {
    /**
     * Pattern recognizing GO VERSION.
     * <p>用于识别GO版本的匹配模式。
     */
    private static final Pattern GO_VERSION = Pattern.compile("(?m)^go\\s+([0-9]+(?:\\.[0-9]+){1,2}(?:[-+][A-Za-z0-9._-]+)?)\\s*$");

    /**
     * Returns the supported project category handled by this strategy.
     * <p>返回当前策略处理的受支持项目类别。
     *
     * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
     */
    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.GO_SERVICE;
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
        GoModuleFacts facts = new GoModuleFacts(ServiceMetadataInspector.match(
                ServiceMetadataInspector.readIfPresent(root.resolve("go.mod")), GO_VERSION),
                ServiceMetadataInspector.missing(root, "go.mod", "go.sum", "main.go"));
        ServiceProjectFacts shape = new ServiceProjectFacts("go.mod", facts.version(), "w2l-app",
                ServiceMetadataInspector.present(root, "main.go") ? "main.go" : null, facts.missingFiles());
        return ServiceInspectionAssembler.assemble(root, projectType(), DeploymentBuildToolType.GO_MODULE,
                languageFacts, shape, false);
    }
}
