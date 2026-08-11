package gold.debug.windowstolinux.app.windows.workspace;

import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;

import java.util.List;
import java.util.Objects;

/**
 * Represents an immutable {@code PreparedSourceArchive} value.
 *
 * <p>表示不可变的 {@code PreparedSourceArchive} 值。
 *
 * @param descriptor the {@code descriptor} value / {@code descriptor} 值
 * @param excludedEntries the {@code excludedEntries} value / {@code excludedEntries} 值
 */
public record PreparedSourceArchive(SourceArchiveDescriptor descriptor, List<String> excludedEntries) {
    /**
     * Creates a {@code PreparedSourceArchive} instance.
     *
     * <p>创建 {@code PreparedSourceArchive} 实例。
     *
     * @param descriptor the {@code descriptor} value / {@code descriptor} 值
     * @param excludedEntries the {@code excludedEntries} value / {@code excludedEntries} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public PreparedSourceArchive {
        Objects.requireNonNull(descriptor, "descriptor");
        excludedEntries = List.copyOf(Objects.requireNonNull(excludedEntries, "excludedEntries"));
    }
}
