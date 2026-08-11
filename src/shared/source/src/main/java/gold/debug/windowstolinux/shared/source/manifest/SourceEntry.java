package gold.debug.windowstolinux.shared.source.manifest;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One immutable regular-file member in a normalized source snapshot.
 *
 * <p>规范化源码快照中的单个不可变普通文件成员。
 *
 * @param path the {@code path} value / {@code path} 值
 * @param relativePath the {@code relativePath} value / {@code relativePath} 值
 * @param byteCount the {@code byteCount} value / {@code byteCount} 值
 */
public record SourceEntry(Path path, String relativePath, long byteCount) {
    /**
     * Creates a {@code SourceEntry} instance.
     *
     * <p>创建 {@code SourceEntry} 实例。
     *
     * @param path the {@code path} value / {@code path} 值
     * @param relativePath the {@code relativePath} value / {@code relativePath} 值
     * @param byteCount the {@code byteCount} value / {@code byteCount} 值
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public SourceEntry {
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        relativePath = Objects.requireNonNull(relativePath, "relativePath");
        if (relativePath.isBlank() || byteCount < 0) {
            throw new IllegalArgumentException("source entry must have a normalized path and non-negative size");
        }
    }
}
