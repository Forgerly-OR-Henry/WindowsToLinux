package gold.debug.windowstolinux.shared.linux.sshd.execution.transfer;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Objects;

/**
 * Checks local archive identity, size and readability before remote transfer.
 * <p>在远程传输前检查本地归档的身份、大小及可读性。
 */
public final class LocalArchivePolicy {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private LocalArchivePolicy() {
    }

    /**
     * Validates the input through {@code verify}.
     *
     *  <p>通过 {@code verify} 验证输入。
     *
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static void verify(SourceArchiveDescriptor archive) throws LinuxOperationException {
        Objects.requireNonNull(archive, "archive");
        if (!Files.isRegularFile(archive.localArchive())) {
            throw LinuxOperationException.create(LinuxOperationFailureType.LOCAL_ARCHIVE_MISSING,
                    "Local source archive does not exist");
        }
        try {
            if (Files.size(archive.localArchive()) != archive.byteCount()) {
                throw LinuxOperationException.create(LinuxOperationFailureType.LOCAL_ARCHIVE_SIZE_MISMATCH,
                        "Local archive size does not match the static archive descriptor");
            }
        } catch (IOException exception) {
            throw LinuxOperationException.create(LinuxOperationFailureType.LOCAL_ARCHIVE_READ_FAILED,
                    "Failed to read the local source archive size", exception);
        }
    }
}
