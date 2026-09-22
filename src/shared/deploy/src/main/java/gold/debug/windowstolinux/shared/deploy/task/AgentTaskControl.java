package gold.debug.windowstolinux.shared.deploy.task;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import gold.debug.windowstolinux.shared.model.agent.AgentTaskState;

/** Cooperatively controls a serial task at transaction boundaries. / 在事务边界协作控制串行任务。 */
public final class AgentTaskControl {
    /** Current visible task state. / 当前可见任务状态。 */
    private AgentTaskState state = AgentTaskState.RUNNING;

    /** Listener receives state only, never secrets. / 监听器仅接收状态，不接收秘密。 */
    private final Consumer<AgentTaskState> listener;

    /** Retains the reason for a paused boundary. / 保存暂停边界原因。 */
    private String reason = "";

    /** Runs approved configuration replacement on the owning worker, never the UI thread. / 在所属工作线程而非 UI 线程应用获准配置替换。 */
    private Runnable resumeAction = () -> {
    };

    /** Whether the worker must recheck explicitly requested resume updates. / 工作线程是否须检查显式恢复更新。 */
    private boolean resumed;
    /** Sets the task-owned resume hook. / 设置任务所属恢复钩子。
     * @param action bounded configuration refresh / 有界配置刷新
     */
    public synchronized void onResume(Runnable action) {
        resumeAction = Objects.requireNonNull(action);
    }

    /** Creates process-local task control. / 创建进程内任务控制。
     * @param listener state observer / 状态观察者
     */
    public AgentTaskControl(Consumer<AgentTaskState> listener) {
        this.listener = Objects.requireNonNull(listener);
    }

    /** Requests pause after the current bounded transaction. / 请求当前有界事务后暂停。 */
    public synchronized void pause() {
        if (state == AgentTaskState.RUNNING)
            change(AgentTaskState.PAUSE_REQUESTED);
    }

    /** Resumes only a known, paused task. / 仅恢复结果已知的暂停任务。 */
    public synchronized void resume() {
        if (state == AgentTaskState.PAUSED) {
            reason = "";
            resumed = true;
            change(AgentTaskState.RUNNING);
            notifyAll();
        }
    }

    /** Requests cancellation without abandoning an executing remote transaction. / 请求取消，但不遗弃执行中的远端事务。 */
    public synchronized void cancel() {
        if (state == AgentTaskState.RUNNING || state == AgentTaskState.PAUSED
                || state == AgentTaskState.PAUSE_REQUESTED) {
            change(AgentTaskState.CANCEL_REQUESTED);
            notifyAll();
        }
    }

    /** Reads the current state. / 读取当前状态。
     * @return lifecycle state / 生命周期状态
     */
    public synchronized AgentTaskState state() {
        return state;
    }

    /** Reads the bounded pause reason. / 读取有界暂停原因。
     * @return reason code / 原因代码
     */
    public synchronized String reason() {
        return reason;
    }

    /** Pauses pending work and waits for explicit user action. / 暂停待执行工作并等待明确用户操作。
     * @param reasonCode safe reason code / 安全原因代码
     */
    public synchronized void awaitUser(String reasonCode) {
        reason = reasonCode;
        if (state == AgentTaskState.RUNNING)
            change(AgentTaskState.PAUSE_REQUESTED);
        checkpoint();
    }

    /** Blocks at a safe boundary, preserving cancellation and interruption. / 在安全边界阻塞，保留取消和中断语义。 */
    public synchronized void checkpoint() {
        if (Thread.currentThread().isInterrupted())
            throw new CancellationException("task interrupted before execution");
        if (state == AgentTaskState.PAUSE_REQUESTED)
            change(AgentTaskState.PAUSED);
        while (true) {
            while (state == AgentTaskState.PAUSED)
                try {
                    wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("task interrupted at boundary");
                }
            if (state == AgentTaskState.CANCEL_REQUESTED) {
                change(AgentTaskState.CANCELLED);
                throw new CancellationException("task cancelled at safe boundary");
            }
            if (state != AgentTaskState.RUNNING)
                throw new IllegalStateException("task cannot execute in " + state);
            if (resumed) {
                resumed = false;
                try {
                    resumeAction.run();
                } catch (RuntimeException invalid) {
                    reason = "model-configuration-invalid";
                    change(AgentTaskState.PAUSED);
                    continue;
                }
            }
            return;
        }
    }

    /** Records a terminal or unknown outcome without enabling replay. / 记录终态或未知结果，不允许重放。
     * @param terminal verified terminal state / 已验证终态
     */
    public synchronized void finish(AgentTaskState terminal) {
        if (terminal != AgentTaskState.SUCCEEDED && terminal != AgentTaskState.FAILED
                && terminal != AgentTaskState.UNKNOWN && terminal != AgentTaskState.CANCELLED)
            throw new IllegalArgumentException("not terminal");
        change(terminal);
        notifyAll();
    }

    /** Publishes a state transition. / 发布状态转换。
     * @param next new state / 新状态
     */
    private void change(AgentTaskState next) {
        state = next;
        listener.accept(next);
    }
}
