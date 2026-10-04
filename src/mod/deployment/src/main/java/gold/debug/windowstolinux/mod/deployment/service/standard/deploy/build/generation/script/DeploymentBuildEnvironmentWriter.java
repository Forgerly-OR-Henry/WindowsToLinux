package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.build.generation.script;

import java.util.Objects;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentBuildEnvironment;

/** Renders reviewed non-secret build values as fixed shell exports. / 将已审阅非秘密构建值渲染为固定 Shell 导出。 */
public final class DeploymentBuildEnvironmentWriter {
    /** Prevents utility construction. / 防止构造工具类。 */
    private DeploymentBuildEnvironmentWriter() {
    }

    /** Produces shell-quoted canonical exports. / 生成 Shell 引用后的规范导出值。
     * @param environment original reviewed values / 原已审阅值
     * @return fixed export script / 固定导出脚本 */
    public static String render(DeploymentBuildEnvironment environment) {
        Objects.requireNonNull(environment);
        StringBuilder script = new StringBuilder();
        environment.entries().forEach((key, value) -> script.append("export ").append(key).append('=')
            .append(SafeBuildScriptEnvelope.shellQuote(value)).append('\n'));
        return script.toString();
    }
}
