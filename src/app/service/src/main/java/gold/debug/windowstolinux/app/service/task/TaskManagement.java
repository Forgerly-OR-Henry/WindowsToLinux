package gold.debug.windowstolinux.app.service.task;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.result.ApiException;

/** Host task inventory and explicit original-task actions. / 宿主任务清单及明确原任务操作。 */
public interface TaskManagement {
    /** Lists retained tasks and the actions currently available for each. / 列出保留任务及其当前可用操作。
     * @return immutable current inventory / 不可变当前清单
     * @throws ApiException if the desktop is locked or unavailable / 桌面锁定或不可用时 */
    List<Entry> list() throws ApiException;

    /** Requests cooperative cancellation without declaring resource cleanup complete. / 请求协作取消，不宣称资源已经清理完成。
     * @param id original task identity / 原任务身份
     * @return actual state after the cancellation request / 请求取消后的实际状态
     * @throws ApiException if cancellation is unavailable or cannot be persisted / 取消不可用或无法持久化时 */
    TaskSnapshot requestCancel(UUID id) throws ApiException;

    /** Continues a live original user-wait callback, which rechecks objects and approval. / 继续存活原等待回调，由其重新核对对象与审批。
     * @param id original task / 原任务
     * @return resumed observation / 恢复观察
     * @throws ApiException if the original live wait or access is unavailable / 原存活等待或访问不可用时
     */
    TaskSnapshot continueWaiting(UUID id) throws ApiException;

    /** Reconciles the original task without submitting replacement business work. / 核对原任务，不提交替代业务。
     * @param id original task identity / 原任务身份
     * @param actionReconciled explicit confirmation that an earlier rescue command no longer runs / 明确确认先前救援命令已不再运行
     * @return completion of this reconciliation attempt / 本次核对尝试的完成状态
     * @throws ApiException if authorization, confirmation or original recovery is unavailable / 授权、确认或原恢复不可用时 */
    CompletionStage<TaskSnapshot> reconcile(UUID id, boolean actionReconciled) throws ApiException;

    /** Current host observations, never an authorization token. / 当前宿主观察，不作为授权凭据。
     * @param snapshot original task state and outstanding resources / 原任务状态及未释放资源
     * @param cancellable whether cooperative cancellation can currently be requested / 当前是否可请求协作取消
     * @param recovery available reconciliation path / 可用核对路径 */
    record Entry(TaskSnapshot snapshot, boolean cancellable, RecoveryState recovery) {
    }

    /** Distinguishes execution, missing original code and metadata-only completion. / 区分执行中、缺少原代码和仅元数据收尾。 */
    enum RecoveryState {
        /** Original callback or another reconciliation is still active. / 原回调或另一核对仍活动中。 */
        NOT_READY,
        /** The original owner, version and task type have no registered recovery. / 原所有者、版本及任务类型未登记恢复。 */
        UNAVAILABLE,
        /** The original registered recovery can inspect retained work. / 原登记恢复可检查保留工作。 */
        RECONCILE,
        /** Only the original known result's platform metadata remains. / 仅剩原已知结果的平台元数据。 */
        ARCHIVE_ONLY,
        /** A rescue command needs explicit human reconciliation before cleanup. / 救援命令须先经明确人工核对再清理。 */
        CONFIRM_RESCUE
    }
}
