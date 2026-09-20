package gold.debug.windowstolinux.shared.linux.protocol.backup;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;

import java.io.OutputStream;

/** Fixed remote creation, verified streaming and exact cleanup for managed backup artifacts. / 受管备份制品的固定远端创建、校验流式传输与精确清理。 */
public interface RemoteBackupArtifactPort {
    /** Blocks new managed tasks and drains active tasks for at most 30 seconds. */
    default void beginMaintenance(gold.debug.windowstolinux.shared.model.managed.ManagedApplication app, String token)
            throws LinuxOperationException { throw new UnsupportedOperationException("application maintenance is unavailable"); }

    /** Releases only the admission marker belonging to this operation. */
    default void endMaintenance(gold.debug.windowstolinux.shared.model.managed.ManagedApplication app, String token)
            throws LinuxOperationException { throw new UnsupportedOperationException("application maintenance is unavailable"); }
    /** Creates exactly one artifact from its reviewed managed identity. / 从经审阅受管身份创建一个精确制品。 */
    RemoteBackupArtifact createBackupArtifact(RemoteBackupArtifactRequest request) throws LinuxOperationException;

    /** Streams one exact artifact while independently verifying size and digest. / 流式回读一个精确制品并独立校验长度和摘要。 */
    void copyBackupArtifact(RemoteBackupArtifact artifact, OutputStream destination) throws LinuxOperationException;

    /** Removes only the exact helper-owned operation directory. / 仅删除 helper 持有的精确操作目录。 */
    RemoteStepResult discardBackupOperation(String operationId) throws LinuxOperationException;
}
