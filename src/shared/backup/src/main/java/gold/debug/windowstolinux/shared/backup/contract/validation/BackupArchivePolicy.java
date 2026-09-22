package gold.debug.windowstolinux.shared.backup.contract.validation;

/**
 * Explicit archive resource and path bounds. / 显式归档资源与路径边界。
 *
 * @param maximumMembers maximum members / 最大成员集合
 * @param maximumMemberBytes maximum member bytes / 最大成员字节
 * @param maximumTotalBytes maximum total bytes / 最大总字节
 * @param maximumPathLength maximum path length / 最大路径长度
 * @param maximumManifestBytes maximum manifest bytes / 最大清单字节
 * @param maximumCompressionRatio maximum compression ratio / 最大CompressionRatio
 */
public record BackupArchivePolicy(int maximumMembers, long maximumMemberBytes, long maximumTotalBytes,
        int maximumPathLength, int maximumManifestBytes, double maximumCompressionRatio) {
    /**
     * Validates conservative positive bounds. / 校验保守的正数边界。
     *
     * @param maximumMembers maximum members / 最大成员集合
     * @param maximumMemberBytes maximum member bytes / 最大成员字节
     * @param maximumTotalBytes maximum total bytes / 最大总字节
     * @param maximumPathLength maximum path length / 最大路径长度
     * @param maximumManifestBytes maximum manifest bytes / 最大清单字节
     * @param maximumCompressionRatio maximum compression ratio / 最大CompressionRatio
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public BackupArchivePolicy {
        if (maximumMembers < 1 || maximumMembers > 100_000)
            throw new IllegalArgumentException("invalid member bound");
        if (maximumMemberBytes < 1 || maximumTotalBytes < maximumMemberBytes) {
            throw new IllegalArgumentException("invalid archive size bounds");
        }
        if (maximumPathLength < 32 || maximumPathLength > 4096)
            throw new IllegalArgumentException("invalid path bound");
        if (maximumManifestBytes < 1024 || maximumManifestBytes > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("invalid manifest bound");
        }
        if (!Double.isFinite(maximumCompressionRatio) || maximumCompressionRatio < 1.0d
                || maximumCompressionRatio > 10_000.0d) {
            throw new IllegalArgumentException("invalid compression ratio bound");
        }
    }

    /**
     * Returns product defaults that remain overrideable by expert configuration. / 返回可由专家配置覆盖的产品默认值。
     *
     * @return product defaults that remain overrideable by expert configuration / 可由专家配置覆盖的产品默认值
     */
    public static BackupArchivePolicy defaults() {
        return new BackupArchivePolicy(4096, 4L * 1024 * 1024 * 1024, 32L * 1024 * 1024 * 1024, 512, 1024 * 1024,
                200.0d);
    }
}
