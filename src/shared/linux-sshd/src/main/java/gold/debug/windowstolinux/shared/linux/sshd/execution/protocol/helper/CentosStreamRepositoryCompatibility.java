package gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Owns fixed platform compatibility fragments pending final placement. / 暂存固定平台适配片段，最终归属待确认。 */
public final class CentosStreamRepositoryCompatibility {
    private CentosStreamRepositoryCompatibility() { }

    /** Expands fixed resource markers without changing generated protocol bytes. / 展开固定资源标记，保持生成协议字节。 */
    public static String expand(String script) {
        if (script.contains("# @compat:centos-repositories@\n")) script = script.replace("# @compat:centos-repositories@\n", resource("centos-source-repositories.py"));
        return script;
    }

    /** Transaction-local option; no repository configuration is changed. / 事务局部选项，不修改仓库配置。 */
    public static String installationOptions(boolean selected) {
        return selected ? "--enablerepo=crb " : "";
    }

    private static String resource(String name) {
        try (var input = CentosStreamRepositoryCompatibility.class.getResourceAsStream("/gold/debug/windowstolinux/shared/linux/sshd/distro/dnf/" + name)) {
            if (input == null) throw new IllegalStateException("Missing compatibility resource: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException failure) {
            throw new IllegalStateException("Unreadable compatibility resource: " + name, failure);
        }
    }
}
