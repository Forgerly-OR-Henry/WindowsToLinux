package gold.debug.windowstolinux.mod.deployment.service.agent.control;

import java.util.Objects;
import java.util.concurrent.CancellationException;

import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Cooperates with one owning worker only at settled action boundaries. / 仅在动作已结清边界与一项所属工作线程协作。 */
public final class DeploymentAgentControl {
    /** Mod-local control state, independent of SDK task states. / 独立于SDK任务状态的Mod内部控制状态。 */
    public enum State {
        /** The original worker may proceed. / 原工作线程可以继续。 */
        RUNNING,
        /** Pause awaits the current bounded action. / 暂停等待当前有界动作。 */
        PAUSE_REQUESTED,
        /** No action is in flight at this paused boundary. / 此暂停边界没有在途动作。 */
        PAUSED,
        /** Original execution has stopped and cannot resume. / 原执行已停止且不能继续。 */
        FINISHED
    }

    /** Exact explicit user commands. / 精确显式用户命令。 */
    public enum CommandAction {
        /** Pause after the current action. / 当前动作之后暂停。 */
        PAUSE,
        /** Continue the same task and budget. / 继续同一任务及预算。 */
        RESUME,
        /** Adopt models only from an actually paused boundary. / 仅从实际暂停边界采用模型。 */
        REFRESH_MODELS
    }

    /** Original worker callbacks; UI threads never use task capabilities. / 原工作线程回调；UI线程不使用任务能力。 */
    public interface Boundary {
        /** Persists a verified paused boundary. / 持久化已核实暂停边界。
         * @throws Exception if effects cannot be settled / 无法结清副作用时 */
        void paused() throws Exception;

        /** Rebinds only explicitly requested models. / 仅重新绑定明确请求的模型。
         * @return whether an explicit replacement invalidated the old proposal / 明确替换是否使旧提案失效
         * @throws Exception if refresh cannot be authorized / 无法授权刷新时 */
        boolean refresh() throws Exception;

        /** Persists continuation of the original worker. / 持久化原工作线程继续。
         * @throws Exception if continuation cannot be recorded / 无法记录继续时 */
        void resumed() throws Exception;
    }

    /** A known invalid model selection leaves the original worker paused. / 已知无效模型选择使原工作线程保持暂停。 */
    public static final class RefreshRejectedException extends ApiException {
        /** Preserves the actual configuration rejection. / 保留实际配置拒绝。
         * @param problem original SDK or task-bound rejection / 原SDK或任务绑定拒绝
         * @param cause original known rejection / 原已知拒绝 */
        public RefreshRejectedException(Problem problem, Throwable cause) {
            super(problem);
            initCause(cause);
        }
    }

    /** Actual process-local boundary state. / 实际进程内部边界状态。 */
    private State state = State.RUNNING;

    /** Explicit continuation awaiting the owning worker. / 等待所属工作线程的明确继续请求。 */
    private boolean resume;

    /** Explicit model replacement awaiting boundary validation. / 等待边界校验的明确模型替换请求。 */
    private boolean refresh;

    /** Creates control for one original execution. / 创建一项原执行的控制器。 */
    public DeploymentAgentControl() {
    }

    /** Accepts a UI request without borrowing an original task context. / 接受UI请求且不借用原任务上下文。
     * @param command exact user request / 精确用户请求
     * @throws IllegalStateException if the current boundary rejects it / 当前边界拒绝时 */
    public synchronized void request(CommandAction command) {
        Objects.requireNonNull(command);
        if (command == CommandAction.PAUSE && state == State.RUNNING)
            state = State.PAUSE_REQUESTED;
        else if (state == State.PAUSED && command != CommandAction.PAUSE) {
            resume = true;
            refresh = command == CommandAction.REFRESH_MODELS;
            notifyAll();
        } else
            throw new IllegalStateException("Original agent boundary does not accept " + command);
    }

    /** Reads only the Mod-local state. / 仅读取Mod内部状态。
     * @return actual boundary state / 实际边界状态 */
    public synchronized State state() {
        return state;
    }

    /** Waits after a settled action and observes original cancellation while paused. / 在动作已结清后等待，并在暂停时观察原取消。
     * @param task authentic owning task / 真实所属任务
     * @param boundary original worker callbacks / 原工作线程回调
     * @return whether an explicit model replacement invalidated the current proposal / 明确模型替换是否使当前提案失效
     * @throws Exception if original persistence, cancellation or refresh fails / 原持久化、取消或刷新失败时 */
    public synchronized boolean checkpoint(TaskContext task, Boundary boundary) throws Exception {
        cancelled(task);
        if (state == State.PAUSE_REQUESTED) {
            boundary.paused();
            state = State.PAUSED;
        }
        boolean changed = false;
        while (state == State.PAUSED) {
            cancelled(task);
            if (!resume) {
                try {
                    wait(250);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Agent interrupted at its safe boundary");
                }
                continue;
            }
            resume = false;
            if (refresh) {
                refresh = false;
                try {
                    changed = boundary.refresh();
                } catch (RefreshRejectedException rejected) {
                    if (rejected.problem().code() == ProblemCode.UNKNOWN)
                        throw rejected;
                    continue;
                }
            }
            boundary.resumed();
            state = State.RUNNING;
        }
        cancelled(task);
        if (state != State.RUNNING)
            throw new IllegalStateException("Original agent cannot resume");
        return changed;
    }

    /** Wakes a paused worker when its original execution exits. / 原执行退出时唤醒暂停工作线程。 */
    public synchronized void finish() {
        state = State.FINISHED;
        notifyAll();
    }

    /** Preserves actual cooperative cancellation and thread interruption. / 保留实际协作取消及线程中断。
     * @param task authentic original task / 真实原任务 */
    private static void cancelled(TaskContext task) {
        if (task.cancelToken().requested() || Thread.currentThread().isInterrupted())
            throw new CancellationException("Agent cancelled at its safe boundary");
    }
}
