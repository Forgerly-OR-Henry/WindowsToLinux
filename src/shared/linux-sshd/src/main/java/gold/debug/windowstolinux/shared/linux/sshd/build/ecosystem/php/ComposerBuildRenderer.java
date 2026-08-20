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

/** Renders the fixed Composer extension architecture. / 渲染固定 Composer 扩展架构。 */
public final class ComposerBuildRenderer implements DeploymentBuildRenderer {
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.PHP_SERVICE; }
    @Override public Set<DeploymentBuildToolType> buildTools() { return Set.of(DeploymentBuildToolType.COMPOSER_LOCKED); }

    @Override
    public String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                         RemoteWorkspace workspace, BuildLimitConfiguration limits) {
        if (!(runtime instanceof DeploymentRuntimeSpecification.PhpService php)
                || facts.buildTool() != DeploymentBuildToolType.COMPOSER_LOCKED) {
            throw new IllegalArgumentException("Composer renderer requires reviewed locked PHP inputs");
        }
        String version = SafeBuildScriptEnvelope.shellQuote(php.version());
        String command = """
                command -v php >/dev/null
                command -v composer >/dev/null
                php -r 'printf("%%d.%%d", PHP_MAJOR_VERSION, PHP_MINOR_VERSION);' | grep -Fx %s
                test -f ./composer.json
                test -f ./composer.lock
                test -f ./public/index.php
                COMPOSER_ALLOW_SUPERUSER=0 run composer install --no-dev --no-interaction --no-progress --prefer-dist --classmap-authoritative --no-plugins --no-scripts
                test -f ./vendor/autoload.php
                test -z "$(find ./vendor -xdev -type l -print -quit)"
                printf 'ARTIFACT=%%s\n' ./vendor
                """.formatted(version);
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits, command);
    }
}
