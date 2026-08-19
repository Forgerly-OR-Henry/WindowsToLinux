package gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.ruby;

import gold.debug.windowstolinux.shared.linux.sshd.build.script.SafeBuildScriptEnvelope;
import gold.debug.windowstolinux.shared.linux.sshd.build.spi.DeploymentBuildRenderer;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.util.Set;

/** Renders the fixed Bundler extension architecture. / 渲染固定 Bundler 扩展架构。 */
public final class BundlerBuildRenderer implements DeploymentBuildRenderer {
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.RUBY_SERVICE; }
    @Override public Set<DeploymentBuildToolType> buildTools() { return Set.of(DeploymentBuildToolType.BUNDLER_LOCKED); }

    @Override
    public String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                         RemoteWorkspace workspace, BuildLimitConfiguration limits) {
        if (!(runtime instanceof DeploymentRuntimeSpecification.RubyService ruby)
                || facts.buildTool() != DeploymentBuildToolType.BUNDLER_LOCKED) {
            throw new IllegalArgumentException("Bundler renderer requires reviewed locked Ruby inputs");
        }
        String version = SafeBuildScriptEnvelope.shellQuote(ruby.version());
        String command = """
                command -v ruby >/dev/null
                command -v bundle >/dev/null
                ruby -e 'print RUBY_VERSION' | grep -Fx %s
                test -f ./Gemfile
                test -f ./Gemfile.lock
                test -f ./config.ru
                bundle config set --local deployment true
                bundle config set --local path vendor/bundle
                run bundle install --jobs 1 --retry 0
                run bundle exec ruby -e 'require "rack"; require "webrick"'
                test -d ./vendor/bundle
                test -z "$(find ./vendor/bundle -xdev -type l -print -quit)"
                printf 'ARTIFACT=%%s\n' ./vendor/bundle
                """.formatted(version);
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits, command);
    }
}
