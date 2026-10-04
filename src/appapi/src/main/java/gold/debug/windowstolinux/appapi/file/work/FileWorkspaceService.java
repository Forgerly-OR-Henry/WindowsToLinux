package gold.debug.windowstolinux.appapi.file.work;

import gold.debug.windowstolinux.appapi.file.work.FileWorkspace;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Original-task writable staging and retained evidence. / 原任务可写暂存与保留依据。 */
public interface FileWorkspaceService {

    /** Creates an empty task-owned workspace for source staging and generated material. / 为来源暂存及生成素材创建空的任务所属工作目录。
     * @param task authentic executing task; recovery cannot create new work / 真实执行任务，恢复不得创建新工作
     * @return owned writable handle requiring explicit close / 需要明确关闭的所属可写句柄
     * @throws ApiException if admission, creation or cleanup fails / 准入、创建或清理失败时 */
    FileWorkspace workspace(TaskContext task) throws ApiException;

    /** Creates one workspace using its previously persisted task-local identity. / 以预先持久化的任务内身份首次创建一个工作目录。
     * @param task original executing task / 原执行任务
     * @param workspaceId original preselected identity / 原预选身份
     * @return original writable workspace / 原可写工作目录
     * @throws ApiException if identity has been used or creation cannot be confirmed / 身份已使用或创建无法确认时 */
    FileWorkspace workspace(TaskContext task, java.util.UUID workspaceId) throws ApiException;

    /** Copies bounded evidence from the original workspace without granting writable access. / 从原工作目录复制有界依据，不授予写权限。
     * @param task original task or recovery context / 原任务或恢复上下文
     * @param workspaceId original workspace identity / 原工作目录身份
     * @param name single sibling name without separators, dot or parent traversal / 不含分隔符、点或父级跳转的单个同级名称
     * @param maxBytes maximum copied bytes / 最大复制字节数
     * @return copied bytes or absence / 独立字节或不存在
     * @throws ApiException if the original identity or physical file cannot be verified / 原身份或物理文件无法核实时 */
    java.util.Optional<byte[]> readWorkspaceEvidence(TaskContext task, java.util.UUID workspaceId, String name,
                                                     int maxBytes) throws ApiException;
}
