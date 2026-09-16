package gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.ruby;

import gold.debug.windowstolinux.shared.linux.sshd.build.generation.script.SafeBuildScriptEnvelope;
import gold.debug.windowstolinux.shared.linux.sshd.build.contract.spi.DeploymentBuildRenderer;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.util.Set;

/** Renders dependency-free Ruby source through Ruby CLI syntax validation. / 通过 Ruby CLI 语法验证渲染无依赖 Ruby 源码。 */
public final class RubyCliBuildRenderer implements DeploymentBuildRenderer {
    /** Returns the Ruby service type. / 返回 Ruby 服务类型。 */
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.RUBY_SERVICE; }
    /** Returns the Ruby CLI identity. / 返回 Ruby CLI 身份。 */
    @Override public Set<DeploymentBuildToolType> buildTools() { return Set.of(DeploymentBuildToolType.RUBY_CLI); }

    /** Renders exact-version syntax checks and one reviewed entrypoint. / 渲染精确版本语法检查与单一经审阅入口。 */
    @Override
    public String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                         RemoteWorkspace workspace, BuildLimitConfiguration limits) {
        if (!(runtime instanceof DeploymentRuntimeSpecification.RubyService ruby)
                || facts.buildTool() != DeploymentBuildToolType.RUBY_CLI) {
            throw new IllegalArgumentException("Ruby CLI renderer requires reviewed dependency-free inputs");
        }
        String version = ruby.version();
        String entrypoint = SafeBuildScriptEnvelope.shellQuote("./" + ruby.entrypoint());
        String command = """
                command -v ruby >/dev/null
                ruby -e 'print RUBY_VERSION' | grep -Fx "${WTL_RUBY_VERSION:-%s}"
                test -f ./windowstolinux-ruby.properties
                test ! -e ./Gemfile
                test ! -e ./Gemfile.lock
                entrypoint=%s
                test -f "$entrypoint"
                mapfile -d '' -t sources < <(find -P . -type f -name '*.rb' -print0 | LC_ALL=C sort -z)
                test "${#sources[@]}" -ge 1
                for source_file in "${sources[@]}"; do run ruby -c "$source_file"; done
                printf 'ARTIFACT=%%s\n' "$entrypoint"
                """.formatted(version, entrypoint);
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits, command);
    }
}
