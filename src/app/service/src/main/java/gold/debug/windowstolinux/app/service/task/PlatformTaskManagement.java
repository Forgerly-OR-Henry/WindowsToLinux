package gold.debug.windowstolinux.app.service.task;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.TaskState;

import java.util.*;
import java.util.concurrent.CompletionStage;

import gold.debug.windowstolinux.app.service.task.TaskManagement;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;

/** Routes explicit desktop actions to original tasks and the host rescue confirmation boundary. / 将明确桌面操作路由到原任务及宿主救援确认边界。 */
public final class PlatformTaskManagement implements TaskManagement {
    /** Original central task and resource owner. / 原中央任务及资源所有者。 */
    private final PlatformTaskRuntime tasks;

    /** Current desktop access boundary. / 当前桌面访问边界。 */
    private final DesktopSecurityService security;

    /** Host-injected rescue reconciliation after explicit confirmation. / 明确确认后由宿主注入的救援核对。 */
    private final RescueReconciliation rescue;

    /** Host-owned session cleanup after the original task has ended. / 原任务结束后的宿主所属会话清理。 */
    private final RecoveryCleanup cleanup;

    /** Binds existing platform owners without registering new recovery implementations. / 绑定已有平台所有者，不登记新的恢复实现。
     * @param tasks original central runtime / 原中央运行时
     * @param security current desktop authorization / 当前桌面授权
     * @param rescue original rescue-service action after human confirmation / 人工确认后的原救援服务操作
     * @param cleanup original Mod session cleanup after task completion / 任务完成后的原 Mod 会话清理 */
    public PlatformTaskManagement(PlatformTaskRuntime tasks, DesktopSecurityService security,
                                  RescueReconciliation rescue, RecoveryCleanup cleanup) {
        this.tasks = Objects.requireNonNull(tasks);
        this.security = Objects.requireNonNull(security);
        this.rescue = Objects.requireNonNull(rescue);
        this.cleanup = Objects.requireNonNull(cleanup);
    }

    /** Reads current action availability without changing task state. / 读取当前可用操作，不改变任务状态。
     * @return original tasks in stable admission order / 按稳定准入顺序排列的原任务
     * @throws ApiException if interactive access is unavailable / 交互访问不可用时 */
    @Override
    public List<Entry> list() throws ApiException {
        requireAccess();
        return tasks.management().stream()
            .map(entry -> rescue(entry.snapshot()) && entry.recovery() == RecoveryState.RECONCILE
                ? new Entry(entry.snapshot(), entry.cancellable(), RecoveryState.CONFIRM_RESCUE)
                : entry)
            .sorted(Comparator.comparing((Entry entry) -> entry.snapshot().startedAt())
                .thenComparing(entry -> entry.snapshot().id()))
            .toList();
    }

    /** Requests cancellation through the original handle and its actual current state. / 通过原句柄及其实际当前状态请求取消。
     * @param id original task / 原任务
     * @return observed state after requesting cancellation / 请求取消后的观察状态
     * @throws ApiException if access or cooperative cancellation is unavailable / 访问或协作取消不可用时 */
    @Override
    public TaskSnapshot requestCancel(UUID id) throws ApiException {
        requireAccess();
        return tasks.requestCancellation(id);
    }

    /** Preserves desktop authorization and original-callback identity. / 保留桌面授权及原回调身份。
     * @param id original task / 原任务
     * @return resumed original observation / 恢复后的原观察
     * @throws ApiException if access or live waiting is unavailable / 访问或存活等待不可用时
     */
    @Override
    public TaskSnapshot continueWaiting(UUID id) throws ApiException {
        requireAccess();
        return tasks.continueWaiting(id);
    }

