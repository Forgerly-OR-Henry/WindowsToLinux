package gold.debug.windowstolinux.mod.deployment.ui.agent;

import java.awt.BorderLayout;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.*;

import gold.debug.windowstolinux.appapi.server.model.ServerInfo;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.appapi.ui.page.ModPage;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.file.git.DeploymentGitRequest;
import gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentPageService;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentDeliveryPort;
import gold.debug.windowstolinux.mod.deployment.service.core.source.DeploymentSourceSelectionService.LocalLocation;
import gold.debug.windowstolinux.mod.deployment.ui.preference.DeploymentPreferenceSelection;
import gold.debug.windowstolinux.mod.deployment.ui.style.DeploymentPageStyle;
import gold.debug.windowstolinux.mod.deployment.ui.target.DeploymentTargetControls;
import gold.debug.windowstolinux.mod.deployment.ui.flow.DeploymentFlowDraft;

/** Agent-only owner-bound page for local or pinned Git tasks. / 用于本地或固定 Git 任务的自主专属所属页面。 */
public final class DeploymentAgentPage implements ModPage {
    /** Explicit target selection and its related public controls. / 明确目标选择及其关联公开控件。 */
    private static final class TargetSection {
        /** Exact configured target choices. / 精确已配置目标选择。 */
        private final JComboBox<ServerInfo> servers;

        private TargetSection(DeploymentFlowDraft shared) {
            servers = shared == null ? new JComboBox<>() : shared.servers;
        }

        /** Original explicit target and navigation controls. / 原明确目标及导航控件。 */
        private DeploymentTargetControls controls;
    }

    /** Own page admission and persisted task queries. / 所属页面准入与持久任务查询。 */
    private final DeploymentAgentPageService service;

