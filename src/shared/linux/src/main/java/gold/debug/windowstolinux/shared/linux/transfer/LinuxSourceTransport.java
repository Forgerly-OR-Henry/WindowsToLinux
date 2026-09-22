package gold.debug.windowstolinux.shared.linux.transfer;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;

/**
 * Bounded source transfer and candidate cleanup contract.
 *
 *  <p>受限源码传输与候选项清理契约。
 */
public interface LinuxSourceTransport {
    /**
     * Uploads source identity or content read by the operation.
     * <p>上传操作读取的源身份或内容。
     *
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param maxWorkspaceBytes max workspace bytes / 最大工作区字节
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    SourceUploadResult uploadSource(SourceArchiveDescriptor archive, RemoteWorkspace workspace, long maxWorkspaceBytes)
            throws LinuxOperationException;

    /**
     * Cleans up candidate.
     * <p>清理候选。
     *
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    default RemoteStepResult cleanupCandidate(RemoteWorkspace workspace) throws LinuxOperationException {
        return new RemoteStepResult(true, false, "Test session did not retain a remote candidate directory");
    }

    /** Queries only a candidate with a root-owned task binding; this call cannot execute writes. / 仅查询绑定 root 所有任务身份的候选项，不能写入。
     * @param candidate exact task and candidate / 精确任务及候选项
     * @return bounded presence and process facts / 有界存在及进程事实
     * @throws LinuxOperationException if remote observation fails / 远端观测失败时
     */
    default java.util.Map<String, String> inspectTaskCandidate(RemoteTaskCandidate candidate)
            throws LinuxOperationException {
        throw new UnsupportedOperationException("task candidate inspection not supported");
    }

    /** Cancels owned build processes and cleans only the exact task candidate. / 取消所属构建进程并仅清理精确任务候选项。
     * @param candidate exact task and candidate / 精确任务及候选项
     * @return verified cleanup result / 已验证清理结果
     * @throws LinuxOperationException if cleanup cannot be established / 无法确认清理时
     */
    default RemoteStepResult cleanupTaskCandidate(RemoteTaskCandidate candidate) throws LinuxOperationException {
        throw new UnsupportedOperationException("task candidate cleanup not supported");
    }
}
