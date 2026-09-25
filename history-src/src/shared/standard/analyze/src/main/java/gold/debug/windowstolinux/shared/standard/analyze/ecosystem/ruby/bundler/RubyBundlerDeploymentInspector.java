package gold.debug.windowstolinux.shared.standard.analyze.ecosystem.ruby.bundler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.standard.analyze.contract.spi.DeploymentTypeAssessment;
import gold.debug.windowstolinux.shared.standard.analyze.contract.spi.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.standard.analyze.service.ServiceInspectionAssembler;
import gold.debug.windowstolinux.shared.standard.analyze.service.ServiceMetadataInspector;
import gold.debug.windowstolinux.shared.standard.analyze.service.ServiceProjectFacts;
import gold.debug.windowstolinux.shared.standard.analyze.source.SourceInspectionFacts;

/**
 * Inspects one Bundler-locked Rack service without executing Ruby. / 在不执行 Ruby 的情况下检查一个 Bundler 锁定的 Rack 服务。
 */
public final class RubyBundlerDeploymentInspector implements DeploymentTypeInspector {
    /**
     * Returns the supported project category handled by this strategy.
     * <p>返回当前策略处理的受支持项目类别。
     *
     * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
     */
    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.RUBY_SERVICE;
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
        String version = ServiceMetadataInspector.readIfPresent(root.resolve(".ruby-version")).trim();
        if (!version.matches("[0-9]+(?:\\.[0-9]+){1,2}(?:[-+][A-Za-z0-9._-]+)?")) {
            version = null;
        }
        String entrypoint = ServiceMetadataInspector.applicationEntrypoint(root, "config.ru");
        RubyBundlerFacts facts = new RubyBundlerFacts(version,
                ServiceMetadataInspector.missing(root, "Gemfile", "Gemfile.lock", ".ruby-version", entrypoint));
        ServiceProjectFacts shape = new ServiceProjectFacts(".ruby-version", facts.version(), "bundle",
                ServiceMetadataInspector.present(root, entrypoint) ? entrypoint : null, facts.missingFiles());
        return ServiceInspectionAssembler.assemble(root, projectType(), DeploymentBuildToolType.BUNDLER_LOCKED,
                languageFacts, shape, true);
    }
}
