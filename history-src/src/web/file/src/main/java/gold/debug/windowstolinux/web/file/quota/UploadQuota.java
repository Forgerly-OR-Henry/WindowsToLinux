package gold.debug.windowstolinux.web.file.quota;

/**
 * Defines bounded storage and extraction limits shared by directory and archive uploads.
 * <p>定义目录及归档上传共享的有界存储和提取限制。
 *
 * @param fileBytes file bytes / 文件字节
 * @param projectBytes project bytes / 项目字节
 * @param totalBytes total bytes / 总字节
 * @param members members / 成员集合
 * @param pathLength path length / 路径长度
 */
public record UploadQuota(long fileBytes, long projectBytes, long totalBytes, int members, int pathLength) {
    /**
     * Validates and binds the inputs required by upload quota.
     * <p>校验并绑定上传配额所需输入。
     *
     * @param fileBytes file bytes / 文件字节
     * @param projectBytes project bytes / 项目字节
     * @param totalBytes total bytes / 总字节
     * @param members members / 成员集合
     * @param pathLength path length / 路径长度
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public UploadQuota {
        if (fileBytes < 1 || projectBytes < fileBytes || totalBytes < projectBytes || members < 1 || pathLength < 1)
            throw new IllegalArgumentException("Invalid upload quota");
    }
}
