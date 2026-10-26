package gold.debug.windowstolinux.appapi.ui.intervention;

import java.util.UUID;

import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;

/** Host review sessions bound to the original task and exact Mod release. / 绑定原任务及精确 Mod 发布的宿主核验会话。 */
public interface UiInterventionService {
    /** Queues one decision from the original task worker with an interaction lease, without persisting waiting state.
     * Returning from the original callback withdraws the decision; deferral retains its lease and future.
     * / 从原任务工作线程排队一个持有交互占用的决定，不持久化等待状态；原回调返回即撤回决定，延期保留占用和未来值。
     * @param <T> accepted decision type / 已接受决定类型
     * @param task authentic original context / 真实原任务上下文
     * @param spec non-secret review presentation / 非秘密核验展示
     * @param form fresh EDT form factory / 新 EDT 表单工厂
     * @return original unresolved session / 原未决会话
     * @throws ApiException if ownership or task lifetime is invalid / 归属或任务寿命无效时 */
    <T> UiInterventionSession<T> request(TaskContext task, UiInterventionSpec spec,
                                         UiInterventionFormFactory<T> form) throws ApiException;

    /** Reports only an actually visible page for this exact original task. / 仅报告此精确原任务页面实际可见。
     * @param originalTask original task identity / 原任务标识
     * @param visible whether that task is actually visible / 此任务是否实际可见
     * @throws ApiException if the visible task belongs to another release or has ended / 可见任务属于其他发布或已结束时 */
    void visible(UUID originalTask, boolean visible) throws ApiException;

    /** Opens pending original questions after the Mod has navigated to that exact task. / Mod 导航至精确原任务后打开其待处理问题。
     * @param originalTask original task identity / 原任务标识
     * @throws ApiException if ownership or lifetime is invalid / 归属或寿命无效时 */
    void show(UUID originalTask) throws ApiException;

    /** Returns the actual owned ordinary-task handle for continued observation and cooperative cancellation on a worker.
     * This grants no task context, resource access, replacement execution or action approval.
     * / 在工作线程返回实际所属普通任务句柄，以继续观察及协作取消；不授予任务上下文、资源访问、替代执行或动作批准。
     * @param originalTask exact original task UUID / 精确原任务 UUID
     * @return actual original runtime handle / 实际原运行时句柄
     * @throws ApiException if ownership, activation, ordinary execution or capability composition is unavailable / 归属、启用、普通执行或能力装配不可用时 */
    default TaskHandle<?> observe(UUID originalTask) throws ApiException {
        throw new ApiException(new gold.debug.windowstolinux.appapi.task.result.Problem(
            gold.debug.windowstolinux.appapi.task.result.ProblemCode.UNAVAILABLE, "TASK_INTERVENTION_OBSERVATION_UNAVAILABLE",
            "Original task observation is not composed", java.util.Map.of(), java.util.Optional.empty()));
    }

    /** Reads transient request count and actual progress for one owned original task. / 读取一个所属原任务的临时请求数及真实进度。
     * @param originalTask original task identity / 原任务标识
     * @return display-only current observation / 当前纯展示观察
     * @throws ApiException if ownership or lifetime is invalid / 归属或寿命无效时 */
    UiInterventionSnapshot snapshot(UUID originalTask) throws ApiException;
}
