package gold.debug.windowstolinux.shared.standard.deploy.build.ecosystem.php;

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
 * Renders the fixed Composer extension architecture. / 渲染固定 Composer 扩展架构。
 */
public final class ComposerBuildRenderer implements DeploymentBuildRenderer {
    /**
     * Returns the supported project category handled by this strategy.
     * <p>返回当前策略处理的受支持项目类别。
     *
     * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
     */
    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.PHP_SERVICE;
    }

    /**
     * Returns the supported build-tool identifiers recognized by this strategy.
     * <p>返回当前策略识别的受支持构建工具标识。
     *
     * @return the supported build-tool identifiers recognized by this strategy / 当前策略识别的受支持构建工具标识
     */
    @Override
    public Set<DeploymentBuildToolType> buildTools() {
        return Set.of(DeploymentBuildToolType.COMPOSER_LOCKED);
    }

    /**
     * Renders composer build as text without executing the rendered command.
     * <p>渲染Composer构建为文本，不执行所渲染命令。
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
        if (!(runtime instanceof DeploymentRuntimeSpecification.PhpService php)
                || facts.buildTool() != DeploymentBuildToolType.COMPOSER_LOCKED) {
            throw new IllegalArgumentException("Composer renderer requires reviewed locked PHP inputs");
        }
        String version = php.version();
        String command = """
                command -v php >/dev/null
                command -v composer >/dev/null
                php -r 'printf("%%d.%%d", PHP_MAJOR_VERSION, PHP_MINOR_VERSION);' | grep -Fx "${WTL_PHP_BRANCH:-%s}"
                test -f ./composer.json
                test -f ./composer.lock
                test -f ./%s
                COMPOSER_ALLOW_SUPERUSER=0 run php "$(command -v composer)" install --no-dev --no-interaction --no-progress --prefer-dist --classmap-authoritative --no-plugins --no-scripts
                test -f ./vendor/autoload.php
                test -z "$(find ./vendor -xdev -type l -print -quit)"
                printf 'ARTIFACT=%%s\n' ./vendor
                """
                .formatted(version, php.entrypoint());
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits, command);
    }
}
