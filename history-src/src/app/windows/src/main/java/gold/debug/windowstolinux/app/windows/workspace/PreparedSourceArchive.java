package gold.debug.windowstolinux.app.windows.workspace;

import java.util.List;
import java.util.Objects;

import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;

/**
 * Represents an immutable {@code PreparedSourceArchive} value.
 *
 *  <p>表示不可变的 {@code PreparedSourceArchive} 值。
 *
 * @param descriptor descriptor / 描述符
 * @param excludedEntries entries excluded by the safe archive policy / 安全归档策略排除的条目
 */
public record PreparedSourceArchive(SourceArchiveDescriptor descriptor, List<String> excludedEntries) {
    /**
     * Validates and binds the inputs required by prepared source archive.
     * <p>校验并绑定已准备源码归档所需输入。
     *
     * @param descriptor descriptor / 描述符
     * @param excludedEntries entries excluded by the safe archive policy / 安全归档策略排除的条目
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public PreparedSourceArchive {
        Objects.requireNonNull(descriptor, "descriptor");
        excludedEntries = List.copyOf(Objects.requireNonNull(excludedEntries, "excludedEntries"));
    }
}
