package gold.debug.windowstolinux.shared.linux.build;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Reviewed build-only values; configuration history stays with the caller. / 仅承载审阅后的构建值，配置历史留在调用层。
 *
 * @param applicationId managed application identifier / 受管应用标识
 * @param entries the type-checked entries / 经类型检查的条目
 */
public record RemoteBuildEnvironment(String applicationId, Map<String, String> entries) {
    /**
     * Freezes canonical exports before execution. / 执行前固定规范化导出值。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param entries the type-checked entries / 经类型检查的条目
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public RemoteBuildEnvironment {
        Objects.requireNonNull(applicationId, "applicationId");
        if (!applicationId.matches("[a-z0-9][a-z0-9-]{0,62}"))
            throw new IllegalArgumentException("invalid application identity");
        entries = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(entries, "entries")));
        entries.forEach((key, value) -> {
            if (!key.matches("[A-Z][A-Z0-9_]{0,63}") || value == null || value.length() > 4096
                    || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0)
                throw new IllegalArgumentException("invalid build export");
        });
    }
}
