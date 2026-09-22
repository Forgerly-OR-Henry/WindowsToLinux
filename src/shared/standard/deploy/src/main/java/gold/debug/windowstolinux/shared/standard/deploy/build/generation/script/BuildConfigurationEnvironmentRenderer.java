package gold.debug.windowstolinux.shared.standard.deploy.build.generation.script;

import java.util.Objects;

import gold.debug.windowstolinux.shared.linux.build.RemoteBuildEnvironment;
import gold.debug.windowstolinux.shared.standard.deploy.build.generation.script.SafeBuildScriptEnvelope;

/**
 * Renders reviewed non-secret build values as fixed shell exports. / 将经审阅的非秘密构建值渲染为固定 Shell 导出。
 */
public final class BuildConfigurationEnvironmentRenderer {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private BuildConfigurationEnvironmentRenderer() {
    }

    /**
     * Renders the controlled output. / 渲染受控输出。
     *
     * @param snapshot immutable observation or configuration revision used by the operation / 操作使用的不可变观测或配置修订
     * @return render text / 渲染文本
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static String render(RemoteBuildEnvironment snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        StringBuilder script = new StringBuilder();
        snapshot.entries().forEach((key, value) -> script.append("export ").append(key).append('=')
                .append(SafeBuildScriptEnvelope.shellQuote(value)).append('\n'));
        return script.toString();
    }
}
