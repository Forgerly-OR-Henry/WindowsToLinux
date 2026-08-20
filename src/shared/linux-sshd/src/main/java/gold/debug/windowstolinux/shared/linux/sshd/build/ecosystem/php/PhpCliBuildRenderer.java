package gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.php;

import gold.debug.windowstolinux.shared.linux.sshd.build.generation.script.SafeBuildScriptEnvelope;
import gold.debug.windowstolinux.shared.linux.sshd.build.contract.spi.DeploymentBuildRenderer;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.util.Set;

/** Renders dependency-free PHP source through PHP CLI syntax validation. / 通过 PHP CLI 语法验证渲染无依赖 PHP 源码。 */
public final class PhpCliBuildRenderer implements DeploymentBuildRenderer {
    /** Returns the PHP service type. / 返回 PHP 服务类型。 */
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.PHP_SERVICE; }
    /** Returns the PHP CLI identity. / 返回 PHP CLI 身份。 */
    @Override public Set<DeploymentBuildToolType> buildTools() { return Set.of(DeploymentBuildToolType.PHP_CLI); }

    /** Renders exact-version syntax checks and a reviewed source artifact. / 渲染精确版本语法检查与经审阅源码制品。 */
    @Override
    public String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                         RemoteWorkspace workspace, BuildLimitConfiguration limits) {
        if (!(runtime instanceof DeploymentRuntimeSpecification.PhpService php)
                || facts.buildTool() != DeploymentBuildToolType.PHP_CLI) {
            throw new IllegalArgumentException("PHP CLI renderer requires reviewed dependency-free inputs");
        }
        String version = SafeBuildScriptEnvelope.shellQuote(php.version());
        String command = """
                command -v php >/dev/null
                php -r 'printf("%%d.%%d", PHP_MAJOR_VERSION, PHP_MINOR_VERSION);' | grep -Fx %s
                test -f ./windowstolinux-php.properties
                test -f ./public/index.php
                test ! -e ./composer.json
                test ! -e ./composer.lock
                mapfile -d '' -t sources < <(find -P . -type f -name '*.php' -print0 | LC_ALL=C sort -z)
                test "${#sources[@]}" -ge 1
                for source_file in "${sources[@]}"; do run php -n -l "$source_file"; done
                test -z "$(find -P ./public -type l -print -quit)"
                printf 'ARTIFACT=%%s\n' ./public
                """.formatted(version);
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits, command);
    }
}
