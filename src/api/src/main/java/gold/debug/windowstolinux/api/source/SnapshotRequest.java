package gold.debug.windowstolinux.api.source;

import java.util.List;

/**
 * Explicit relative files and total copied-byte budget. / 明确的相对文件集合及总复制容量预算。
 * @param relativePaths copied paths relative to the authorized source root / 相对已授权来源根目录的路径副本
 * @param maxTotalBytes nonnegative total copied-byte budget / 非负的复制总字节预算
 */
public record SnapshotRequest(List<String> relativePaths, long maxTotalBytes) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param relativePaths copied paths relative to the authorized source root / 相对已授权来源根目录的路径副本
     * @param maxTotalBytes nonnegative total copied-byte budget / 非负的复制总字节预算
     */
    public SnapshotRequest {
        relativePaths = List.copyOf(relativePaths);
        if (maxTotalBytes < 0)
            throw new IllegalArgumentException("maxTotalBytes is below its minimum");
    }
}