    /** Serial background page actions. / 串行后台页面动作。 */
    private final ExecutorService actions = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "deployment-agent-page");
        thread.setDaemon(true);
        return thread;
    });

    /** Revocable view generation. / 可撤销的视图代次。 */
    private final AtomicLong generation = new AtomicLong();

    /** Root owned by this view. / 此视图持有的根组件。 */
    private final JPanel root = new JPanel(new BorderLayout(8, 8));

    /** Exact configured target choices. / 精确已配置目标选择。 */
    private final TargetSection targets;

    private final DeploymentFlowDraft shared;

    private JComponent advancedSurface;

    /** Actual confirmed preference edits and original write observation. / 实际已确认偏好编辑及原写入观察。 */
    private final DeploymentPreferenceSelection preferences;

    /** Actual preselected local and Git metadata independent of running tasks. / 独立于运行任务的实际预选本地及Git元数据。 */
    private DeploymentAgentSourceControls sources;

    /** Bounded nonsecret human constraints. / 有界且不含秘密的人工约束。 */
    private final JTextArea requirements = new JTextArea(5, 48);

    /** Persisted task feedback visible to the user. / 用户可见的持久任务反馈。 */
    private final JTextArea status = new JTextArea(10, 60);

    /** Actual original evidence and cooperative controls. / 实际原依据及协作控件。 */
    private final DeploymentAgentEvidencePanel evidence;

    /** Original task submission control. / 原任务提交控件。 */
    private final JButton start = new JButton();

    /** Persisted snapshot reload control. / 持久快照重载控件。 */
    private final JButton refresh = new JButton();

    /** Cooperative original task cancellation control. / 原任务协作取消控件。 */
    private final JButton cancel = new JButton();

    /** Locale-bound visible labels. / 依语言变化的可见标签。 */
    private final List<JLabel> labels = new ArrayList<>();

    /** Unanswered short-lived server lookup. / 未回答的短时服务器查询。 */
    private volatile TaskHandle<List<ServerInfo>> lookup;

    /** Accepted task retained independently of this view. / 独立于此视图留存的已接纳任务。 */
    private volatile TaskHandle<DeploymentAgentDeliveryPort.Published> current;

    private volatile gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentGoal goal;

    private javax.swing.Timer goalWatch;

    private AutoCloseable goalSubscription;

    /** Whether this view has closed. / 此视图是否已关闭。 */
    private volatile boolean closed;

    /** Current host lock state. / 当前宿主锁定状态。 */
    private volatile boolean locked;

    /** Current original task feedback independent of the historical recovery view. / 独立于历史恢复视图的当前原任务反馈。 */
    private gold.debug.windowstolinux.mod.deployment.ui.feedback.DeploymentTaskFeedback feedback;

    /** Whether submission is admitted or awaiting completion. / 提交是否已准入或正等待结束。 */
    private volatile boolean busy;

    /** Current locale resource bundle. / 当前语言资源包。 */
    private ResourceBundle messages;

    /** Separate advanced options preserving original fields. / 保留原字段的独立高级选项。 */
    private DeploymentPageStyle.Advanced advanced;

    /** Creates a fresh lazy view of one original agent service. / 为一项原自主服务创建新的惰性视图。
     * @param service owner-bound page operations / 所属页面操作 */
    public DeploymentAgentPage(DeploymentAgentPageService service) {
        this(service, null);
    }

    /** Borrows the flow owner's one common draft; autonomous inputs remain separate. / 借用流程所有者的一份公共草稿，自主专用输入单独保留。 */
    public DeploymentAgentPage(DeploymentAgentPageService service, DeploymentFlowDraft shared) {
        this.service = Objects.requireNonNull(service);
        this.shared = shared;
        targets = new TargetSection(shared);
        preferences = shared != null ? shared.preferences : new DeploymentPreferenceSelection(actions, service::savePreferences,
            () -> "AGENT", selection -> {
        },
            detail -> status.setText(text("preferenceError") + "\n" + detail), this::availability);
        evidence = new DeploymentAgentEvidencePanel(service, actions, () -> current == null ? null : current.id());
        targets.servers.setName("deployment.agent.server");
        targets.servers.setRenderer((list, value, index, selected, focus) -> {
            var label = new JLabel(value == null ? "" : value.name() + " (" + value.host() + ")");
            label.setOpaque(true);
            label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            return label;
        });
    }

    /** Builds the real owner-bound controls on the EDT. / 在 EDT 构建真实所属控件。
     * @param environment host UI state / 宿主界面状态
     * @return page root / 页面根 */
    @Override
    public JComponent create(UiEnvironment environment) {
        requireEdt();
        locale(environment);
        locked = environment.locked();
        feedback = new gold.debug.windowstolinux.mod.deployment.ui.feedback.DeploymentTaskFeedback(service.feedback(),
            actions, () -> {
            try {
                service.openPlatform(gold.debug.windowstolinux.appapi.ui.page.PlatformPageKind.TASKS);
            } catch (ApiException refused) {
                status.setText(text("submitError"));
            }
        }, service::delivery, service.configuration().view());
        if (shared != null) feedback.compactIdle(true);
        feedback.configure(environment);
        root.setBorder(shared == null ? BorderFactory.createEmptyBorder(18, 20, 18, 20) : BorderFactory.createEmptyBorder());
        var sourceFields = DeploymentPageStyle.form();
        var targetFields = DeploymentPageStyle.form();
        if (shared == null) {
            row(targetFields, "server", targets.servers);
            targets.controls = new DeploymentTargetControls(this::text, service::openPlatform, targets.servers,
                this::availability, this::loadServers, status::setText);
            targetFields.add(targets.controls.panel());
            targetFields.add(preferences.panel(this::text));
            sources = new DeploymentAgentSourceControls(service.sources(), this::text, actions, generation::get,
                view -> !closed && !locked && generation.get() == view, this::availability, status::setText, this::availability);
            sourceFields.add(sources.component());
        }
        var gitFields = DeploymentPageStyle.form();
        if (sources != null) sources.git().add((key, value) -> row(gitFields, key, value));
        var advancedFields = DeploymentPageStyle.form();
        if (sources != null) advancedFields.add(DeploymentPageStyle.card("style.git", gitFields));
        var requirementsFields = DeploymentPageStyle.form();
        row(requirementsFields, "requirements", new JScrollPane(requirements));
        advancedFields.add(DeploymentPageStyle.card("style.requirements", requirementsFields));
        advanced = new DeploymentPageStyle.Advanced(root, advancedFields);
        advancedSurface = advancedFields;
        if (shared == null) requirements.setText("applicationId=example-app\nhealthEndpoint=/health");
        var buttons = new JPanel();
        start.addActionListener(ignored -> submit());
        refresh.addActionListener(ignored -> refresh());
        cancel.addActionListener(ignored -> cancel());
        buttons.add(start);
        buttons.add(refresh);
        buttons.add(cancel);
        buttons.add(advanced.button());
        start.putClientProperty("deployment.style.primary", true);
        status.setEditable(false);
        if (shared == null)
            root.add(DeploymentPageStyle.selections(DeploymentPageStyle.card("style.source", sourceFields),
                DeploymentPageStyle.card("style.server", targetFields)), BorderLayout.NORTH);
        var live = new JPanel(new BorderLayout(4, 4));
        live.add(new JScrollPane(status), BorderLayout.NORTH);
        live.add(feedback.component(), BorderLayout.CENTER);
        var observed = new JSplitPane(JSplitPane.VERTICAL_SPLIT, live, evidence.component());
        observed.setResizeWeight(0.55);
        root.add(shared == null ? DeploymentPageStyle.card("style.results", observed) : observed, BorderLayout.CENTER);
        if (shared == null) root.add(buttons, BorderLayout.SOUTH);
        else {
            var goalActions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT));
            cancel.setName("deployment.agent.cancelGoal");
            goalActions.add(cancel);
            root.add(goalActions, BorderLayout.SOUTH);
        }
        if (shared == null) DeploymentPageStyle.scrollPage(root);
        localize();
        if (shared == null) {
            loadPreferences();
            loadServers();
        }
        refresh();
        return root;
    }

    /** Applies a new locale or lock and revokes only an unanswered lookup. / 应用新语言或锁定，并仅撤销未回答查询。
     * @param environment current host UI state / 当前宿主界面状态 */
    @Override
    public void environmentChanged(UiEnvironment environment) {
        requireEdt();
        locale(environment);
        locked = environment.locked();
        localize();
        if (feedback != null) feedback.configure(environment);
        if (locked) {
            generation.incrementAndGet();
            cancelLookup();
            if (sources != null) sources.revoke();
        } else {
            if (shared == null && !preferences.ready())
                loadPreferences();
            if (shared == null && targets.servers.getItemCount() == 0)
                loadServers();
        }
        availability();
    }

    /** Closes only this view; accepted original agent tasks remain observable. / 仅关闭此视图；已接纳原自主任务仍可观察。 */
    @Override
    public void close() {
        requireEdt();
        closed = true;
        if (goalWatch != null) goalWatch.stop();
        closeGoalSubscription();
        if (feedback != null) feedback.close();
        if (advanced != null)
            advanced.close();
        if (shared == null) preferences.close();
        evidence.configure(messages, true);
        generation.incrementAndGet();
        cancelLookup();
        if (sources != null) sources.revoke();
        actions.shutdown();
    }

    /** Adds one localized form label with its control. / 添加一项带控件的本地化表单标签。
     * @param form original control container / 原控件容器
     * @param key message key / 文案键
     * @param value control / 控件 */
    private void row(JPanel form, String key, JComponent value) {
        if (value.getName() == null)
            value.setName("deployment.agent." + key);
        if (value instanceof JComboBox<?> choice && value != targets.servers)
            choice.setRenderer((list, option, index, selected, focused) -> new DefaultListCellRenderer()
                .getListCellRendererComponent(list, option == null ? "" : text("choice." + option), index, selected,
                    focused));
        var label = new JLabel();
        label.putClientProperty("key", key);
        labels.add(label);
        DeploymentPageStyle.field(form, label, value);
    }

    /** Restores the actual approval choice on a worker without overwriting a live draft on locale changes. / 在工作线程恢复实际审批选择，语言变化不覆盖活动草稿。 */
    private void loadPreferences() {
        preferences.editable(false);
        long view = generation.get();
        actions.execute(() -> {
            try {
                var handle = service.preferences();
                handle.completion().whenComplete((result, error) -> SwingUtilities.invokeLater(() -> {
                    if (closed || locked || generation.get() != view)
                        return;
                    if (error != null || result == null || result.outcome() != TaskResult.SUCCEEDED || result.value().isEmpty()) {
                        status.setText(text("preferenceError"));
                        return;
                    }
                    preferences.load(result.value().orElseThrow());
                    availability();
                }));
            } catch (Exception failure) {
                SwingUtilities.invokeLater(() -> {
                    if (!closed && generation.get() == view)
                        status.setText(text("preferenceError"));
                });
            }
        });
    }

    /** Reads only configured servers in a short separate task. / 仅在独立短任务中读取已配置服务器。 */
    private void loadServers() {
        long view = generation.get();
        actions.execute(() -> {
            try {
                var handle = service.servers();
                lookup = handle;
                if (closed || locked || generation.get() != view)
                    handle.requestCancel();
                handle.completion().whenComplete((result, error) -> SwingUtilities.invokeLater(() -> {
                    if (lookup == handle)
                        lookup = null;
                    if (closed || locked || generation.get() != view)
                        return;
                    if (error != null || result == null || result.outcome() != TaskResult.SUCCEEDED || result.value().isEmpty()) {
                        status.setText(text("serverError"));
                        return;
                    }
                    targets.controls.replace(result.value().orElseThrow());
                    availability();
                }));
            } catch (Exception failure) {
                SwingUtilities.invokeLater(() -> {
                    if (!closed && generation.get() == view)
                        status.setText(text("serverError"));
                });
            }
        });
    }

    /** Submits exactly the visible local or Git source and bounded policy. / 精确提交可见本地或 Git 来源与有界策略。 */
    private void submit() {
        requireEdt();
        if (closed || locked || busy || (shared == null ? sources.busy() || !sources.ready() : !shared.ready())
            || !preferences.ready() || targets.servers.getSelectedItem() == null)
            return;
        final Submission input;
        try {
            input = capture();
        } catch (RuntimeException invalid) {
            status.setText(text("invalid"));
            return;
        }
        busy = true;
        availability();
        long view = generation.get();
        actions.execute(() -> {
            try {
                var handle = input.git().isPresent()
                    ? service.submitGit(input.server().ref(), input.request(), input.git().orElseThrow(),
                    input.executable(), input.name())
                    : service.submitLocal(input.server().ref(), input.request(), input.local().orElseThrow());
                current = handle;
                goal = service.goal(handle.id()).orElse(null);
                SwingUtilities.invokeLater(() -> {
                    if (!closed && current == handle) {
                        feedback.watch(handle);
                        if (goal != null) watchGoal(goal);
                    }
                });
                var completed = goal == null ? handle.completion() : goal.completion();
                completed.whenComplete((result, failure) -> {
                    busy = false;
                    SwingUtilities.invokeLater(() -> {
                        if (!closed) {
                            availability();
                            if (generation.get() == view)
                                refresh();
                        }
                    });
                });
                refresh();
            } catch (Exception failure) {
                busy = false;
                SwingUtilities.invokeLater(() -> {
                    if (!closed) {
                        if (generation.get() == view)
                            status.setText(text("submitError") + ": " + failure.getClass().getSimpleName());
                        availability();
                    }
                });
            }
        });
    }

    private void watchGoal(gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentGoal selected) {
        if (goalWatch != null) goalWatch.stop();
        closeGoalSubscription();
        goalSubscription = selected.watch(handle -> SwingUtilities.invokeLater(() -> {
            if (!closed && goal == selected) {
                current = handle;
                feedback.watch(handle);
                availability();
            }
        }));
        goalWatch = new javax.swing.Timer(service.configuration().view().refreshMillis(), ignored -> {
            if (closed || goal != selected) {
                ((javax.swing.Timer) ignored.getSource()).stop();
                return;
            }
            var observed = selected.observation();
            if (!observed.state().equals("RUNNING")) {
                busy = false;
                ((javax.swing.Timer) ignored.getSource()).stop();
                availability();
                refresh();
            }
        });
        goalWatch.start();
    }

    private void closeGoalSubscription() {
        if (goalSubscription == null) return;
        try {
            goalSubscription.close();
        } catch (Exception invalid) {
            throw new IllegalStateException("Goal view subscription changed", invalid);
        }
        goalSubscription = null;
    }

    /** Captures only bounded, nonsecret EDT form values. / 仅抓取 EDT 中有界且不含秘密的表单值。
     * @return immutable submission / 不可变提交 */
    private Submission capture() {
        var facts = new LinkedHashMap<String, String>();
        for (String line : requirements.getText().split("\\R")) {
            if (line.isBlank())
                continue;
            int separator = line.indexOf('=');
            if (separator < 1 || separator == line.length() - 1)
                throw new IllegalArgumentException("Requirement needs key=value");
            if (facts.putIfAbsent(line.substring(0, separator).trim(), line.substring(separator + 1).trim()) != null)
                throw new IllegalArgumentException("Duplicate requirement");
        }
        var request = new DeploymentAgentInputs.Request(facts, preferences.approval(), preferences.rememberAuthorization(),
            service.configuration().source().bytes(), service.configuration().source().entries(),
            service.configuration().source().archiveBytes());
        var selected = (ServerInfo) targets.servers.getSelectedItem();
        if (shared != null) {
            if (!shared.gitSelected())
                return new Submission(selected, request, shared.localLocation(), Optional.empty(), null, "");
            if (shared.name.getText().isBlank()) throw new IllegalArgumentException("Project name missing");
            var git = shared.gitSource(service.configuration().source().archiveBytes());
            return new Submission(selected, request, Optional.empty(), Optional.of(git.request()), git.executable(), shared.name.getText().trim());
        }
        if (!sources.gitSelected())
            return new Submission(selected, request, sources.localLocation(), Optional.empty(), null, "");
        var git = sources.captureGit();
        return new Submission(selected, request, Optional.empty(), Optional.of(git.request()), git.executable(), git.name());
    }

    /** Frozen UI admission values. / 冻结的界面准入值。
     * @param server selected server / 所选服务器
     * @param request policy / 策略
     * @param local previously completed picker position metadata / 此前已完成选择器位置元数据
     * @param git optional Git source / 可选 Git 来源
     * @param executable optional native Git binary / 可选原生 Git 程序
     * @param name reviewed saved name / 经审阅已存名称 */
    private record Submission(ServerInfo server, DeploymentAgentInputs.Request request,
                              Optional<LocalLocation> local, Optional<DeploymentGitRequest> git, Path executable,
                              String name) {
    }

    /** Reads original persisted task snapshots without resetting the engine. / 读取原持久任务快照而不重置引擎。 */
    private void refresh() {
        if (closed || actions.isShutdown())
            return;
        long view = generation.get();
        actions.execute(() -> {
            try {
                var snapshots = service.tasks();
                var shown = snapshots.stream()
                    .map(item -> item.id() + "  " + item.state() + "  "
                        + item.outcome().map(Object::toString).orElse(item.progressText()))
                    .collect(java.util.stream.Collectors.joining("\n"));
                SwingUtilities.invokeLater(() -> {
                    if (!closed && !locked && generation.get() == view)
                        status.setText((shown.isBlank() ? text("noTasks") : shown) + "\n" + preferences.diagnostic()
                            + (goal == null ? "" : "\n" + goal.observation().root() + " → " + goal.observation().current()
                            + "  " + goal.observation().state()));
                });
            } catch (Exception failure) {
                SwingUtilities.invokeLater(() -> {
                    if (!closed && !locked && generation.get() == view)
                        status.setText(text("taskError"));
                });
            }
        });
    }

    /** Requests cooperative cancellation of only the displayed original task. / 仅请求取消所显示原任务的协作执行。 */
    private void cancel() {
        var handle = current;
        if (handle == null)
            return;
        actions.execute(() -> {
            try {
                if (goal == null) handle.requestCancel();
                else goal.cancel();
            } catch (ApiException unavailable) {
                SwingUtilities.invokeLater(() -> status.setText(text("cancelError")));
            }
            refresh();
        });
    }

    /** Updates locale-bound visible text and lock availability. / 更新依语言变化的可见文案与锁定可用性。 */
    private void localize() {
        preferences.localize(this::text);
        labels.forEach(label -> label.setText(text((String) label.getClientProperty("key"))));
        start.setText(text("start"));
        refresh.setText(text("refresh"));
        cancel.setText(text(shared == null ? "cancel" : "cancelGoal"));
        if (targets.controls != null) targets.controls.localize();
        evidence.configure(messages, closed || locked);
        if (sources != null) sources.localize();
        if (advanced != null)
            advanced.environment(messages, locked);
        DeploymentPageStyle.refresh(root, messages);
        availability();
    }

    /** Stores current host locale. / 保存当前宿主语言。
     * @param environment host state / 宿主状态 */
    private void locale(UiEnvironment environment) {
        messages = ResourceBundle.getBundle("gold.debug.windowstolinux.mod.deployment.ui.agent.deployment-agent-page",
            environment.locale(),
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_DEFAULT));
    }

    /** Looks up one owned localized message. / 查找一项所属本地化文案。
     * @param key message key / 文案键
     * @return localized text / 本地化文本 */
    private String text(String key) {
        return messages.getString(key);
    }

    /** Reflects current target, owner and lock state. / 反映当前目标、所属者和锁定状态。 */
    private void availability() {
        boolean sourceBusy = sources != null && sources.busy();
        boolean enabled = !closed && !locked && !busy && !sourceBusy;
        start.setEnabled(enabled && sources != null && sources.ready() && preferences.ready() && targets.servers.getSelectedItem() != null);
        if (targets.controls != null) targets.controls.availability(enabled, enabled);
        if (sources != null) sources.availability(!closed && !locked && !busy, !closed && !locked && !busy);
        if (shared == null) preferences.editable(enabled && preferences.ready());
        requirements.setEnabled(enabled && (shared == null || !shared.frozen));
        refresh.setEnabled(!closed && !locked);
        cancel.setEnabled(!closed && !locked && current != null);
    }

    public JComponent flowResults() {
        return root;
    }

    public JComponent flowAdvanced() {
        return advancedSurface;
    }

    public void flowSubmit() {
        submit();
    }

    public boolean flowBusy() {
        return busy || goal != null && !goal.completion().toCompletableFuture().isDone();
    }

    public java.util.UUID flowTask() {
        return goal != null ? goal.observation().root() : current == null ? null : current.id();
    }

    public void flowRefresh() {
        refresh();
    }

    /** Cancels only an in-flight read-only view query. / 仅取消进行中的视图只读查询。 */
    private void cancelLookup() {
        var handle = lookup;
        if (handle != null)
            try {
                handle.requestCancel();
            } catch (ApiException unavailable) {
                // The short original query keeps its own retained result. / 原短查询自行保留结果。
            }
    }

    /** Enforces the host Swing owner thread. / 强制宿主 Swing 所属线程。 */
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Agent page requires EDT");
    }
}
