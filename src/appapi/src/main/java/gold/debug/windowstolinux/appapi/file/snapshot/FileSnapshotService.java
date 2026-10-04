package gold.debug.windowstolinux.appapi.file.snapshot;

import gold.debug.windowstolinux.appapi.file.snapshot.FileSnapshot;
import gold.debug.windowstolinux.appapi.file.snapshot.SnapshotRequest;
import gold.debug.windowstolinux.appapi.file.source.FileSelection;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Original-task immutable snapshot capture. / 原任务不可变快照创建。 */
public interface FileSnapshotService {

    /**
     * Captures the current owned state or immutable selected source. / 取得当前所属状态或选定来源的不可变快照。
     * @param selection opaque user-authorized source selection / 不透明的用户授权来源选择
     * @param request immutable capability request / 不可变的能力请求
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @return task-owned immutable snapshot requiring explicit close / 需要明确关闭的任务所属不可变快照
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    FileSnapshot snapshot(FileSelection selection, SnapshotRequest request, TaskContext task) throws ApiException;
}
