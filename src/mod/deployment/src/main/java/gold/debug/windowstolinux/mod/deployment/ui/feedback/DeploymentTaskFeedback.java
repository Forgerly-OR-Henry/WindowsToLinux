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
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationUsage;
import gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics;

/** Continuously observes one original handle without submitting polling tasks. / 持续观察一个原句柄，不提交轮询任务。 */
public final class DeploymentTaskFeedback implements AutoCloseable {
    /** Host-owned progress projection without clock-based fractions. / 不按时间推算比例的宿主进度投影。
     * @param fraction reported fraction, absent when unmeasurable / 报告的比例，不可计算时为空
     * @param estimated whether the fraction is a phase estimate / 比例是否为阶段估算
     * @param pendingInterventions unresolved original decisions / 原任务未决事项数
     * @param paused confirmed pause observation / 已确认暂停观察
     * @param uncertain unconfirmed result observation / 未确认结果观察 */
    public record Presentation(Optional<Double> fraction, boolean estimated, int pendingInterventions,
                               boolean paused, boolean uncertain) {
        /** Rejects invalid display observations. / 拒绝无效的展示观察。
         * @param fraction bounded reported fraction / 有界报告比例
         * @param estimated phase estimate flag / 阶段估算标记
         * @param pendingInterventions nonnegative decision count / 非负事项数
         * @param paused actual pause observation / 实际暂停观察
         * @param uncertain actual uncertainty observation / 实际不确定性观察 */
        public Presentation {
            Objects.requireNonNull(fraction);
            if (pendingInterventions < 0 || fraction.filter(value -> !Double.isFinite(value) || value < 0 || value > 1).isPresent())
                throw new IllegalArgumentException("Invalid feedback projection");
        }
    }

    /** Worker-side host observation without another task. / 不创建新任务的后台宿主观察。 */
    private volatile java.util.function.Function<UUID, Optional<Presentation>> presentationLookup = ignored -> Optional.empty();

    /** Destination for the watched task's unresolved decisions. / 所观察任务的未决事项目的页。 */
    private java.util.function.Consumer<UUID> interventionNavigation;

    /** Last presentation admitted for this view generation. / 当前视图代次最近准入的展示。 */
    private Optional<Presentation> presentation = Optional.empty();

    /** Locale used to format factual counts. / 格式化事实计数的语言。 */
    private Locale locale = Locale.SIMPLIFIED_CHINESE;

    /** Fraction bar that reserves full completion for actual success. / 仅实际成功才完整填满的比例条。 */
    private final JProgressBar progress = new JProgressBar(0, 100);

    /** Visible distinction between estimated and measured fractions. / 区分预计与实测比例的可见文字。 */
    private final JLabel percentage = new JLabel();

    /** Factual pending-decision count. / 实际未决事项数。 */
    private final JLabel interventionCount = new JLabel();

    /** Opens decisions for the retained original identity. / 按保留的原身份打开事项。 */
    private final JButton intervention = new JButton();

    /** Explicit opt-in to technical information. / 明确展开专业信息的控件。 */
    private final JToggleButton detailsToggle = new JToggleButton();

    /** Copyable original task and diagnostic evidence. / 可复制的原任务及诊断依据。 */
    private final JTextArea details = new JTextArea(8, 40);

    /** Collapsed container for original evidence. / 默认折叠的原依据容器。 */
    private final JPanel detailsPanel = new JPanel(new BorderLayout(4, 4));

    /** Copies the complete retained evidence beyond the viewport. / 复制包含视口外内容的完整保留依据。 */
    private final JButton copyDetails = new JButton();

    /** Keeps the current action above its fraction bar. / 将当前动作放在比例条上方。 */
    private final JPanel progressPanel = new JPanel(new BorderLayout(4, 4));

    /** Consecutively deduplicated known workflow phases. / 连续去重的已知流程阶段。 */
    private java.util.List<String> retainedStages = java.util.List.of();

