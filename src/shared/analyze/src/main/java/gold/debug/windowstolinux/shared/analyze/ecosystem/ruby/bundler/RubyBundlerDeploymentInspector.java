package gold.debug.windowstolinux.shared.analyze.ecosystem.ruby.bundler;

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

/** Inspects one Bundler-locked Rack service without executing Ruby. / 在不执行 Ruby 的情况下检查一个 Bundler 锁定的 Rack 服务。 */
public final class RubyBundlerDeploymentInspector implements DeploymentTypeInspector {
    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.RUBY_SERVICE;
    }

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
