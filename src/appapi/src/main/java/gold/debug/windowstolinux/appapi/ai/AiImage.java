package gold.debug.windowstolinux.appapi.ai;

/**
 * Immutable image bytes supplied to a vision request. / 视觉请求使用的不可变图像字节。
 * @param mediaType image media type / 图像媒体类型
 * @param content payload bytes copied on input and output / 输入和输出时均复制的载荷字节
 */
public record AiImage(String mediaType, byte[] content) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param mediaType image media type / 图像媒体类型
     * @param content payload bytes copied on input and output / 输入和输出时均复制的载荷字节
     */
    public AiImage {
        java.util.Objects.requireNonNull(mediaType, "mediaType");
        content = java.util.Objects.requireNonNull(content, "content").clone();
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
