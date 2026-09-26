package gold.debug.windowstolinux.appapi.linux;

/**
 * Observed transfer size and verified content digest. / 已观察的传输大小及内容摘要。
 * @param transferredBytes observed nonnegative transferred byte count / 已观察的非负传输字节数
 * @param sha256 observed content SHA-256 digest / 已观察的内容 SHA-256 摘要
 * @param localPath actual selected local endpoint / 实际选定本地路径
 * @param remoteAbsolutePath actual selected remote endpoint / 实际选定远端路径
 */
public record TransferResult(long transferredBytes, String sha256, java.nio.file.Path localPath,
        String remoteAbsolutePath) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param transferredBytes observed nonnegative transferred byte count / 已观察的非负传输字节数
     * @param sha256 observed content SHA-256 digest / 已观察的内容 SHA-256 摘要
     * @param localPath actual selected local endpoint / 实际选定本地路径
     * @param remoteAbsolutePath actual selected remote endpoint / 实际选定远端路径
     */
    public TransferResult {
        if (transferredBytes < 0)
            throw new IllegalArgumentException("transferredBytes is below its minimum");
        java.util.Objects.requireNonNull(sha256, "sha256");
        java.util.Objects.requireNonNull(localPath, "localPath");
        java.util.Objects.requireNonNull(remoteAbsolutePath, "remoteAbsolutePath");
    }
}
