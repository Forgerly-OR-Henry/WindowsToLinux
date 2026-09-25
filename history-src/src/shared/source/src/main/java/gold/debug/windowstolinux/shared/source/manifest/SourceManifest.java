package gold.debug.windowstolinux.shared.source.manifest;

import java.util.List;
import java.util.Objects;

/**
 * Deterministically ordered source members and exclusions prepared for archiving.
 *
 *  <p>为归档准备的确定排序源码成员与排除项。
 *
 * @param entries the type-checked entries / 经类型检查的条目
 * @param excludedEntries entries excluded by the safe archive policy / 安全归档策略排除的条目
 * @param byteCount measured content length in bytes / 实测内容长度，单位为字节
 */
public record SourceManifest(List<SourceEntry> entries, List<String> excludedEntries, long byteCount) {
    /**
     * Validates and binds the inputs required by source manifest.
     * <p>校验并绑定源码清单所需输入。
     *
     * @param entries the type-checked entries / 经类型检查的条目
     * @param excludedEntries entries excluded by the safe archive policy / 安全归档策略排除的条目
     * @param byteCount measured content length in bytes / 实测内容长度，单位为字节
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public SourceManifest {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        excludedEntries = List.copyOf(Objects.requireNonNull(excludedEntries, "excludedEntries"));
        if (byteCount < 0) {
            throw new IllegalArgumentException("byteCount must not be negative");
        }
    }
}
