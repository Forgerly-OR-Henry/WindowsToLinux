package gold.debug.windowstolinux.shared.linux.protocol.restore;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;

/** Narrow Linux contract for staging or discarding one isolated restore file candidate. / 暂存或丢弃单个隔离恢复文件候选的 Linux 窄契约。 */
public interface RemoteRestoreFilePort {
    /** Stages and independently reads back every declared member. / 暂存并独立回读每个已声明成员。 */
    RemoteRestoreStagingEvidence stageRestoreFiles(RemoteRestoreStagingRequest request)
            throws LinuxOperationException;

    /** Discards the exact digest-derived candidate workspace. / 丢弃精确的摘要派生候选工作区。 */
    RemoteStepResult discardRestoreFiles(RemoteRestoreStagingRequest request) throws LinuxOperationException;
}
