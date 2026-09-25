package gold.debug.windowstolinux.api.source;

import gold.debug.windowstolinux.api.task.ApiException;
import gold.debug.windowstolinux.api.task.TaskContext;

/**
 * User-selected bounded reads and owned immutable snapshots. / 用户选定来源的有界读取及受管不可变快照。
 */
public interface SourceService {

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
