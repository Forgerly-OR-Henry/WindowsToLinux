package gold.debug.windowstolinux.shared.source.manifest;

import java.util.List;
import java.util.Objects;

/**
 * Deterministically ordered source members and exclusions prepared for archiving.
 *
 * <p>为归档准备的确定排序源码成员与排除项。
 *
 * @param entries the {@code entries} value / {@code entries} 值
 * @param excludedEntries the {@code excludedEntries} value / {@code excludedEntries} 值
 * @param byteCount the {@code byteCount} value / {@code byteCount} 值
 */
public record SourceManifest(List<SourceEntry> entries, List<String> excludedEntries, long byteCount) {
    /**
     * Creates a {@code SourceManifest} instance.
     *
     * <p>创建 {@code SourceManifest} 实例。
     *
     * @param entries the {@code entries} value / {@code entries} 值
     * @param excludedEntries the {@code excludedEntries} value / {@code excludedEntries} 值
     * @param byteCount the {@code byteCount} value / {@code byteCount} 值
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public SourceManifest {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        excludedEntries = List.copyOf(Objects.requireNonNull(excludedEntries, "excludedEntries"));
        if (byteCount < 0) {
            throw new IllegalArgumentException("byteCount must not be negative");
        }
    }
}
