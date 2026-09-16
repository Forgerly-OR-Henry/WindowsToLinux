package gold.debug.windowstolinux.shared.linux.protocol;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Runtime-only values bound to the original reviewed configuration digest. / 绑定原审阅摘要的运行配置投影。 */
public record RemoteRuntimeConfiguration(String applicationId, String sha256, Map<String, String> entries) {
    /** Validates wire bounds and preserves deterministic rendering order. / 校验传输边界并保持确定的渲染顺序。 */
    public RemoteRuntimeConfiguration {
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(sha256, "sha256");
        if (!applicationId.matches("[a-z0-9][a-z0-9-]{0,62}") || !sha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("invalid runtime configuration binding");
        entries = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(entries, "entries")));
        entries.forEach((key, value) -> {
            if (!key.matches("[A-Z][A-Z0-9_]{0,63}") || value == null || value.length() > 4096
                    || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0) throw new IllegalArgumentException("invalid runtime value");
        });
    }
}
