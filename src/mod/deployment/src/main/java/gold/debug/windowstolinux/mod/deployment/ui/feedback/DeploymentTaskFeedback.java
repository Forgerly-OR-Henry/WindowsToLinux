package gold.debug.windowstolinux.mod.deployment.ui.feedback;

import java.awt.BorderLayout;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;

import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationUsage;
import gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics;

/** Continuously observes one original handle without submitting polling tasks. / 持续观察一个原句柄，不提交轮询任务。 */
public final class DeploymentTaskFeedback implements AutoCloseable {
    /** Reads delivery with the actual completed original identity. / 以实际已完成原身份读取交付。 */
    public interface DeliveryLookup {
        /** Admits one exact original publication read. / 准入一项精确原发布读取。
         * @param originalTaskUUID actual original platform task UUID / 实际原平台任务UUID
         * @return actual bounded owned query / 实际有界所属查询
         * @throws ApiException if current original admission fails / 当前原准入失败时 */
        TaskHandle<Optional<DeploymentApplicationUsage>> read(UUID originalTaskUUID) throws ApiException;
    }

    /** Original owned diagnostic reader. / 原所属诊断读取器。 */
    private final DeploymentDiagnostics diagnostics;

    /** Page-owned background executor. / 页面所属后台执行器。 */
    private final Executor worker;

    /** Actual finite platform task navigation. / 实际有限平台任务导航。 */
    private final Runnable openTasks;

    /** Optional exact completed-publication delivery. / 可选精确已完成发布交付。 */
    private final DeploymentTaskDelivery delivery;

    /** Owned feedback controls. / 所属反馈控件。 */
    private final JPanel root = new JPanel(new BorderLayout(4, 4));

    /** Latest actual state and original identity. / 最新实际状态及原身份。 */
    private final JTextArea state = new JTextArea(3, 40);

    /** Bounded persisted event window. / 有界持久事件窗口。 */
    private final JTextArea events = new JTextArea(6, 40);

    private final JScrollPane eventsScroll = new JScrollPane(events);

    private boolean compactIdle;

    /** Cooperative cancellation of the original handle. / 原句柄的协作取消。 */
    private final JButton cancel = new JButton();

    /** Original task and recovery destination. / 原任务及恢复目的页。 */
    private final JButton tasks = new JButton();

    /** Only one diagnostic read may be in flight. / 仅允许一项诊断读取在途。 */
    private final AtomicBoolean reading = new AtomicBoolean();

    /** EDT timer dispatches storage to the original worker. / EDT计时器将存储派发给原工作线程。 */
    private final Timer timer;

    /** Actual original accepted task, retained after completion. / 实际原已准入任务，完成后仍保留。 */
    private TaskHandle<?> handle;

    /** Current localized messages. / 当前本地化文字。 */
    private ResourceBundle messages;

    /** Observed completion outcome, never inferred from cancellation. / 已观测完成结果，绝不从取消推断。 */
    private String result = "";

    /** Last confirmed event window. / 最近确认的事件窗口。 */
    private String retainedEvents = "";

    /** Revokes stale callbacks from lock, replacement or closure. / 撤销锁定、替换或关闭前的过期回调。 */
    private long generation;

    /** View admission state independent of its accepted task. / 独立于已准入任务的视图准入状态。 */
    private boolean unavailable, closed, cancellationRequested;

    /** Creates controls without any storage access. / 不访问存储地创建控件。
     * @param diagnostics original owned diagnostic reader / 原所属诊断读取器
     * @param worker original page background executor / 原页面后台执行器
     * @param openTasks finite actual task destination / 有限实际任务目的页 */
    public DeploymentTaskFeedback(DeploymentDiagnostics diagnostics, Executor worker, Runnable openTasks) {
        this(diagnostics, worker, openTasks, Optional.empty(),
            gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().view());
    }

    /** Uses the owning page's fixed refresh interval. / 使用所属页面的固定刷新间隔。
     * @param diagnostics original diagnostic reader / 原诊断读取器
     * @param worker original worker / 原工作线程
     * @param openTasks task navigation / 任务导航
     * @param view immutable presentation settings / 不可变展示配置 */
    public DeploymentTaskFeedback(DeploymentDiagnostics diagnostics, Executor worker, Runnable openTasks,
                                  gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.View view) {
        this(diagnostics, worker, openTasks, Optional.empty(), view);
    }

    /** Includes original successful-publication delivery without a polling task. / 包含原成功发布交付，不创建轮询任务。
     * @param diagnostics original owned diagnostic reader / 原所属诊断读取器
     * @param worker original page background executor / 原页面后台执行器
     * @param openTasks finite actual task destination / 有限实际任务目的页
     * @param delivery actual UUID-bound delivery lookup / 实际UUID绑定交付查询 */
    public DeploymentTaskFeedback(DeploymentDiagnostics diagnostics, Executor worker, Runnable openTasks,
                                  DeliveryLookup delivery) {
        this(diagnostics, worker, openTasks, delivery,
            gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().view());
    }

