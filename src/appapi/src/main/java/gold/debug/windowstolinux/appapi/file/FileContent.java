package gold.debug.windowstolinux.appapi.file;

/**
 * Copied source bytes and digest from an authorized selection. / 从已授权来源取得的独立字节及摘要。
 * @param relativePath path relative to the authorized source root / 相对已授权来源根目录的路径
 * @param content payload bytes copied on input and output / 输入和输出时均复制的载荷字节
 * @param sha256 observed content SHA-256 digest / 已观察的内容 SHA-256 摘要
 */
public record FileContent(String relativePath, byte[] content, String sha256) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param relativePath path relative to the authorized source root / 相对已授权来源根目录的路径
     * @param content payload bytes copied on input and output / 输入和输出时均复制的载荷字节
     * @param sha256 observed content SHA-256 digest / 已观察的内容 SHA-256 摘要
     */
    public FileContent {
        java.util.Objects.requireNonNull(relativePath, "relativePath");
        content = java.util.Objects.requireNonNull(content, "content").clone();
        java.util.Objects.requireNonNull(sha256, "sha256");
    }

    /**
     * Returns an independent byte copy. / 返回独立字节副本。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    @Override
    public byte[] content() {
        return content.clone();
    }
}
