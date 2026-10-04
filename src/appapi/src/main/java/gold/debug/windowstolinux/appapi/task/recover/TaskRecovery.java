package gold.debug.windowstolinux.appapi.task.recover;

import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/**
 * Reconciliation using original resources without replaying unknown effects. / 复用原资源且不重放未知副作用的结果核对。
 */
public interface TaskRecovery {

    /**
     * Checks original work and completes necessary cleanup without replay. / 核对原工作并完成必要清理，不重放操作。
     * @param previous previous retained state to reconcile / 待核对的先前保留状态
     * @param originalTask retained task whose effects require reconciliation / 需要核对副作用的保留任务
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws Exception if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    OperationResult<Void> reconcile(TaskSnapshot previous, TaskContext originalTask) throws Exception;
}
