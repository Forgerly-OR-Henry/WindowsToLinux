package gold.debug.windowstolinux.mod.deployment.ui;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskHandle;
import gold.debug.windowstolinux.appapi.task.TaskState;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.SwingUtilities;
import javax.swing.Timer;


import gold.debug.windowstolinux.mod.deployment.db.DeploymentPreferences.Selection;

/** Persists explicit view edits without writing initialization or locale changes. / 持久化明确视图编辑，不写初始化或语言变化。 */
final class DeploymentPreferenceSelection {
    /** Existing serial view worker. / 既有串行视图工作线程。 */
    private final ExecutorService actions;

    /** Original owner task admission. / 原所有者任务准入。 */
    private final Saver saver;

    /** Captures current EDT values. / 抓取当前EDT值。 */
    private final Supplier<Selection> capture;

    /** Restores only confirmed controls. / 仅恢复已确认控件。 */
    private final Consumer<Selection> apply;

    /** Displays safe task identity or failure. / 显示安全任务身份或失败。 */
    private final Consumer<String> failure;

    /** Recomputes original control availability. / 重算原控件可用性。 */
    private final Runnable availability;

    /** Last successfully persisted pair. / 最近成功持久化配对。 */
    private Selection confirmed;

    /** Whether initialization established valid stored values. / 初始化是否建立有效存储值。 */
    private boolean ready;

    /** Whether an explicit write remains active. / 明确写入是否仍活动。 */
    private boolean saving;

    /** Whether this view has closed. / 此视图是否关闭。 */
    private boolean closed;

    /** Observes retained UNKNOWN without waiting for a terminal future. / 无需等待终态Future即可观察留存UNKNOWN。 */
    private Timer observer;

    /** Safe retained write diagnostic, independent of task-list refresh. / 安全留存写入诊断，独立于任务列表刷新。 */
    private String diagnostic = "";

    /** Binds the current view and original task owner. / 绑定当前视图及原任务所有者。
     * @param actions serial view worker / 串行视图工作线程
     * @param saver actual metadata admission / 实际元数据准入
     * @param capture EDT selection capture / EDT选择抓取
     * @param apply EDT confirmed selection restoration / EDT确认选择恢复
     * @param failure safe original identity display / 安全原身份显示
     * @param availability original control refresh / 原控件刷新 */
    DeploymentPreferenceSelection(ExecutorService actions, Saver saver, Supplier<Selection> capture,
                                  Consumer<Selection> apply, Consumer<String> failure, Runnable availability) {
        this.actions = Objects.requireNonNull(actions);
        this.saver = Objects.requireNonNull(saver);
        this.capture = Objects.requireNonNull(capture);
        this.apply = Objects.requireNonNull(apply);
        this.failure = Objects.requireNonNull(failure);
        this.availability = Objects.requireNonNull(availability);
    }

    /** Returns whether a stored selection is confirmed and editable. / 返回存储选择是否已确认且可编辑。
     * @return actual readiness / 实际就绪状态 */
    boolean ready() {
        return ready && !saving && !closed;
    }

    /** Returns the original safe edit diagnostic without hiding uncertainty. / 返回原安全编辑诊断，不隐藏未知。
     * @return fixed event or original retained identity / 固定事件或原留存身份 */
    String diagnostic() {
        return diagnostic;
    }

    /** Installs valid stored values while suppressing programmatic change events. / 安装有效存储值，同时抑制程序化变化事件。
     * @param selection actual stored pair / 实际存储配对 */
    void load(Selection selection) {
        ready = false;
        confirmed = Objects.requireNonNull(selection);
        apply.accept(selection);
        ready = true;
    }

    /** Admits one captured edit before returning to the EDT; no deployment or AI is started. / 返回EDT前接纳一项已抓取编辑，不启动部署或AI。 */
    void changed() {
        if (!ready()) return;
        var selection = capture.get();
        if (selection.equals(confirmed)) return;
        saving = true;
        availability.run();
        actions.execute(() -> {
            try {
                var handle = saver.save(selection);
                SwingUtilities.invokeLater(() -> observe(handle));
                handle.completion().whenComplete((result, error) -> SwingUtilities.invokeLater(() -> finish(result, error)));
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> finish(null, error));
            }
        });
    }

    /** Watches original UNKNOWN without cancelling or claiming cleanup. / 观察原UNKNOWN，不取消或声称清理完成。
     * @param handle actual admitted edit / 实际已接纳编辑 */
    private void observe(TaskHandle<Selection> handle) {
        if (closed || !saving) return;
        observer = new Timer(100, ignored -> {
            if (handle.snapshot().state() != TaskState.UNKNOWN) return;
            observer.stop();
            ready = false;
            diagnostic = handle.id() + " UNKNOWN " + handle.snapshot().progressText();
            failure.accept(diagnostic);
            availability.run();
        });
        observer.start();
    }

    /** Applies only confirmed success or restores the previous pair on known failure. / 仅应用确认成功，或在已知失败恢复之前配对。
     * @param result original final result / 原最终结果
     * @param error original callback failure / 原回调失败 */
    private void finish(OperationResult<Selection> result, Throwable error) {
        if (observer != null) observer.stop();
        if (closed) return;
        saving = false;
        if (error == null && result != null && result.outcome() == Outcome.SUCCEEDED) {
            diagnostic = "";
            load(result.value().orElseThrow());
        } else if (error == null && result != null && result.outcome() == Outcome.UNKNOWN
            || error instanceof ApiException api && api.problem().code() == ProblemCode.UNKNOWN) {
            ready = false;
            diagnostic = error instanceof ApiException api ? api.problem().eventCode()
                : result.problem().map(Problem::eventCode).orElse("UNKNOWN");
            failure.accept(diagnostic);
        } else {
            load(confirmed);
            diagnostic = error instanceof ApiException api ? api.problem().eventCode()
                : result == null ? "DEPLOYMENT_PREFERENCE_SAVE_FAILED"
                : result.problem().map(Problem::eventCode).orElse("DEPLOYMENT_PREFERENCE_SAVE_FAILED");
            failure.accept(diagnostic);
        }
        availability.run();
    }

    /** Stops only view observation; the accepted metadata task keeps its original lifecycle. / 仅停止视图观察；已接纳元数据任务保持原寿命。 */
    void close() {
        closed = true;
        if (observer != null) observer.stop();
    }

    /** Admits an original metadata write without a fabricated task. / 接纳原元数据写入，不伪造任务。 */
    @FunctionalInterface
    interface Saver {
        /** Saves the captured pair. / 保存已抓取配对。
         * @param selection actual EDT pair / 实际EDT配对
         * @return original task / 原任务
         * @throws ApiException if admission fails / 准入失败时 */
        TaskHandle<Selection> save(Selection selection) throws ApiException;
    }
}
