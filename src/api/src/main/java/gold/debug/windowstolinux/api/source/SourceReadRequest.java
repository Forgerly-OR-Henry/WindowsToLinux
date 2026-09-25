package gold.debug.windowstolinux.api.source;

/**
 * Bounded read confined to the selected source root. / 限制在已选来源根目录内的有界读取。
 * @param relativePath path relative to the authorized source root / 相对已授权来源根目录的路径
 * @param maxBytes nonnegative byte budget / 非负字节预算
 */
public record SourceReadRequest(String relativePath, long maxBytes) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param relativePath path relative to the authorized source root / 相对已授权来源根目录的路径
     * @param maxBytes nonnegative byte budget / 非负字节预算
     */
    public SourceReadRequest {
        java.util.Objects.requireNonNull(relativePath, "relativePath");
        if (maxBytes < 0)
            throw new IllegalArgumentException("maxBytes is below its minimum");
    }
}
