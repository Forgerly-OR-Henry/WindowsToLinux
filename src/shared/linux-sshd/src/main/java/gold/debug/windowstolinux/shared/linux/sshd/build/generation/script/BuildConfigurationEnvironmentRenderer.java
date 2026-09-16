package gold.debug.windowstolinux.shared.linux.sshd.build.generation.script;

import gold.debug.windowstolinux.shared.linux.sshd.build.generation.script.SafeBuildScriptEnvelope;


import gold.debug.windowstolinux.shared.linux.build.RemoteBuildEnvironment;
import java.util.Objects;

/** Renders reviewed non-secret build values as fixed shell exports. / 将经审阅的非秘密构建值渲染为固定 Shell 导出。 */
public final class BuildConfigurationEnvironmentRenderer {
    private BuildConfigurationEnvironmentRenderer() { }

    /** Renders the controlled output. / 渲染受控输出。 */
    public static String render(RemoteBuildEnvironment snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        StringBuilder script = new StringBuilder();
        snapshot.entries().forEach((key, value) -> script.append("export ").append(key).append('=')
                .append(SafeBuildScriptEnvelope.shellQuote(value)).append('\n'));
        return script.toString();
    }
}
