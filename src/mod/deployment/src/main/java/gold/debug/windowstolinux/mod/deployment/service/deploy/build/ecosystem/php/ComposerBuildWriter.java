package gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.php;

import java.util.Set;

import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentBuildLimits;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.contract.DeploymentBuildWriter;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.script.SafeBuildScriptEnvelope;

/**
 * Renders the fixed Composer extension architecture. / 渲染固定 Composer 扩展架构。
 */
public final class ComposerBuildWriter implements DeploymentBuildWriter {
    /**
     * Returns the supported project category handled by this strategy.
     * <p>返回当前策略处理的受支持项目类别。
     *
     * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
     */
    @Override
    public DeploymentProjectKind projectType() {
        return DeploymentProjectKind.PHP_SERVICE;
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
    public String render(DeploymentProjectFacts facts, DeploymentRuntimeDefinition runtime,
                         DeploymentRemoteWorkspace workspace, DeploymentBuildLimits limits) {
        if (!(runtime instanceof DeploymentRuntimeDefinition.PhpService php)
            || facts.buildTool() != DeploymentBuildToolType.COMPOSER_LOCKED) {
            throw new IllegalArgumentException("Composer renderer requires reviewed locked PHP inputs");
        }
        String version = php.version();
        String command = """
            require_build_tool php
            php -r 'printf("%%d.%%d", PHP_MAJOR_VERSION, PHP_MINOR_VERSION);' | grep -Fx "${WTL_PHP_BRANCH:-%s}"
            test -f ./composer.json
            test -f ./composer.lock
            test -f ./%s
            %s
            COMPOSER_ALLOW_SUPERUSER=0 run php "$composer_phar" install --no-dev --no-interaction --no-progress --prefer-dist --classmap-authoritative --no-plugins --no-scripts
            test -f ./vendor/autoload.php
            test -z "$(find ./vendor -xdev -type l -print -quit)"
            printf 'ARTIFACT=%%s\n' ./vendor
            """
            .formatted(version, php.entrypoint(), prepareComposer());
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits, command);
    }

    /** Uses one digest-pinned official PHAR inside the isolated candidate, without executing an installer or host Composer. / 在隔离候选内使用摘要固定的官方PHAR，不执行安装器或宿主Composer。
     * @return deterministic Composer preparation script / 确定性 Composer 准备脚本 */
    private static String prepareComposer() {
        return """
            require_build_tool curl
            require_build_tool sha256sum
            composer_phar="$mutable/home/.wtl-tools/composer-2.10.3.phar"
            mkdir -p -- "${composer_phar%/*}"
            run curl --disable --fail --silent --show-error --proto '=https' --max-time 120 --retry 2 --retry-max-time 180 --max-filesize 16777216 \\
              --output "$composer_phar" 'https://getcomposer.org/download/2.10.3/composer.phar'
            test -f "$composer_phar" && test ! -L "$composer_phar"
            test "$(stat -c '%s' -- "$composer_phar")" -le 16777216
            printf '%s  %s\\n' '7a2d379d5b8ffdaa028580ef26494c36d2feef4b178d3dd1473a4dbc5e17c8d6' "$composer_phar" | run sha256sum --check --status
            chmod 400 -- "$composer_phar"
            printf 'BUILD_MANAGER=COMPOSER:2.10.3:candidate\\n'
            """;
    }
}