    /** Binds delivery and refresh timing without creating polling tasks. / 绑定交付及刷新时序，不创建轮询任务。
     * @param diagnostics original diagnostic reader / 原诊断读取器
     * @param worker original worker / 原工作线程
     * @param openTasks task navigation / 任务导航
     * @param delivery original publication lookup / 原发布查询
     * @param view immutable presentation settings / 不可变展示配置 */
    public DeploymentTaskFeedback(DeploymentDiagnostics diagnostics, Executor worker, Runnable openTasks,
                                  DeliveryLookup delivery,
                                  gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.View view) {
        this(diagnostics, worker, openTasks, Optional.of(Objects.requireNonNull(delivery)), view);
    }

    /** Builds the original feedback with only the supplied concrete publication capability. / 仅以所提供具体发布能力构建原反馈。
     * @param diagnostics original owned reader / 原所属读取器
     * @param worker original page worker / 原页面工作线程
     * @param openTasks finite actual destination / 有限实际目的页
     * @param lookup present only for publication pages / 仅发布页面提供
     * @param view immutable refresh settings / 不可变刷新配置 */
    private DeploymentTaskFeedback(DeploymentDiagnostics diagnostics, Executor worker, Runnable openTasks,
                                   Optional<DeliveryLookup> lookup,
                                   gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.View view) {
        this.diagnostics = Objects.requireNonNull(diagnostics);
        this.worker = Objects.requireNonNull(worker);
        this.timer = new Timer(Objects.requireNonNull(view).refreshMillis(), ignored -> poll());
        this.openTasks = Objects.requireNonNull(openTasks);
        this.delivery = lookup.map(value -> new DeploymentTaskDelivery(value, worker)).orElse(null);
        root.setName("deployment.task.feedback");
        state.setName("deployment.task.feedback.state");
        events.setName("deployment.task.feedback.events");
        cancel.setName("deployment.task.feedback.cancel");
        tasks.setName("deployment.task.feedback.tasks");
        state.setEditable(false);
        events.setEditable(false);
        state.setLineWrap(true);
        events.setLineWrap(true);
        state.setWrapStyleWord(true);
        events.setWrapStyleWord(true);
        var controls = new JPanel();
        controls.add(cancel);
        controls.add(tasks);
        root.add(state, BorderLayout.NORTH);
        var observation = new JPanel(new BorderLayout(4, 4));
        observation.add(eventsScroll, BorderLayout.CENTER);
        if (delivery != null) observation.add(delivery.component(), BorderLayout.SOUTH);
        root.add(observation, BorderLayout.CENTER);
        root.add(controls, BorderLayout.SOUTH);
        cancel.addActionListener(ignored -> cancelOriginal());
        tasks.addActionListener(ignored -> {
            if (!closed && !unavailable) openTasks.run();
        });
        timer.setCoalesce(true);
        configure(Locale.SIMPLIFIED_CHINESE, false);
    }

    /** Returns the original owned component. / 返回原所属组件。
     * @return actual feedback root / 实际反馈根 */
    public JComponent component() {
        return root;
    }

    /** Lets an owning flow present its inventory's single idle message. / 让所属流程仅呈现任务清单的一处空态。 */
    public void compactIdle(boolean compact) {
        requireEdt();
        compactIdle = compact;
        availability();
    }

    /** Binds an admitted original handle on the EDT. / 在EDT绑定已准入原句柄。
     * @param original actual original handle / 实际原句柄 */
    public void watch(TaskHandle<?> original) {
        requireEdt();
        if (closed) return;
        handle = Objects.requireNonNull(original);
        if (delivery != null) delivery.watch(original);
        generation++;
        result = "";
        retainedEvents = "";
        cancellationRequested = false;
        original.completion().whenComplete((completed, failure) -> {
            try {
                worker.execute(() -> diagnostics.completed(original.id(), completed));
            } catch (java.util.concurrent.RejectedExecutionException ignored) { /* Page already closed. */ }
            SwingUtilities.invokeLater(() -> completed(original, completed, failure));
        });
        if (!unavailable) {
            timer.start();
            poll();
        }
    }

    /** Applies locale and lock state without cancelling accepted business work. / 应用语言及锁定状态，不取消已准入业务。
     * @param locale actual locale / 实际语言
     * @param unavailable current locked or revoked view / 当前锁定或已撤销视图 */
    public void configure(Locale locale, boolean unavailable) {
        requireEdt();
        messages = ResourceBundle.getBundle("gold.debug.windowstolinux.mod.deployment.ui.feedback.task-feedback", locale);
        this.unavailable = unavailable;
        generation++;
        if (delivery != null) delivery.gate(unavailable);
        cancel.setText(messages.getString("cancel"));
        tasks.setText(messages.getString("tasks"));
        if (closed || unavailable) {
            timer.stop();
            state.setText("");
            events.setText("");
        } else if (handle != null) {
            timer.start();
            poll();
        } else {
            state.setText(messages.getString("idle"));
            events.setText("");
        }
        availability();
    }

