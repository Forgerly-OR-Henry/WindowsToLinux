package gold.debug.windowstolinux.shared.linux.transfer;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;

/**
 * Bounded source transfer and candidate cleanup contract.
 *
 * <p>受限源码传输与候选项清理契约。
 */
public interface LinuxTransferOperations {
    /**
     * Performs the {@code uploadSource} operation.
     *
     * <p>执行 {@code uploadSource} 操作。
     *
     * @param archive the {@code archive} value / {@code archive} 值
     * @param workspace the {@code workspace} value / {@code workspace} 值
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     */
    UploadReceipt uploadSource(SourceArchiveDescriptor archive, RemoteWorkspace workspace) throws LinuxOperationException;

    /**
     * Performs the {@code cleanupCandidate} operation.
     *
     * <p>执行 {@code cleanupCandidate} 操作。
     *
     * @param workspace the {@code workspace} value / {@code workspace} 值
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     */
    default RemoteStepResult cleanupCandidate(RemoteWorkspace workspace) throws LinuxOperationException {
        return new RemoteStepResult(true, false, "Test session did not retain a remote candidate directory");
    }
}
