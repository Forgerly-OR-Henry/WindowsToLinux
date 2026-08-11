package gold.debug.windowstolinux.shared.linux.protocol;

import gold.debug.windowstolinux.shared.linux.build.RemoteBuildResult;
import gold.debug.windowstolinux.shared.linux.connection.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;

/**
 * Typed managed-release protocol; no arbitrary privileged command is exposed.
 *
 * <p>类型化受管版本协议；不公开任意高权限命令。
 */
public interface LinuxReleaseOperations {
    /**
     * Performs the {@code snapshot} operation.
     *
     * <p>执行 {@code snapshot} 操作。
     *
     * @param application the {@code application} value / {@code application} 值
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     */
    ReleaseSnapshot snapshot(ManagedApplication application) throws LinuxOperationException;

    /**
     * Performs the {@code publish} operation.
     *
     * <p>执行 {@code publish} 操作。
     *
     * @param application the {@code application} value / {@code application} 值
     * @param workspace the {@code workspace} value / {@code workspace} 值
     * @param build the {@code build} value / {@code build} 值
     * @param snapshot the {@code snapshot} value / {@code snapshot} 值
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     */
    RemoteStepResult publish(ManagedApplication application, RemoteWorkspace workspace, RemoteBuildResult build,
                             ReleaseSnapshot snapshot) throws LinuxOperationException;

    /**
     * Performs the {@code retainRecentSuccessfulReleases} operation.
     *
     * <p>执行 {@code retainRecentSuccessfulReleases} 操作。
     *
     * @param application the {@code application} value / {@code application} 值
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     */
    RemoteStepResult retainRecentSuccessfulReleases(ManagedApplication application) throws LinuxOperationException;

    /**
     * Performs the {@code rollback} operation.
     *
     * <p>执行 {@code rollback} 操作。
     *
     * @param application the {@code application} value / {@code application} 值
     * @param snapshot the {@code snapshot} value / {@code snapshot} 值
     * @param build the {@code build} value / {@code build} 值
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     */
    RemoteStepResult rollback(ManagedApplication application, ReleaseSnapshot snapshot, RemoteBuildResult build)
            throws LinuxOperationException;
}
