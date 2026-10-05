package gold.debug.windowstolinux.mod.deployment.ui.feedback;

import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationUsage;
import gold.debug.windowstolinux.mod.deployment.ui.delivery.DeploymentDeliveryPanel;

/** Reads exact delivery only after the original host completion. / 仅在原宿主完成后读取精确交付。 */
final class DeploymentTaskDelivery implements AutoCloseable {
    /** Original UUID-bound read admission. / 原UUID绑定的读取准入。 */
    private final DeploymentTaskFeedback.DeliveryLookup lookup;

    /** Original page-owned worker. / 原页面所属工作线程。 */
    private final Executor worker;

    /** Actual public delivery actions and values. / 实际公开交付动作及值。 */
    private final DeploymentDeliveryPanel panel = new DeploymentDeliveryPanel();

    /** Original accepted publication handle. / 原已准入发布句柄。 */
    private TaskHandle<?> original;

    /** Only this short read may be cancelled by view revocation. / 仅此短读取可被视图撤销取消。 */
    private TaskHandle<Optional<DeploymentApplicationUsage>> query;

    /** View revocation is also checked by the original worker. / 原工作线程也核对视图撤销。 */
    private volatile long generation;

    /** Actual page availability, independent of publication. / 独立于发布的实际页面可用性。 */
    private volatile boolean available;

    /** Actual successful host completion. / 实际成功宿主完成。 */
    private boolean succeeded;

    /** One bounded query is in flight. / 一项有界查询在途。 */
    private boolean loading;

    /** Closed page never reads or exposes delivery again. / 已关闭页面不再读取或暴露交付。 */
    private boolean closed;

    /** Binds the concrete original read boundary. / 绑定具体原读取边界。
     * @param lookup original UUID query admission / 原UUID查询准入
     * @param worker original page worker / 原页面工作线程 */
    DeploymentTaskDelivery(DeploymentTaskFeedback.DeliveryLookup lookup, Executor worker) {
        this.lookup = java.util.Objects.requireNonNull(lookup);
        this.worker = java.util.Objects.requireNonNull(worker);
    }

    /** Returns the actual action panel. / 返回实际动作面板。
     * @return original delivery panel / 原交付面板 */
    DeploymentDeliveryPanel component() {
        return panel;
    }

    /** Applies the actual host environment. / 应用实际宿主环境。
     * @param environment original host environment / 原宿主环境 */
    void environment(UiEnvironment environment) {
        panel.environment(environment);
    }

    /** Revokes stale reads while retaining only the original eligible handle. / 撤销过期读取，仅保留原符合条件句柄。
     * @param unavailable actual view revocation / 实际视图撤销 */
    void gate(boolean unavailable) {
        revoke();
        available = !closed && !unavailable;
        panel.enabled(available);
        if (available) load();
    }

    /** Replaces the original publication without borrowing any old delivery. / 替换原发布，不借用任何旧交付。
     * @param handle original accepted publication / 原已准入发布 */
    void watch(TaskHandle<?> handle) {
        revoke();
        original = handle;
        succeeded = false;
    }

    /** Requires the actual host outcome and terminal snapshot before any read. / 任何读取前均要求实际宿主结果及终态快照。
     * @param handle exact original handle / 精确原句柄
     * @param result actual host result / 实际宿主结果
     * @param failure actual completion exception / 实际完成异常 */
    void completed(TaskHandle<?> handle, OperationResult<?> result, Throwable failure) {
        if (closed || original != handle) return;
        succeeded = failure == null && result != null && result.outcome() == TaskResult.SUCCEEDED
            && handle.snapshot().endedAt().isPresent();
        if (succeeded && available) load();
    }

    /** Starts at most one original short query and performs no storage on the EDT. / 最多启动一项原短查询，不在EDT访问存储。 */
    private void load() {
        if (closed || !available || !succeeded || loading || original == null) return;
        loading = true;
        var accepted = original;
        long view = generation;
        try {
            worker.execute(() -> read(accepted, view));
        } catch (RejectedExecutionException rejected) {
            loading = false;
        }
    }

    /** Admits only the exact original task identity and rejects revoked callbacks. / 仅准入精确原任务身份并拒绝撤销回调。
     * @param accepted original host-completed publication / 原宿主已完成发布
     * @param view captured page generation / 捕获页面代次 */
    private void read(TaskHandle<?> accepted, long view) {
        if (!available || generation != view) return;
        try {
            var admitted = lookup.read(accepted.id());
            if (!available || generation != view) {
                cancel(admitted);
                return;
            }
            SwingUtilities.invokeLater(() -> admitted(accepted, admitted, view));
        } catch (Exception unavailable) {
            SwingUtilities.invokeLater(() -> {
                if (!closed && generation == view) loading = false;
            });
        }
    }

    /** Registers only a still-current read on the owning EDT. / 仅在所属EDT登记仍为当前的读取。
     * @param accepted original completed publication / 原已完成发布
     * @param admitted exact original short query / 精确原短查询
     * @param view captured page generation / 捕获页面代次 */
    private void admitted(TaskHandle<?> accepted, TaskHandle<Optional<DeploymentApplicationUsage>> admitted, long view) {
        if (closed || !available || generation != view || original != accepted) {
            cancelLater(admitted);
            return;
        }
        query = admitted;
        admitted.completion().whenComplete((value, failure) -> SwingUtilities.invokeLater(
            () -> observed(accepted, admitted, view, value, failure)));
    }

    /** Displays only a successful exact query with a present eligible publication. / 仅展示成功精确查询中存在的符合条件发布。
     * @param accepted original publication handle / 原发布句柄
     * @param admitted actual short query / 实际短查询
     * @param view captured page generation / 捕获页面代次
     * @param value actual query outcome / 实际查询结果
     * @param failure actual query exception / 实际查询异常 */
    private void observed(TaskHandle<?> accepted, TaskHandle<Optional<DeploymentApplicationUsage>> admitted,
                          long view, OperationResult<Optional<DeploymentApplicationUsage>> value, Throwable failure) {
        if (closed || !available || generation != view || original != accepted || query != admitted) return;
        loading = false;
        query = null;
        if (failure == null && value != null && value.outcome() == TaskResult.SUCCEEDED
            && admitted.snapshot().endedAt().isPresent()) value.value().flatMap(item -> item).ifPresent(panel::show);
    }

    /** Clears previous actionable facts and cancels only an admitted short read. / 清除旧可操作事实，仅取消已准入短读取。 */
    private void revoke() {
        generation++;
        loading = false;
        panel.clear();
        var admitted = query;
        query = null;
        cancelLater(admitted);
    }

    /** Dispatches cancellation only for the view's bounded read. / 仅为视图有界读取派发取消。
     * @param admitted exact owned read or absence / 精确所属读取或无值 */
    private void cancelLater(TaskHandle<?> admitted) {
        if (admitted == null || admitted.snapshot().endedAt().isPresent()) return;
        try {
            worker.execute(() -> cancel(admitted));
        } catch (RejectedExecutionException ignored) { /* The bounded read still owns its normal cleanup. */ }
    }

    /** Requests cancellation without fabricating read completion. / 请求取消，不伪造读取完成。
     * @param admitted exact owned read / 精确所属读取 */
    private static void cancel(TaskHandle<?> admitted) {
        try {
            admitted.requestCancel();
        } catch (Exception ignored) { /* A refused cancellation does not change the original outcome. */ }
    }

    /** Closes only this delivery view and its short read. / 仅关闭此交付视图及其短读取。 */
    @Override
    public void close() {
        closed = true;
        available = false;
        revoke();
        original = null;
        panel.enabled(false);
    }
}
