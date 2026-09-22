package gold.debug.windowstolinux.shared.linux.protocol.restore;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;

/**
 * Narrow Linux contract for staging or discarding one isolated restore file candidate. / 暂存或丢弃单个隔离恢复文件候选的 Linux 窄契约。
 */
public interface RemoteRestoreFilePort {
    /**
     * Stages and independently reads back every declared member. / 暂存并独立回读每个已声明成员。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved remote restore staging evidence / 构造或解析得到的远端恢复暂存证据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    RemoteRestoreStagingEvidence stageRestoreFiles(RemoteRestoreStagingRequest request) throws LinuxOperationException;

    /**
     * Discards the exact digest-derived candidate workspace. / 丢弃精确的摘要派生候选工作区。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved remote step result / 构造或解析得到的远端步骤结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    RemoteStepResult discardRestoreFiles(RemoteRestoreStagingRequest request) throws LinuxOperationException;
}
