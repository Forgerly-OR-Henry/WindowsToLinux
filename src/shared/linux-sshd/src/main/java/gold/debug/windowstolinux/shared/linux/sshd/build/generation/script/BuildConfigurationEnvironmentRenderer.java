package gold.debug.windowstolinux.shared.linux.sshd.build.generation.script;

import gold.debug.windowstolinux.shared.linux.sshd.build.generation.script.SafeBuildScriptEnvelope;

import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;

import java.util.Comparator;
import java.util.Objects;

/** Renders reviewed non-secret build values as fixed shell exports. / 将经审阅的非秘密构建值渲染为固定 Shell 导出。 */
public final class BuildConfigurationEnvironmentRenderer {
    private BuildConfigurationEnvironmentRenderer() { }

    /** Renders the controlled output. / 渲染受控输出。 */
    public static String render(ConfigurationSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        StringBuilder script = new StringBuilder();
        snapshot.entries().stream()
                .filter(entry -> entry.scope() == ConfigurationScope.BUILD)
                .sorted(Comparator.comparing(gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry::key))
                .forEach(entry -> script.append("export ").append(entry.key()).append('=')
                        .append(SafeBuildScriptEnvelope.shellQuote(entry.value().canonicalValue())).append('\n'));
        return script.toString();
    }
}
