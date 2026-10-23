package gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.ruby;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import gold.debug.windowstolinux.mod.deployment.service.core.model.analysis.RejectionReason;
import gold.debug.windowstolinux.mod.deployment.service.core.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.message.DeploymentLocalizedMessage;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.contract.DeploymentTypeChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.contract.DeploymentTypeReport;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.ruby.bundler.RubyBundlerDeploymentChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.ruby.rubycli.RubyCliDeploymentChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.service.ServiceMetadataChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.source.SourceCheckFacts;

/**
 * Selects exactly one reviewed Ruby architecture. / 恰好选择一个经审阅的 Ruby 架构。
 */
public final class RubyServiceDeploymentChecker implements DeploymentTypeChecker {
    /**
     * Bound ruby bundler deployment inspector collaborator for bundler.
     * <p>处理Bundler 构建的RubyBundler部署检查器协作对象。
     */
    private final RubyBundlerDeploymentChecker bundler = new RubyBundlerDeploymentChecker();

    /**
     * Bound ruby cli deployment inspector collaborator for ruby cli.
     * <p>处理RubyCli 对应的输入或状态的RubyCli部署检查器协作对象。
     */
    private final RubyCliDeploymentChecker rubyCli = new RubyCliDeploymentChecker();

    /**
     * Returns the Ruby service type. / 返回 Ruby 服务类型。
     *
     * @return the Ruby service type /  Ruby 服务类型
     */
    @Override
    public DeploymentProjectKind projectType() {
        return DeploymentProjectKind.RUBY_SERVICE;
    }

    /**
     * Rejects Bundler/Ruby CLI conflicts and delegates to one architecture. / 拒绝 Bundler/Ruby CLI 冲突并委派给一个架构。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param languageFacts language facts / 语言事实
     * @param rejections reasons preventing admission to the next stage / 阻止进入下一阶段的原因
     * @return constructed or resolved deployment type assessment; null when no matching value is available / 构造或解析得到的部署类型评估；没有匹配值时为 null
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    @Override
    public DeploymentTypeReport inspect(Path root, SourceCheckFacts source, ProjectLanguageFacts languageFacts,
                                        List<RejectionReason> rejections) throws IOException {
        boolean nativeMetadata = ServiceMetadataChecker.present(root, "windowstolinux-ruby.properties");
        boolean bundlerMetadata = ServiceMetadataChecker.present(root, "Gemfile")
            || ServiceMetadataChecker.present(root, "Gemfile.lock");
        if (nativeMetadata && bundlerMetadata) {
            rejections.add(new RejectionReason("RUBY_ARCHITECTURE_CONFLICT",
                DeploymentLocalizedMessage.of("analysis.ruby.architectureConflict"), "deployment"));
            return null;
        }
        return nativeMetadata
            ? rubyCli.inspect(root, source, languageFacts, rejections)
            : bundler.inspect(root, source, languageFacts, rejections);
    }
}