    /** Applies the original full host environment to real delivery actions. / 将原完整宿主环境应用于实际交付动作。
     * @param environment actual host environment / 实际宿主环境 */
    public void configure(UiEnvironment environment) {
        requireEdt();
        if (delivery != null) delivery.environment(environment);
        configure(environment.locale(), environment.locked());
    }

    /** Captures the host's immutable snapshot and reads bounded diagnostics off the EDT. / 捕获宿主不可变快照，在EDT外读取有界诊断。 */
    private void poll() {
        requireEdt();
        if (closed || unavailable || handle == null) return;
        var original = handle;
        var snapshot = original.snapshot();
        state.setText(snapshot.id() + " · " + snapshot.state() + "\n" + snapshot.progressText()
            + (cancellationRequested ? "\n" + messages.getString("cancelRequested") : "")
            + (result.isEmpty() ? "" : "\n" + result));
        availability();
        if (!reading.compareAndSet(false, true)) return;
        long view = generation;
        try {
            worker.execute(() -> read(original, view));
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            reading.set(false);
            timer.stop();
        }
    }

    /** Reads persisted original events with one in-flight storage operation. / 用一项在途存储操作读取持久原事件。
     * @param original exact original handle / 精确原句柄
     * @param view captured view generation / 捕获的视图代次 */
    private void read(TaskHandle<?> original, long view) {
        String text;
        try {
            var observed = diagnostics.read(original.id());
            text = observed.records().reversed().stream().map(record -> record.recordedAt() + " · "
                + record.event().level() + " · " + record.event().eventCode() + " · " + record.event().message()
                + record.event().outcome().map(value -> " · " + value).orElse("")
                + (record.truncated() ? " …" : "")).collect(java.util.stream.Collectors.joining("\n"));
            text += observed.problem().map(problem -> "\n" + problem.eventCode()).orElse("");
        } catch (Exception unavailable) {
            text = null;
        }
        String current = text;
        SwingUtilities.invokeLater(() -> {
            reading.set(false);
            if (closed || unavailable || generation != view || handle != original) return;
            if (current != null) retainedEvents = current;
            events.setText(retainedEvents + (current == null ? "\n" + messages.getString("unavailable") : ""));
            if (original.snapshot().endedAt().isPresent()) timer.stop();
        });
    }

    /** Retains only actual final outcome and a safe problem identifier. / 仅保留实际最终结果及安全问题标识。
     * @param original exact completed handle / 精确已完成句柄
     * @param completed actual host completion / 实际宿主完成
     * @param failure actual completion exception / 实际完成异常 */
    private void completed(TaskHandle<?> original, OperationResult<?> completed, Throwable failure) {
        if (closed || handle != original) return;
        if (delivery != null) delivery.completed(original, completed, failure);
        result = failure != null || completed == null ? messages.getString("unavailable")
            : completed.outcome() + completed.problem().map(problem -> " · " + problem.eventCode()).orElse("");
        if (!unavailable) {
            timer.start();
            poll();
        }
    }

    /** Requests original cooperative cancellation without displaying a fabricated terminal result. / 请求原协作取消，不显示捏造终态。 */
    private void cancelOriginal() {
        if (closed || unavailable || handle == null || handle.snapshot().endedAt().isPresent()) return;
        var original = handle;
        cancellationRequested = true;
        cancel.setEnabled(false);
        poll();
        worker.execute(() -> {
            try {
                original.requestCancel();
            } catch (Exception refused) {
                SwingUtilities.invokeLater(() -> {
                    if (!closed && !unavailable && handle == original) {
                        result = messages.getString("cancelUnavailable");
                        poll();
                    }
                });
            }
        });
    }

    /** Derives controls from current admission and actual original terminal state. / 从当前准入及实际原终态确定控件。 */
    private void availability() {
        boolean showObservation = !compactIdle || !closed && !unavailable && handle != null;
        state.setVisible(showObservation);
        eventsScroll.setVisible(showObservation);
        cancel.setEnabled(!closed && !unavailable && handle != null && !cancellationRequested
            && handle.snapshot().endedAt().isEmpty());
        tasks.setEnabled(!closed && !unavailable);
    }

    /** Stops every refresh callback without changing the accepted original task. / 停止所有刷新回调，不改变已准入原任务。 */
    @Override
    public void close() {
        requireEdt();
        closed = true;
        generation++;
        timer.stop();
        handle = null;
        if (delivery != null) delivery.close();
        retainedEvents = "";
        result = "";
        state.setText("");
        events.setText("");
        availability();
    }

    /** Enforces actual Swing ownership. / 强制实际Swing归属。 */
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Feedback requires EDT");
    }
}
