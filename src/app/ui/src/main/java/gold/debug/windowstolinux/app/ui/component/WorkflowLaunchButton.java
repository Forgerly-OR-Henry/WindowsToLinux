package gold.debug.windowstolinux.app.ui.component;

import java.util.Objects;
import java.util.function.*;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.task.PlatformAuthorizationService;
import gold.debug.windowstolinux.appapi.task.TaskHandle;

/** Host start control capturing the actual selected workflow and its target scope. / 捕获实际选定流程及目标作用域的宿主启动控件。
 * @param <T> actual workflow result / 实际流程结果 */
public final class WorkflowLaunchButton<T> extends JButton implements AutoCloseable {
    /** Host launch authority for this activation. / 此启用的宿主启动授权。 */
    private final PlatformAuthorizationService.Launcher launcher;

    /** Reads and validates current form selections on the EDT. / 在 EDT 读取及校验当前表单选择。 */
    private final Supplier<PlatformAuthorizationService.Launch<T>> selection;

    /** Receives the original admitted task handle. / 接收原准入任务句柄。 */
    private final Consumer<TaskHandle<T>> accepted;

    /** Presents admission or form errors. / 呈现准入或表单错误。 */
    private final Consumer<Exception> failure;

    /** Prevents starts while locked, already running or detached. / 锁定、已运行或分离时阻止启动。 */
    private boolean locked = true, busy, closed;

    /** Binds actual host admission to one native button action. / 将实际宿主准入绑定一次原生按钮操作。
     * @param text localized action label / 本地化操作标签
     * @param launcher owning activation's host launcher / 所属启用的宿主启动器
     * @param selection current validated form selection / 当前已校验表单选择
     * @param accepted original admitted task callback / 原准入任务回调
     * @param failure visible admission failure callback / 可见准入失败回调 */
    public WorkflowLaunchButton(String text, PlatformAuthorizationService.Launcher launcher,
            Supplier<PlatformAuthorizationService.Launch<T>> selection, Consumer<TaskHandle<T>> accepted,
            Consumer<Exception> failure) {
        super(text);
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Workflow controls require the EDT");
        this.launcher = Objects.requireNonNull(launcher);
        this.selection = Objects.requireNonNull(selection);
        this.accepted = Objects.requireNonNull(accepted);
        this.failure = Objects.requireNonNull(failure);
        addActionListener(event -> launch());
        controls();
    }

    /** Applies the containing page's current privacy state. / 应用所属页面当前隐私状态。
     * @param value actual privacy state / 实际隐私状态 */
    public void setLocked(boolean value) {
        locked = value;
        controls();
    }

    /** Captures the form once, then admits a central task on a worker. / 一次捕获表单，再于工作线程准入中央任务。 */
    private void launch() {
        if (locked || busy || closed || DesktopTaskExecutor.isLocked())
            return;
        final PlatformAuthorizationService.Launch<T> launch;
        try {
            launch = Objects.requireNonNull(selection.get());
        } catch (RuntimeException invalid) {
            failure.accept(invalid);
            return;
        }
        busy = true;
        controls();
        DesktopTaskExecutor.run(() -> launcher.submit(launch), task -> {
            task.completion().whenComplete((result, error) -> SwingUtilities.invokeLater(() -> {
                busy = false;
                controls();
            }));
            if (!closed)
                accepted.accept(task);
        }, error -> {
            busy = false;
            controls();
            if (!closed)
                failure.accept(error);
        });
    }

    /** Reflects action availability without changing admitted work. / 反映操作可用性，不改变已准入工作。 */
    private void controls() {
        setEnabled(!locked && !busy && !closed);
    }

    /** Detaches this control while the central runtime keeps accepted work. / 分离此控件，中央运行时保留已接纳工作。 */
    @Override
    public void close() {
        closed = true;
        controls();
    }
}
