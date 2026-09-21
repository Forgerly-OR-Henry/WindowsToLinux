package gold.debug.windowstolinux.shared.source.manifest;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One immutable regular-file member in a normalized source snapshot.
 *
 *  <p>规范化源码快照中的单个不可变普通文件成员。
 *
 * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
 * @param relativePath relative path / 相对路径
 * @param byteCount measured content length in bytes / 实测内容长度，单位为字节
 */
public record SourceEntry(Path path, String relativePath, long byteCount) {
    /**
     * Validates and binds the inputs required by source entry.
     * <p>校验并绑定源码条目所需输入。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @param relativePath relative path / 相对路径
     * @param byteCount measured content length in bytes / 实测内容长度，单位为字节
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public SourceEntry {
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        relativePath = Objects.requireNonNull(relativePath, "relativePath");
        if (relativePath.isBlank() || byteCount < 0) {
            throw new IllegalArgumentException("source entry must have a normalized path and non-negative size");
        }
    }
}
