package gold.debug.windowstolinux.shared.analyze.ecosystem.ruby;

import gold.debug.windowstolinux.shared.analyze.ecosystem.ruby.bundler.RubyBundlerDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.ruby.rubycli.RubyCliDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.service.ServiceMetadataInspector;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.shared.analyze.spi.DeploymentTypeAssessment;
import gold.debug.windowstolinux.shared.analyze.spi.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Selects exactly one reviewed Ruby architecture. / 恰好选择一个经审阅的 Ruby 架构。 */
public final class RubyServiceDeploymentInspector implements DeploymentTypeInspector {
    private final RubyBundlerDeploymentInspector bundler = new RubyBundlerDeploymentInspector();
    private final RubyCliDeploymentInspector rubyCli = new RubyCliDeploymentInspector();

    /** Returns the Ruby service type. / 返回 Ruby 服务类型。 */
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.RUBY_SERVICE; }

    /** Rejects Bundler/Ruby CLI conflicts and delegates to one architecture. / 拒绝 Bundler/Ruby CLI 冲突并委派给一个架构。 */
    @Override
    public DeploymentTypeAssessment inspect(Path root, SourceInspectionFacts source, ProjectLanguageFacts languageFacts,
                                            List<RejectionReason> rejections) throws IOException {
        boolean nativeMetadata = ServiceMetadataInspector.present(root, "windowstolinux-ruby.properties");
        boolean bundlerMetadata = ServiceMetadataInspector.present(root, "Gemfile")
                || ServiceMetadataInspector.present(root, "Gemfile.lock");
        if (nativeMetadata && bundlerMetadata) {
            rejections.add(new RejectionReason("RUBY_ARCHITECTURE_CONFLICT",
                    LocalizedMessage.of("analysis.ruby.architectureConflict"), "deployment"));
            return null;
        }
        return nativeMetadata ? rubyCli.inspect(root, source, languageFacts, rejections)
                : bundler.inspect(root, source, languageFacts, rejections);
    }
}
