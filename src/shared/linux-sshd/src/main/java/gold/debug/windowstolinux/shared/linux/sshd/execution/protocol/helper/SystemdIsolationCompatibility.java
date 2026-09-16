package gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Owns fixed platform compatibility fragments pending final placement. / 暂存固定平台适配片段，最终归属待确认。 */
public final class SystemdIsolationCompatibility {
    private SystemdIsolationCompatibility() { }

    /** Expands fixed resource markers without changing generated protocol bytes. / 展开固定资源标记，保持生成协议字节。 */
    public static String expand(String script) {
        if (script.contains("# @compat:systemd-isolation@\n")) script = script.replace("# @compat:systemd-isolation@\n", resource("systemd-manager-isolation.sh"));
        if (script.contains("# @compat:selinux-entry@\n")) script = script.replace("# @compat:selinux-entry@\n", resource("selinux-command-entry.sh"));
        return script;
    }

    private static String resource(String name) {
        try (var input = SystemdIsolationCompatibility.class.getResourceAsStream("/gold/debug/windowstolinux/shared/linux/sshd/runtime/systemd/" + name)) {
            if (input == null) throw new IllegalStateException("Missing compatibility resource: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException failure) {
            throw new IllegalStateException("Unreadable compatibility resource: " + name, failure);
        }
    }
}
