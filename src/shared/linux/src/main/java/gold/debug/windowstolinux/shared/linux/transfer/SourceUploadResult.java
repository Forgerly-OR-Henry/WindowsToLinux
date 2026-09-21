package gold.debug.windowstolinux.shared.linux.transfer;

import java.util.Objects;

/**
 * Confirmation that a source archive reached the fixed candidate workspace.
 *
 *  <p>源码归档已到达固定候选工作区的确认结果。
 *
 * @param remoteArchivePath remote archive path / 远端归档路径
 * @param byteCount measured content length in bytes / 实测内容长度，单位为字节
 * @param contentSha256 content sha 256 / 内容SHA256
 * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
 */
public record SourceUploadResult(String remoteArchivePath, long byteCount, String contentSha256, String evidence) {
    /**
     * Validates and binds the inputs required by source upload result.
     * <p>校验并绑定源码上传结果所需输入。
     *
     * @param remoteArchivePath remote archive path / 远端归档路径
     * @param byteCount measured content length in bytes / 实测内容长度，单位为字节
     * @param contentSha256 content sha 256 / 内容SHA256
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public SourceUploadResult {
        remoteArchivePath = Objects.requireNonNull(remoteArchivePath, "remoteArchivePath");
        contentSha256 = Objects.requireNonNull(contentSha256, "contentSha256");
        evidence = Objects.requireNonNull(evidence, "evidence");
        if (!remoteArchivePath.startsWith("/var/lib/windowstolinux/work/") || byteCount < 0
                || !contentSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid upload receipt");
        }
    }
}