    /** Preserves rescue confirmation while metadata-only completion never invokes business recovery. / 保留救援确认，且仅元数据收尾绝不调用业务恢复。
     * @param id original task / 原任务
     * @param actionReconciled whether the old rescue command was explicitly checked / 是否已明确核对旧救援命令
     * @return original reconciliation completion / 原核对完成状态
     * @throws ApiException if access, original task or required confirmation is absent / 缺少访问、原任务或必要确认时 */
    @Override
    public CompletionStage<TaskSnapshot> reconcile(UUID id, boolean actionReconciled) throws ApiException {
        var entry = list().stream().filter(item -> item.snapshot().id().equals(id)).findFirst()
            .orElseThrow(() -> failure(ProblemCode.NOT_FOUND, "TASK_MISSING", "Task does not exist"));
        if (rescue(entry.snapshot()) && entry.recovery() != RecoveryState.ARCHIVE_ONLY) {
            if (!actionReconciled)
                throw failure(ProblemCode.ACCESS_DENIED, "RESCUE_RECONCILIATION_REQUIRED",
                    "Confirm that the original rescue command is no longer running");
            return completed(rescue.reconcile(id));
        }
        return completed(tasks.reconcile(id));
    }

    /** Releases drained sessions outside the task runtime monitor after known completion. / 在已知完成后于任务运行时监视器之外释放排空会话。
     * @param original original task reconciliation / 原任务核对
     * @return task observation after host cleanup / 宿主清理后的任务观察
     */
    private CompletionStage<TaskSnapshot> completed(CompletionStage<TaskSnapshot> original) {
        return original.thenApply(snapshot -> {
            if (snapshot.state() == TaskState.ENDED)
                try {
                    cleanup.release();
                } catch (ApiException failure) {
                    throw new java.util.concurrent.CompletionException(failure);
                }
            return snapshot;
        });
    }

    /** Matches only the fixed host rescue identity, never a Mod's similarly named type. / 仅匹配固定宿主救援身份，不匹配 Mod 的同名类型。
     * @param snapshot original task / 原任务
     * @return whether this is the platform rescue protocol / 是否为平台救援协议 */
    private static boolean rescue(TaskSnapshot snapshot) {
        return snapshot.ownerId().equals(PlatformTaskAdmission.PLATFORM_OWNER)
            && snapshot.type().equals(PlatformTaskAdmission.RESCUE_TYPE);
    }

    /** Requires a worker and unlocked desktop before inventory or actions. / 在清单或操作前要求工作线程及已解锁桌面。
     * @throws ApiException if interactive authorization is unavailable / 交互授权不可用时 */
    private void requireAccess() throws ApiException {
        if (javax.swing.SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Task management requires a worker");
        try {
            security.requireUnlocked();
        } catch (gold.debug.windowstolinux.app.service.error.ApplicationServiceException denied) {
            throw failure(ProblemCode.ACCESS_DENIED, "TASK_SECURITY_LOCKED", "Unlock the desktop to manage tasks");
        }
    }

    /** Creates a safe rejection without underlying persistence or credential details. / 创建不带底层持久化或凭据详情的安全拒绝。
     * @param code public category / 公开类别
     * @param event stable event / 稳定事件
     * @param message safe description / 安全描述
     * @return public failure / 公开失败 */
    private static ApiException failure(ProblemCode code, String event, String message) {
        return new ApiException(new Problem(code, event, message, Map.of(), Optional.empty()));
    }

    /** Original host rescue route; startup supplies the already-owned service. / 原宿主救援路由，由启动方提供已持有服务。 */
    @FunctionalInterface
    public interface RescueReconciliation {
        /** Reconciles after the task page has obtained explicit human confirmation. / 任务页取得明确人工确认后核对。
         * @param id original rescue task / 原救援任务
         * @return actual cleanup attempt / 实际清理尝试
         * @throws ApiException if the original service cannot reconcile / 原服务无法核对时 */
        CompletionStage<TaskSnapshot> reconcile(UUID id) throws ApiException;
    }

    /** Existing host graph cleanup without new business execution. / 不进行新业务执行的已有宿主图清理。 */
    @FunctionalInterface
    public interface RecoveryCleanup {
        /** Releases only completely drained original groups. / 仅释放完全排空的原组。
         * @throws ApiException if actual cleanup remains / 实际清理仍残留时 */
        void release() throws ApiException;
    }
}
