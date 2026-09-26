package gold.debug.windowstolinux.appapi.linux;

import java.nio.file.Path;

/**
 * Bounded file transfer with an explicit remote destination. / 具有明确远端路径及容量限制的文件传输。
 * @param localPath local transfer endpoint / 传输的本地端路径
 * @param remoteAbsolutePath absolute path on the selected remote server / 选定远端服务器上的绝对路径
 * @param maxBytes nonnegative byte budget / 非负字节预算
 */
public record TransferRequest(Path localPath, String remoteAbsolutePath, long maxBytes) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param localPath local transfer endpoint / 传输的本地端路径
     * @param remoteAbsolutePath absolute path on the selected remote server / 选定远端服务器上的绝对路径
     * @param maxBytes nonnegative byte budget / 非负字节预算
     */
    public TransferRequest {
        java.util.Objects.requireNonNull(localPath, "localPath");
        java.util.Objects.requireNonNull(remoteAbsolutePath, "remoteAbsolutePath");
        if (maxBytes < 0)
            throw new IllegalArgumentException("maxBytes is below its minimum");
    }
}
