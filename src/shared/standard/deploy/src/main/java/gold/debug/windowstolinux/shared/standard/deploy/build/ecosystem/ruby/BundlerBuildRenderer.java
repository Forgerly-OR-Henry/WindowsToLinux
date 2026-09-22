package gold.debug.windowstolinux.shared.standard.deploy.build.ecosystem.ruby;

import java.util.Set;

import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.standard.deploy.build.contract.spi.DeploymentBuildRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.build.generation.script.SafeBuildScriptEnvelope;

/**
 * Renders the fixed Bundler extension architecture. / 渲染固定 Bundler 扩展架构。
 */
public final class BundlerBuildRenderer implements DeploymentBuildRenderer {
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
     * Returns the supported build-tool identifiers recognized by this strategy.
     * <p>返回当前策略识别的受支持构建工具标识。
     *
     * @return the supported build-tool identifiers recognized by this strategy / 当前策略识别的受支持构建工具标识
     */
    @Override
    public Set<DeploymentBuildToolType> buildTools() {
        return Set.of(DeploymentBuildToolType.BUNDLER_LOCKED);
    }

    /**
     * Renders bundler build as text without executing the rendered command.
     * <p>渲染Bundler构建为文本，不执行所渲染命令。
     *
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @return render text / 渲染文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    @Override
    public String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
            RemoteWorkspace workspace, BuildLimitConfiguration limits) {
        if (!(runtime instanceof DeploymentRuntimeSpecification.RubyService ruby)
                || facts.buildTool() != DeploymentBuildToolType.BUNDLER_LOCKED) {
            throw new IllegalArgumentException("Bundler renderer requires reviewed locked Ruby inputs");
        }
        String version = ruby.version();
        String command = """
                command -v ruby >/dev/null
                command -v gem >/dev/null
                ruby -e 'print RUBY_VERSION' | grep -Fx "${WTL_RUBY_VERSION:-%s}"
                test -f ./Gemfile
                test -f ./Gemfile.lock
                test -f ./%s
                bundler_version="$(awk '/^BUNDLED WITH$/ {getline; gsub(/^[ \\t]+|[ \\t]+$/, ""); print}' ./Gemfile.lock)"
                [[ "$bundler_version" =~ ^[0-9]+[.][0-9]+[.][0-9]+$ ]] || { printf 'BUILD_REJECT=bundler-exact-version-required\\n'; exit 64; }
                [ "${bundler_version%%%%.*}" -ge 2 ] || { printf 'BUILD_TOOL_INCOMPATIBLE=bundler-config-set\\n'; exit 64; }
                export GEM_HOME="$PWD/.w2l/bundler" GEM_PATH="$PWD/.w2l/bundler:"
                run gem install bundler --version "$bundler_version" --install-dir "$GEM_HOME" --bindir "$GEM_HOME/bin" --env-shebang --no-document --ignore-dependencies --source https://rubygems.org
                export PATH="$GEM_HOME/bin:$PATH" BUNDLE_VERSION="$bundler_version"
                bundle --version | grep -Fx "Bundler version $bundler_version"
                bundle config set --local deployment true
                bundle config set --local path vendor/bundle
                run bundle install --jobs 1 --retry 0
                %s
                test -d ./vendor/bundle
                test -z "$(find ./vendor/bundle -xdev -type l -print -quit)"
                printf 'ARTIFACT=%%s\n' ./vendor/bundle
                """
                .formatted(version, ruby.entrypoint(),
                        ruby.servicePort() == 0
                                ? "run bundle exec ruby -c ./" + ruby.entrypoint()
                                : "run bundle exec ruby -e 'require \"rack\"; require \"webrick\"'");
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits, command);
    }
}
