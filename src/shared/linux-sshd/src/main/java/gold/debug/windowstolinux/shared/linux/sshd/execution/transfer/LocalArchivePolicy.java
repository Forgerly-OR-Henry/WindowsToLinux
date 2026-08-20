package gold.debug.windowstolinux.shared.linux.sshd.execution.transfer;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Objects;

/**
 * Provides the {@code LocalArchivePolicy} implementation.
 *
 * <p>提供 {@code LocalArchivePolicy} 实现。
 */
public final class LocalArchivePolicy {
    private LocalArchivePolicy() {
    }

    /**
     * Validates the input through {@code verify}.
     *
     * <p>通过 {@code verify} 验证输入。
     *
     * @param archive the {@code archive} value / {@code archive} 值
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public static void verify(SourceArchiveDescriptor archive) throws LinuxOperationException {
        Objects.requireNonNull(archive, "archive");
        if (!Files.isRegularFile(archive.localArchive())) {
            throw LinuxOperationException.localized("linux.error.localArchiveMissing",
                    "Local source archive does not exist");
        }
        try {
            if (Files.size(archive.localArchive()) != archive.byteCount()) {
                throw LinuxOperationException.localized("linux.error.localArchiveSizeMismatch",
                        "Local archive size does not match the static archive descriptor");
            }
        } catch (IOException exception) {
            throw LinuxOperationException.localized("linux.error.localArchiveReadFailed",
                    "Failed to read the local source archive size", exception);
        }
    }
}
