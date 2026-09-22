package gold.debug.windowstolinux.shared.linux.transfer;

/** Exact candidate and task identity, never a free-form remote path. / 精确候选项及任务身份，绝不是自由远端路径。
 * @param workspace bounded candidate / 有界候选项
 * @param taskId opaque task identity / 不透明任务身份
 */
public record RemoteTaskCandidate(RemoteWorkspace workspace, String taskId) {
    /** Validates the complete transport identity. / 验证完整传输身份。
     * @param workspace bounded candidate / 有界候选项
     * @param taskId opaque task identity / 不透明任务身份
     */
    public RemoteTaskCandidate {
        java.util.Objects.requireNonNull(workspace);
        DeploymentRemoteTaskScope.requireTask(taskId);
    }
}