    /** Actual completion identity retained only in details. / 仅在详情保留的实际完成标识。 */
    private String completionDetails = "";

    /** Reports a missing diagnostic observation without replacing evidence. / 不替换已有依据地报告诊断缺失。 */
    private boolean observationUnavailable;

    /** Fixed plain-language explanation for the latest known issue. / 最近已知问题的固定通俗说明。 */
    private String observationNotice = "";

    /** Prevents repeated graph phases from reversing an estimate. / 避免重复整图阶段导致预计值倒退。 */
    private int estimatedHighWater;

    /** Reads existing owner diagnostics only on the EDT. / 仅在 EDT 读取既有所属诊断。 */
    private java.util.function.Supplier<String> additionalDetails = () -> "";

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

    private java.util.function.Consumer<UUID> originalTaskNavigation;

    /** Optional exact completed-publication delivery. / 可选精确已完成发布交付。 */
    private final DeploymentTaskDelivery delivery;

    /** Owned feedback controls. / 所属反馈控件。 */
    private final JPanel root = new JPanel(new BorderLayout(4, 4));

    /** Latest actual state and original identity. / 最新实际状态及原身份。 */
    private final JTextArea state = new JTextArea(3, 40);

    /** Human-readable stage history, separate from diagnostics. / 与诊断分开的通俗阶段历史。 */
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
        progress.setName("deployment.task.feedback.progress");
        percentage.setName("deployment.task.feedback.percentage");
        interventionCount.setName("deployment.task.feedback.interventionCount");
        intervention.setName("deployment.task.feedback.intervention");
        detailsToggle.setName("deployment.task.feedback.detailsToggle");
        details.setName("deployment.task.feedback.details");
        copyDetails.setName("deployment.task.feedback.copyDetails");
        state.setEditable(false);
        events.setEditable(false);
        state.setLineWrap(true);
        events.setLineWrap(true);
        state.setWrapStyleWord(true);
        events.setWrapStyleWord(true);
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        state.setOpaque(false);
        events.setOpaque(false);
        var controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        cancel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        tasks.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        detailsToggle.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        controls.add(cancel);
        controls.add(Box.createVerticalStrut(4));
        controls.add(tasks);
        controls.add(Box.createVerticalStrut(4));
        controls.add(detailsToggle);
        var action = new JPanel(new BorderLayout(4, 4));
        action.add(state, BorderLayout.CENTER);
        action.add(percentage, BorderLayout.EAST);
        progressPanel.add(action, BorderLayout.NORTH);
        progressPanel.add(progress, BorderLayout.CENTER);
        var attention = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEADING));
        attention.add(interventionCount);
        attention.add(intervention);
        progressPanel.add(attention, BorderLayout.SOUTH);
        root.add(progressPanel, BorderLayout.NORTH);
        var observation = new JPanel(new BorderLayout(4, 4));
        observation.add(eventsScroll, BorderLayout.CENTER);
        detailsPanel.add(new JScrollPane(details), BorderLayout.CENTER);
        detailsPanel.add(copyDetails, BorderLayout.SOUTH);
        detailsPanel.setVisible(false);
        var supplement = new JPanel(new BorderLayout(4, 4));
        supplement.add(detailsPanel, BorderLayout.CENTER);
        if (delivery != null) supplement.add(delivery.component(), BorderLayout.SOUTH);
        observation.add(supplement, BorderLayout.SOUTH);
        root.add(observation, BorderLayout.CENTER);
        root.add(controls, BorderLayout.SOUTH);
        cancel.addActionListener(ignored -> cancelOriginal());
        detailsToggle.addActionListener(ignored -> {
            detailsPanel.setVisible(detailsToggle.isSelected() && !closed && !unavailable);
            root.revalidate();
        });
        copyDetails.addActionListener(ignored -> {
            if (closed || unavailable) return;
            try {
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                    new java.awt.datatransfer.StringSelection(details.getText()), null);
            } catch (IllegalStateException | java.awt.HeadlessException unavailableClipboard) {
                details.selectAll();
                details.requestFocusInWindow();
            }
        });
        intervention.addActionListener(ignored -> {
            if (!closed && !unavailable && handle != null && interventionNavigation != null)
                interventionNavigation.accept(handle.id());
        });
        tasks.addActionListener(ignored -> {
            if (!closed && !unavailable) {
                if (handle != null && originalTaskNavigation != null)
                    originalTaskNavigation.accept(handle.snapshot().id());
                else openTasks.run();
            }
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

    /** Navigates using the actual watched task UUID rather than a replacement task. */
    public void originalTaskNavigation(java.util.function.Consumer<UUID> navigation) {
        requireEdt();
        originalTaskNavigation = Objects.requireNonNull(navigation);
    }

    /** Reads host progress without admitting another task. / 不准入新任务地读取宿主进度。
     * @param lookup worker-side original-task projection / 后台原任务投影 */
    public void presentationLookup(java.util.function.Function<UUID, Optional<Presentation>> lookup) {
        requireEdt();
        presentationLookup = Objects.requireNonNull(lookup);
        if (handle != null && !closed && !unavailable) poll();
    }

    /** Opens the exact original task's manual decisions. / 打开精确原任务的人工事项。
     * @param navigation original-task decision destination / 原任务事项目的页 */
    public void interventionNavigation(java.util.function.Consumer<UUID> navigation) {
        requireEdt();
        interventionNavigation = Objects.requireNonNull(navigation);
        availability();
    }

    /** Appends owner diagnostics only to details. / 仅向详情附加所属诊断。
     * @param supplier EDT-safe original diagnostic reader / EDT 安全的原诊断读取器 */
    public void additionalDetails(java.util.function.Supplier<String> supplier) {
        requireEdt();
        additionalDetails = Objects.requireNonNull(supplier);
    }

    /** Binds an admitted original handle on the EDT. / 在EDT绑定已准入原句柄。
     * @param original actual original handle / 实际原句柄 */
    public void watch(TaskHandle<?> original) {
        requireEdt();
        if (closed) return;
        if (handle == original) {
            if (!unavailable) poll();
            return;
        }
        handle = Objects.requireNonNull(original);
        if (delivery != null) delivery.watch(original);
        generation++;
        result = "";
        retainedEvents = "";
        completionDetails = "";
        retainedStages = java.util.List.of();
        presentation = Optional.empty();
        observationUnavailable = false;
        observationNotice = "";
        estimatedHighWater = 0;
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
        this.locale = locale;
        this.unavailable = unavailable;
        generation++;
        if (delivery != null) delivery.gate(unavailable);
        cancel.setText(messages.getString("cancel"));
        tasks.setText(messages.getString("tasks"));
        detailsToggle.setText(messages.getString("details"));
        copyDetails.setText(messages.getString("copyDetails"));
        intervention.setText(messages.getString("intervention"));
        if (closed || unavailable) {
            timer.stop();
            state.setText("");
            events.setText("");
            details.setText("");
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
        render(snapshot);
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
        java.util.List<String> stages = null;
        Optional<Presentation> projected = Optional.empty();
        boolean storageProblem = false;
        String notice = "";
        try {
            projected = Objects.requireNonNull(presentationLookup.apply(original.id()));
        } catch (
            RuntimeException unavailableProjection) { /* An unavailable projection cannot supply a fraction or decision count. */ }
        try {
            var observed = diagnostics.read(original.id());
            storageProblem = observed.problem().isPresent();
            if (!observed.records().isEmpty()) notice = switch (observed.records().getFirst().event().eventCode()) {
                case "DEPLOYMENT_AGENT_COMMAND_REVIEW_DENIED", "DEPLOYMENT_AGENT_REVIEW_DENIED",
                     "DEPLOYMENT_ASSISTED_REVIEW_DENIED" -> "issue.review";
                case "DEPLOYMENT_CANDIDATE_TRANSFER_FAILURE" -> "issue.transfer";
                case "DEPLOYMENT_AGENT_FAILURE", "DEPLOYMENT_OPERATION_FAILURE", "DEPLOYMENT_EFFECT_OUTCOME" ->
                    "issue.operation";
                case "DEPLOYMENT_AGENT_SESSION_STOPPED" -> "issue.stopped";
                case "DEPLOYMENT_AGENT_TOOL_REJECTED" -> "issue.tool";
                default -> "";
            };
            var stageMessages = new java.util.ArrayList<String>();
            for (var record : observed.records().reversed()) {
                if (!record.event().eventCode().equals("DEPLOYMENT_STAGE")) continue;
                DeploymentTaskStages.identify(record.event().message()).ifPresent(stage -> {
                    if (stageMessages.isEmpty() || !stageMessages.getLast().equals(stage.message()))
                        stageMessages.add(stage.message());
                });
            }
            stages = java.util.List.copyOf(stageMessages);
            text = observed.records().reversed().stream().map(record -> record.recordedAt() + " · "
                + record.event().level() + " · " + record.event().eventCode() + " · " + record.event().message()
                + record.event().outcome().map(value -> " · " + value).orElse("")
                + record.event().details().map(value -> " · " + value).orElse("")
                + (record.truncated() ? " …" : "")).collect(java.util.stream.Collectors.joining("\n"));
            text += observed.problem().map(problem -> "\n" + problem.eventCode()).orElse("");
        } catch (Exception unavailable) {
            text = null;
        }
        String current = text;
        var currentStages = stages;
        var currentProjection = projected;
        boolean currentStorageProblem = storageProblem;
        String currentNotice = notice;
        SwingUtilities.invokeLater(() -> {
            reading.set(false);
            if (closed || unavailable || generation != view || handle != original) return;
            if (current != null) retainedEvents = current;
            if (currentStages != null) retainedStages = currentStages;
            presentation = currentProjection;
            observationUnavailable = current == null || currentStorageProblem;
            if (current != null) observationNotice = currentNotice;
            render(original.snapshot());
            availability();
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
        result = failure != null || completed == null ? "unavailable" : "outcome." + completed.outcome();
        completionDetails = failure != null ? failure.getClass().getSimpleName()
            : completed == null ? "" : completed.outcome() + completed.problem().map(problem -> " · " + problem.eventCode()).orElse("");
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
                        result = "cancelUnavailable";
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
        progressPanel.setVisible(showObservation);
        eventsScroll.setVisible(showObservation);
        detailsToggle.setEnabled(!closed && !unavailable && handle != null);
        detailsPanel.setVisible(showObservation && !closed && !unavailable && detailsToggle.isSelected());
        int pending = handle != null && handle.snapshot().endedAt().isPresent() ? 0
            : presentation.map(Presentation::pendingInterventions).orElse(0);
        interventionCount.setVisible(pending > 0 && !closed && !unavailable);
        intervention.setVisible(pending > 0 && !closed && !unavailable);
        intervention.setEnabled(interventionNavigation != null);
        cancel.setEnabled(!closed && !unavailable && handle != null && !cancellationRequested
            && handle.snapshot().endedAt().isEmpty());
        tasks.setEnabled(!closed && !unavailable);
    }

    /** Renders lifecycle facts separately from estimated progress. / 将生命周期事实与预计进度分开呈现。
     * @param snapshot authentic original observation / 真实原任务观察 */
    private void render(TaskSnapshot snapshot) {
        var stage = DeploymentTaskStages.identify(snapshot.progressText());
        var projection = presentation;
        boolean uncertain = snapshot.state() == gold.debug.windowstolinux.appapi.task.model.TaskState.UNKNOWN
            || projection.map(Presentation::uncertain).orElse(false);
        boolean paused = projection.map(Presentation::paused).orElse(false);
        boolean succeeded = snapshot.endedAt().isPresent() && snapshot.outcome().orElse(null) == TaskResult.SUCCEEDED;
        boolean awaiting = !uncertain && !paused && projection.map(Presentation::pendingInterventions).orElse(0) > 0
            && (snapshot.state() == gold.debug.windowstolinux.appapi.task.model.TaskState.RUNNING
            || snapshot.state() == gold.debug.windowstolinux.appapi.task.model.TaskState.WAITING);
        String status = snapshot.endedAt().isPresent() ? "outcome." + snapshot.outcome().orElseThrow()
            : uncertain ? "outcome.UNKNOWN" : paused ? "paused" : awaiting ? "state.WAITING" : "state." + snapshot.state();
        String action = stage.map(value -> messages.getString(value.message())).orElse(messages.getString("action.pending"));
        StringBuilder summary = new StringBuilder(messages.getString(status));
        if (!succeeded && !awaiting && !paused) summary.append('\n').append(action);
        if (cancellationRequested && snapshot.endedAt().isEmpty())
            summary.append('\n').append(messages.getString("cancelRequested"));
        if (!result.isEmpty() && (result.equals("unavailable") || result.equals("cancelUnavailable")))
            summary.append('\n').append(messages.getString(result));
        if (observationUnavailable) summary.append('\n').append(messages.getString("unavailable"));
        if (!observationNotice.isEmpty() && snapshot.endedAt().isEmpty())
            summary.append('\n').append(messages.getString(observationNotice));
        state.setText(summary.toString());

        Optional<Double> fraction = projection.flatMap(Presentation::fraction);
        boolean estimated = projection.map(Presentation::estimated).orElse(false);
        // Phase estimates apply only to fixed standard workflows, whose stages have a known order.
        // An autonomous workflow can revisit any stage, so it must supply its own bounded projection.
        if (fraction.isEmpty() && !snapshot.type().toLowerCase(Locale.ROOT).contains("agent")
            && stage.filter(value -> value.estimate() >= 0).isPresent()) {
            fraction = Optional.of(stage.orElseThrow().estimate() / 100.0);
            estimated = true;
        }
        if (succeeded) {
            progress.setIndeterminate(false);
            progress.setValue(100);
            percentage.setText("100%");
        } else if (fraction.isPresent()) {
            int percent = Math.min(99, (int) Math.floor(fraction.orElseThrow() * 100));
            if (estimated) {
                estimatedHighWater = Math.max(estimatedHighWater, percent);
                percent = estimatedHighWater;
            }
            progress.setIndeterminate(false);
            progress.setValue(percent);
            percentage.setText((estimated ? messages.getString("estimated") + " " : "") + percent + "%");
        } else {
            progress.setValue(0);
            progress.setIndeterminate(snapshot.endedAt().isEmpty() && !paused);
            percentage.setText(messages.getString("progress.unknown"));
        }
        progress.setToolTipText(messages.getString(estimated ? "progress.estimated" : "progress.measured"));
        int pending = projection.map(Presentation::pendingInterventions).orElse(0);
        interventionCount.setText(new java.text.MessageFormat(messages.getString("interventionCount"), locale).format(new Object[]{pending}));
        events.setText(retainedStages.stream().map(messages::getString).collect(java.util.stream.Collectors.joining("\n")));
        String extraDetails = Objects.requireNonNullElse(additionalDetails.get(), "");
        details.setText(snapshot.id() + " · " + snapshot.state() + "\n" + snapshot.progressText()
            + (snapshot.blockers().isEmpty() ? "" : "\n" + String.join("\n", snapshot.blockers()))
            + (completionDetails.isEmpty() ? "" : "\n" + completionDetails)
            + (retainedEvents.isEmpty() ? "" : "\n" + retainedEvents)
            + (extraDetails.isBlank() ? "" : "\n" + extraDetails));
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
        details.setText("");
        progress.setIndeterminate(false);
        progress.setValue(0);
        percentage.setText("");
        presentation = Optional.empty();
        availability();
    }

    /** Enforces actual Swing ownership. / 强制实际Swing归属。 */
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Feedback requires EDT");
    }
}
