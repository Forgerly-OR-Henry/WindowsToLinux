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
 * Renders dependency-free PHP source through PHP CLI syntax validation. / 通过 PHP CLI 语法验证渲染无依赖 PHP 源码。
 */
public final class PhpCliBuildRenderer implements DeploymentBuildRenderer {
    /**
     * Returns the PHP service type. / 返回 PHP 服务类型。
     *
     * @return the PHP service type /  PHP 服务类型
     */
    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.PHP_SERVICE;
    }

    /**
     * Returns the PHP CLI identity. / 返回 PHP CLI 身份。
     *
     * @return the PHP CLI identity /  PHP CLI 身份
     */
    @Override
    public Set<DeploymentBuildToolType> buildTools() {
        return Set.of(DeploymentBuildToolType.PHP_CLI);
    }

    /**
     * Renders exact-version syntax checks and a reviewed source artifact. / 渲染精确版本语法检查与经审阅源码制品。
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
                || facts.buildTool() != DeploymentBuildToolType.PHP_CLI) {
            throw new IllegalArgumentException("PHP CLI renderer requires reviewed dependency-free inputs");
        }
        String version = php.version();
        String command = """
                command -v php >/dev/null
                php -r 'printf("%%d.%%d", PHP_MAJOR_VERSION, PHP_MINOR_VERSION);' | grep -Fx "${WTL_PHP_BRANCH:-%s}"
                test -f ./windowstolinux-php.properties
                test -f ./%s
                test ! -e ./composer.json
                test ! -e ./composer.lock
                mapfile -d '' -t sources < <(find -P . -type f -name '*.php' -print0 | LC_ALL=C sort -z)
                test "${#sources[@]}" -ge 1
                for source_file in "${sources[@]}"; do run php -n -l "$source_file"; done
                test -z "$(find -P . -type l -print -quit)"
                printf 'ARTIFACT=%%s\n' ./public
                """.formatted(version, php.entrypoint());
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits, command);
    }
}
