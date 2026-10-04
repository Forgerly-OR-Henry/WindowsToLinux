package gold.debug.windowstolinux.appapi.task.process;

import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/**
 * Task-owned process launch without exposing platform credentials. / 不暴露平台凭据的任务所属进程启动。
 */
public interface ProcessService {

    /**
     * Starts the owned lifecycle without implicitly beginning user business tasks. / 启动所属生命周期，不隐式执行用户业务任务。
     * @param spec registration or launch specification / 登记或启动规格
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    ManagedProcess start(ProcessSpec spec, TaskContext task) throws ApiException;

    /** Starts the first and only process for a previously persisted task-local identity. / 使用预先持久化的任务内身份首次且仅首次启动进程。
     * @param spec exact launch specification / 精确启动规格
     * @param task original executing task / 原执行任务
     * @param processId persisted process identity / 已持久化进程身份
     * @return original managed process / 原受管进程
     * @throws ApiException if this capability cannot prove first creation / 此能力无法证明首次创建时 */
    ManagedProcess start(ProcessSpec spec, TaskContext task, java.util.UUID processId) throws ApiException;

    /** Releases only the original task's physically verified process resources. / 仅释放原任务物理已核实的进程资源。
     * @param task original execution or recovery context / 原执行或恢复上下文
     * @throws ApiException if any process or ownership remains uncertain / 任一进程或归属仍不确定时 */
    void closeTaskResources(TaskContext task) throws ApiException;
}
