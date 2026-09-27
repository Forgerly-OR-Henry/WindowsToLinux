package gold.debug.windowstolinux.appapi.source;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.TaskContext;

/**
 * User-selected bounded reads and owned immutable snapshots. / 用户选定来源的有界读取及受管不可变快照。
 */
public interface SourceService {

    /** Returns the original selected directory's canonical absolute file URI as metadata. / 将原选定目录的规范绝对文件URI作为元数据返回。
     * @param selection original user-approved directory, never a workspace selection / 原用户批准的目录，不得为工作目录选择
     * @param task original active task / 原活动任务
     * @return original location metadata, not a reusable authorization / 原位置元数据，不是可重复使用的授权
     * @throws ApiException if ownership or physical root identity cannot be confirmed / 无法确认归属或物理根身份时 */
    java.net.URI location(SourceSelection selection, TaskContext task) throws ApiException;

    /** Creates an empty task-owned workspace for source staging and generated material. / 为来源暂存及生成素材创建空的任务所属工作目录。
     * @param task authentic executing task; recovery cannot create new work / 真实执行任务，恢复不得创建新工作
     * @return owned writable handle requiring explicit close / 需要明确关闭的所属可写句柄
     * @throws ApiException if admission, creation or cleanup fails / 准入、创建或清理失败时 */
    SourceWorkspace workspace(TaskContext task) throws ApiException;

    /** Creates one workspace using its previously persisted task-local identity. / 以预先持久化的任务内身份首次创建一个工作目录。
     * @param task original executing task / 原执行任务
     * @param workspaceId original preselected identity / 原预选身份
     * @return original writable workspace / 原可写工作目录
     * @throws ApiException if identity has been used or creation cannot be confirmed / 身份已使用或创建无法确认时 */
    SourceWorkspace workspace(TaskContext task, java.util.UUID workspaceId) throws ApiException;

    /** Copies bounded evidence from the original workspace without granting writable access. / 从原工作目录复制有界依据，不授予写权限。
     * @param task original task or recovery context / 原任务或恢复上下文
     * @param workspaceId original workspace identity / 原工作目录身份
     * @param name one simple file name / 单个简单文件名
     * @param maxBytes maximum copied bytes / 最大复制字节数
     * @return copied bytes or absence / 独立字节或不存在
     * @throws ApiException if the original identity or physical file cannot be verified / 原身份或物理文件无法核实时 */
    java.util.Optional<byte[]> readWorkspaceEvidence(TaskContext task, java.util.UUID workspaceId, String name,
            int maxBytes) throws ApiException;

    /** Releases only physically verified work owned by this original task. / 仅释放此原任务物理已核实的工作素材。
     * @param task original task or recovery context / 原任务或恢复上下文
     * @throws ApiException if physical cleanup or ownership remains uncertain / 物理清理或归属仍不确定时 */
    void closeTaskResources(TaskContext task) throws ApiException;

    /** Lists one complete bounded directory without recursively reading its children. / 列出一个完整有界目录，不递归读取其子项。
     * @param selection original user-authorized selection / 原用户授权选择
     * @param relativeDirectory slash-separated relative directory, empty for the selected root / 斜线分隔的相对目录，空串表示所选根
     * @param maxEntries maximum immediate children from one to one hundred thousand / 一到十万个直接子项的上限
     * @param task original active task / 原活动任务
     * @return immutable entries sorted by original relative path / 按原相对路径排序的不可变子项
     * @throws ApiException if authorization, boundaries, capacity or actual reading fails / 授权、边界、容量或实际读取失败时 */
    java.util.List<SourceDirectoryEntry> list(SourceSelection selection, String relativeDirectory, int maxEntries,
            TaskContext task) throws ApiException;

    /**
     * Obtains an explicit user selection bound to the current task. / 取得绑定当前任务的明确用户选择。
     * @param title human-readable task title / 可读的任务标题
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    SourceSelection selectDirectory(String title, TaskContext task) throws ApiException;

    /**
     * Reads copied bytes within the selected root and requested limit. / 在选定根目录及请求限制内读取独立字节。
     * @param selection opaque user-authorized source selection / 不透明的用户授权来源选择
     * @param request immutable capability request / 不可变的能力请求
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    SourceFile read(SourceSelection selection, SourceReadRequest request, TaskContext task) throws ApiException;

    /**
     * Captures the current owned state or immutable selected source. / 取得当前所属状态或选定来源的不可变快照。
     * @param selection opaque user-authorized source selection / 不透明的用户授权来源选择
     * @param request immutable capability request / 不可变的能力请求
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    SourceSnapshot snapshot(SourceSelection selection, SnapshotRequest request, TaskContext task) throws ApiException;
}
