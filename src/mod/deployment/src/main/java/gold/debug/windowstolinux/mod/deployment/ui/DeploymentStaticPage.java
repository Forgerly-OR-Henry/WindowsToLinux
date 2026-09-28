package gold.debug.windowstolinux.mod.deployment.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.*;

import gold.debug.windowstolinux.appapi.server.ServerInfo;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.appapi.ui.ModPage;
import gold.debug.windowstolinux.appapi.ui.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentSecretSelection;
import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticPageService;
import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticWorkflow;

/** Owner-bound static deployment page; task callbacks remain with their original task after a confirmed view closes. / 绑定所属者的静态部署页面；确认后视图关闭，任务回调仍留在原任务。 */
public final class DeploymentStaticPage implements ModPage {
    /** Supported source form modes. / 支持的来源表单模式。 */
    private enum SourceMode {
        /** Original local directory chooser. / 原本地目录选择器。 */
        LOCAL,
        /** One pinned Git request. / 一项固定 Git 请求。 */
        GIT
    }

    /** Mod-owned task and read-only query service. / Mod 所属任务及只读查询服务。 */
    private final DeploymentStaticPageService service;

    /** View-local background actions, never the publication worker. / 仅视图本地后台操作，非发布工作线程。 */
    private final ExecutorService actions = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "deployment-page-view");
        thread.setDaemon(true);
        return thread;
    });

    /** Invalidates stale chooser and review callbacks. / 撤销迟到的选材与审阅回调。 */
    private final AtomicLong generation = new AtomicLong();

    /** Root owned component. / 所属根组件。 */
    private final JPanel root = new JPanel(new BorderLayout(8, 8));

    /** Actual platform server choices. / 实际平台服务器选择。 */
    private final JComboBox<ServerInfo> servers = new JComboBox<>();

    /** Reviewed logical source name. / 经审阅逻辑来源名。 */
    private final JTextField sourceName = new JTextField(24);

    /** Explicit source mode and Git inputs. / 明确来源模式及 Git 输入。 */
    private final JComboBox<SourceMode> sourceMode = new JComboBox<>(SourceMode.values());

    /** Git-only input component created after the locale is known. / 语言确定后创建的仅 Git 输入组件。 */
    private DeploymentGitSourceForm gitForm;

    /** Pre-publication secret choices and one-shot creation controls. / 发布前秘密选项及一次性创建控件。 */
    private DeploymentStaticSecretControls secretControls;

    /** Current and previously retained task observations. / 当前及之前保留的任务观察。 */
    private final JTextArea status = new JTextArea(10, 60);

    /** Start action. / 启动操作。 */
    private final JButton start = new JButton();

    /** Current server label and source label. / 当前服务器与来源标签。 */
    private final JLabel serverLabel = new JLabel(), sourceLabel = new JLabel();

    /** Localized source mode label. / 本地化来源方式标签。 */
    private final JLabel modeLabel = new JLabel();

    /** Reload action. / 重新读取操作。 */
    private final JButton refreshButton = new JButton();

    /** Current localized messages. / 当前本地化消息。 */
    private ResourceBundle messages;

    /** Current environment lock. / 当前环境锁。 */
    private volatile boolean locked;

    /** View lifetime. / 视图寿命。 */
    private volatile boolean closed;

    /** One modal dialog, disposed when this owner closes or locks. / 单个模态对话框，所属者关闭或锁定时销毁。 */
    private JDialog dialog;

    /** A pending unconfirmed owner review. / 一项待确认所属审阅。 */
    private volatile boolean pendingReview;

    /** An independent secret task is being admitted or observed. / 独立秘密任务正在准入或观察。 */
    private volatile boolean secretBusy;

    /** Original admitted task while this view owns unanswered decisions. / 本视图仍持有待答决定时的原已准入任务。 */
    private volatile TaskHandle<Void> currentTask;

    /** Original read-only lookup while this view owns its result. / 当前视图持有结果期间的原只读查询。 */
    private volatile TaskHandle<List<ServerInfo>> serverLookup;

    /** Final confirmation was accepted before any later view revocation. / 最终确认已在后续视图撤销前接受。 */
    private volatile boolean confirmed;

    /** Last safe result from this view's original task. / 当前视图原任务最近一次安全结果。 */
    private volatile Problem recentProblem;

    /** Creates one fresh page instance for a lazy registration. / 为惰性登记创建新的页面实例。
     * @param service Mod-owned page task service / Mod 所属页面任务服务 */
    public DeploymentStaticPage(DeploymentStaticPageService service) {
        this.service = java.util.Objects.requireNonNull(service);
        servers.setRenderer((list, value, index, selected, focus) -> {
            var label = new JLabel(value == null ? "" : value.name() + " (" + value.host() + ")");
            label.setOpaque(true);
            label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            return label;
        });
    }

    /** Builds the actual local page on the EDT. / 在 EDT 构建真实本地页面。
     * @param environment host environment / 宿主环境
     * @return owned Swing page / 所属 Swing 页面 */
    @Override
    public JComponent create(UiEnvironment environment) {
        requireEdt();
        messages = ResourceBundle.getBundle("gold.debug.windowstolinux.mod.deployment.ui.deployment-page",
                environment.locale(),
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_DEFAULT));
        locked = environment.locked();
        var controls = new JPanel(new GridLayout(0, 2, 8, 8));
        serverLabel.setText(text("server"));
        controls.add(serverLabel);
        controls.add(servers);
        sourceLabel.setText(text("sourceName"));
        controls.add(sourceLabel);
        controls.add(sourceName);
        modeLabel.setText(text("sourceMode"));
        controls.add(modeLabel);
        controls.add(sourceMode);
        gitForm = new DeploymentGitSourceForm(this);
        sourceMode.setRenderer((list, value, index, selected, focus) -> {
            var label = new JLabel(value == null ? "" : text("sourceMode." + value.name()));
            label.setOpaque(true);
            label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            return label;
        });
        sourceMode.addActionListener(ignored -> updateSourceMode());
        updateSourceMode();
        var buttons = new JPanel();
        start.setText(text("start"));
        start.addActionListener(ignored -> submit());
        refreshButton.setText(text("refresh"));
        refreshButton.addActionListener(ignored -> refresh());
        buttons.add(start);
        buttons.add(refreshButton);
        var sourceControls = new JPanel();
        sourceControls.setLayout(new BoxLayout(sourceControls, BoxLayout.Y_AXIS));
        sourceControls.add(controls);
        sourceControls.add(gitForm.panel());
        secretControls = new DeploymentStaticSecretControls(this, service);
        sourceControls.add(secretControls.panel());
        root.add(sourceControls, BorderLayout.NORTH);
        status.setEditable(false);
        root.add(new JScrollPane(status), BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        start.setEnabled(false);
        loadServers();
        secretControls.refresh(generation.get());
        refresh();
        return root;
    }

    /** Revokes unanswered review on lock; accepted publication continues. / 锁定时撤销未答审阅；已接受发布继续。
     * @param environment current host state / 当前宿主状态 */
    @Override
    public void environmentChanged(UiEnvironment environment) {
        requireEdt();
        locked = environment.locked();
        messages = ResourceBundle.getBundle("gold.debug.windowstolinux.mod.deployment.ui.deployment-page",
                environment.locale(),
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_DEFAULT));
        serverLabel.setText(text("server"));
        sourceLabel.setText(text(sourceMode.getSelectedItem() == SourceMode.GIT ? "sourceNameGit" : "sourceName"));
        modeLabel.setText(text("sourceMode"));
        gitForm.localize();
        secretControls.localize();
        secretControls.updateAvailability();
        sourceMode.repaint();
        refreshButton.setText(text("refresh"));
        start.setText(text("start"));
        start.setEnabled(!closed && !locked && !pendingReview && !secretBusy && servers.getSelectedItem() != null);
        if (locked)
            revoke();
        else {
            if (servers.getItemCount() == 0)
                loadServers();
            secretControls.refresh(generation.get());
            refresh();
        }
    }

    /** Releases this view and pending dialogs, never an accepted publication task. / 释放视图及待答对话框，绝不取消已接纳发布任务。 */
    @Override
    public void close() {
        requireEdt();
        closed = true;
        revoke();
        secretControls.close();
        actions.shutdown();
    }

    /** Lists server records in a separate short task off the EDT. / 在 EDT 外的独立短任务中列出服务器记录。 */
    private void loadServers() {
        long view = generation.get();
        try {
            actions.execute(() -> {
                if (closed || locked || generation.get() != view)
                    return;
                try {
                    var handle = service.servers();
                    serverLookup = handle;
                    if (closed || locked || generation.get() != view)
                        cancelLookup(handle);
                    handle.completion().whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
                        if (serverLookup == handle)
                            serverLookup = null;
                        if (closed || locked || generation.get() != view)
                            return;
                        if (failure != null || result.outcome() != Outcome.SUCCEEDED) {
                            status.setText(text("serverError"));
                            return;
                        }
                        servers.removeAllItems();
                        result.value().orElseThrow().forEach(servers::addItem);
                        start.setEnabled(!pendingReview && !secretBusy && servers.getItemCount() > 0);
                        secretControls.updateAvailability();
                    }));
                } catch (Exception failure) {
                    SwingUtilities.invokeLater(() -> {
                        if (!closed && !locked && generation.get() == view)
                            status.setText(text("serverError"));
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException closedNow) {
            // A closing view no longer owns a server-list result.
        }
    }

    /** Keeps Git inputs visible only for the selected source mode. / 仅在选定 Git 来源模式显示可用 Git 输入。 */
    private void updateSourceMode() {
        boolean git = sourceMode.getSelectedItem() == SourceMode.GIT;
        sourceLabel.setText(text(git ? "sourceNameGit" : "sourceName"));
        if (gitForm != null)
            gitForm.visible(git);
        root.revalidate();
    }

    /** Submits the selected server and source name through one original publication task. / 将选定服务器及来源名提交为一项原发布任务。 */
    private void submit() {
        requireEdt();
        if (closed || locked || pendingReview || secretBusy || servers.getSelectedItem() == null)
            return;
        var server = ((ServerInfo) servers.getSelectedItem()).ref();
        var name = sourceName.getText().trim();
        var mode = (SourceMode) sourceMode.getSelectedItem();
        Optional<DeploymentStaticWorkflow.GitSource> git;
        try {
            if (mode == SourceMode.GIT && name.isBlank())
                throw new IllegalArgumentException("Git project name is required");
            git = mode == SourceMode.GIT ? Optional.of(gitForm.source()) : Optional.empty();
        } catch (IllegalArgumentException invalid) {
            status.setText(text("invalidGitInput"));
            return;
        }
        long view = generation.get();
        pendingReview = true;
        secretControls.updateAvailability();
        confirmed = false;
        recentProblem = null;
        start.setEnabled(false);
        try {
            actions.execute(() -> {
                if (closed || locked || generation.get() != view) {
                    pendingReview = false;
                    return;
                }
                boolean admitted = false;
                try {
                    var handle = service
                            .submit(server,
                                    new DeploymentStaticWorkflow.Draft(name, 2L * 1024 * 1024 * 1024, 100_000,
                                            2L * 1024 * 1024 * 1024),
                                    git, new DeploymentStaticPageReviewer(this, view));
                    admitted = true;
                    currentTask = handle;
                    if (closed || locked || generation.get() != view)
                        cancelUnconfirmed(handle);
                    handle.completion().whenComplete((result, failure) -> {
                        pendingReview = false;
                        if (currentTask == handle)
                            currentTask = null;
                        SwingUtilities.invokeLater(secretControls::updateAvailability);
                        if (active(view) && failure == null && result != null)
                            recentProblem = result.problem().orElse(null);
                        refresh();
                        SwingUtilities.invokeLater(() -> start
                                .setEnabled(!closed && !locked && !secretBusy && servers.getItemCount() > 0));
                    });
                    refresh();
                } catch (Exception failure) {
                    SwingUtilities.invokeLater(() -> {
                        if (!closed && !locked && generation.get() == view)
                            status.setText(text(
                                    failure instanceof IllegalArgumentException ? "invalidGitInput" : "submitError"));
                    });
                } finally {
                    if (!admitted) {
                        pendingReview = false;
                        SwingUtilities.invokeLater(secretControls::updateAvailability);
                        SwingUtilities.invokeLater(() -> start
                                .setEnabled(!closed && !locked && !secretBusy && servers.getItemCount() > 0));
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException closedNow) {
            pendingReview = false;
        }
    }

    /** Reads persisted task snapshots in the background so reopening sees the original task. / 后台读取持久任务快照，使重新打开可见原任务。 */
    private void refresh() {
        if (closed || actions.isShutdown())
            return;
        long view = generation.get();
        try {
            actions.execute(() -> {
                try {
                    var tasks = service.publications();
                    var shown = tasks.stream()
                            .map(item -> item.id() + "  " + item.state() + "  "
                                    + item.outcome().map(Object::toString).orElse(item.progressText()))
                            .collect(java.util.stream.Collectors.joining("\n"));
                    SwingUtilities.invokeLater(() -> {
                        if (!closed && !locked && generation.get() == view) {
                            var problem = recentProblem;
                            String diagnostic = problem == null
                                    ? ""
                                    : "\n" + problem.eventCode() + ": "
                                            + (problem.eventCode().equals("DEPLOYMENT_SCHEMA_REVIEW_REQUIRED")
                                                    ? text("schemaReviewRequired")
                                                    : problem.message());
                            status.setText((shown.isEmpty() ? text("noTasks") : shown) + diagnostic);
                        }
                    });
                } catch (Exception failure) {
                    SwingUtilities.invokeLater(() -> {
                        if (!closed && !locked && generation.get() == view)
                            status.setText(text("taskError"));
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException closedNow) {
            // The view closed between the status check and scheduling.
        }
    }

    /** Disposes the exact current dialog and invalidates its decision. / 销毁当前精确对话框并撤销其决定。 */
    private void revoke() {
        generation.incrementAndGet();
        var lookup = serverLookup;
        if (lookup != null)
            cancelLookup(lookup);
        var handle = currentTask;
        if (handle != null && !confirmed)
            try {
                actions.execute(() -> cancelUnconfirmed(handle));
            } catch (java.util.concurrent.RejectedExecutionException closedNow) {
                // The owner already stopped; the review generation remains revoked.
            }
        if (dialog != null) {
            dialog.dispose();
            dialog = null;
        }
        if (secretControls != null)
            secretControls.revoke();
    }

    /** Cancels only a view-owned read-only query. / 仅取消视图所属只读查询。
     * @param handle original server-list task / 原服务器列表任务 */
    private static void cancelLookup(TaskHandle<List<ServerInfo>> handle) {
        try {
            handle.requestCancel();
        } catch (ApiException unavailable) {
            // The original short task retains its own completion or recovery.
        }
    }

    /** Cooperatively cancels only a task still awaiting this view's final consent. / 仅协作取消仍等待本视图最终同意的任务。
     * @param handle original publication task / 原发布任务 */
    private void cancelUnconfirmed(TaskHandle<Void> handle) {
        if (confirmed)
            return;
        try {
            handle.requestCancel();
        } catch (ApiException failed) {
            // The original task retains its own uncertain-effect evidence.
        }
    }

    /** Runs one owner-bound modal form on the EDT and reports only a current choice. / 在 EDT 运行一份所属模态表单且仅报告当前选择。
     * @param view original view generation / 原视图代次
     * @param panel typed review controls / 类型化审阅控件
     * @param title localized title / 本地化标题
     * @param task original task / 原任务
     * @return true only after current approval / 仅当前同意返回真 */
    boolean approve(long view, JPanel panel, String title, TaskContext task) {
        return approve(view, panel, title, task, false);
    }

    /** Confirms a form and atomically hands an accepted final decision to the background task. / 确认表单，并将已接受的最终决定原子交给后台任务。
     * @param view original generation / 原代次
     * @param panel actual controls / 实际控件
     * @param title localized title / 本地化标题
     * @param task original task / 原任务
     * @param finalReview whether this is final consent / 是否为最终同意
     * @return current approval / 当前同意 */
    boolean approve(long view, JPanel panel, String title, TaskContext task, boolean finalReview) {
        return approve(view, panel, title, task, finalReview, () -> {
        });
    }

    /** Records a secret consent on the EDT before the dialog result is handed to its worker. / 在对话框结果交给工作线程前于 EDT 记录秘密同意。
     * @param view original generation / 原代次
     * @param panel actual controls / 实际控件
     * @param title localized title / 本地化标题
     * @param task original task / 原任务
     * @param onAccepted owner action performed before returning approval / 返回同意前执行的所属操作
     * @return current approval / 当前同意 */
    boolean approveSecret(long view, JPanel panel, String title, TaskContext task, Runnable onAccepted) {
        return approve(view, panel, title, task, false, onAccepted);
    }

    /** Performs the shared owner-bound dialog and its accepted-state transition. / 执行共同的所属对话框及接受状态转换。
     * @param view original generation / 原代次
     * @param panel actual controls / 实际控件
     * @param title localized title / 本地化标题
     * @param task original task / 原任务
     * @param finalReview whether this is publication consent / 是否发布最终同意
     * @param onAccepted additional same-EDT accepted transition / 同 EDT 的附加接受转换
     * @return current approval / 当前同意 */
    private boolean approve(long view, JPanel panel, String title, TaskContext task, boolean finalReview,
            Runnable onAccepted) {
        if (closed || locked || generation.get() != view || task.cancellation().requested())
            return false;
        var answer = new java.util.concurrent.CompletableFuture<Boolean>();
        SwingUtilities.invokeLater(() -> {
            if (closed || locked || generation.get() != view) {
                answer.complete(false);
                return;
            }
            JDialog owned = null;
            try {
                var scroll = new JScrollPane(panel);
                var screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
                scroll.setPreferredSize(new Dimension(Math.min(760, Math.max(320, screen.width - 120)),
                        Math.min(480, Math.max(260, screen.height - 160))));
                var pane = new JOptionPane(scroll, JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
                owned = pane.createDialog(root, title);
                dialog = owned;
                owned.setVisible(true);
                boolean accepted = !closed && !locked && generation.get() == view
                        && Integer.valueOf(JOptionPane.OK_OPTION).equals(pane.getValue());
                if (accepted && finalReview)
                    confirmed = true;
                if (accepted)
                    onAccepted.run();
                answer.complete(accepted);
            } catch (RuntimeException failed) {
                answer.complete(false);
            } finally {
                if (owned != null)
                    owned.dispose();
                if (dialog == owned)
                    dialog = null;
                answer.complete(false);
            }
        });
        try {
            while (!answer.isDone()) {
                if (closed || locked || generation.get() != view || task.cancellation().requested()) {
                    SwingUtilities.invokeLater(this::revoke);
                    return false;
                }
                try {
                    return answer.get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException pending) {
                    // Poll the task and owner generation; the modal dialog itself remains on the EDT.
                }
            }
            return answer.getNow(false);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            SwingUtilities.invokeLater(this::revoke);
            return false;
        } catch (java.util.concurrent.ExecutionException failed) {
            return false;
        }
    }

    /** Creates or reads one Swing form only on the EDT. / 仅在 EDT 创建或读取一份 Swing 表单。
     * @param work form action / 表单操作
     * @param <T> return type / 返回类型
     * @return EDT result / EDT 结果 */
    <T> T onEdt(java.util.concurrent.Callable<T> work) {
        if (SwingUtilities.isEventDispatchThread())
            try {
                return work.call();
            } catch (Exception failure) {
                throw new IllegalStateException("Review form failed", failure);
            }
        var result = new java.util.concurrent.atomic.AtomicReference<T>();
        var failure = new java.util.concurrent.atomic.AtomicReference<Exception>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    result.set(work.call());
                } catch (Exception invalid) {
                    failure.set(invalid);
                }
            });
        } catch (Exception unavailable) {
            throw new IllegalStateException("Review EDT unavailable", unavailable);
        }
        if (failure.get() != null)
            throw new IllegalArgumentException("Review form input is invalid", failure.get());
        return result.get();
    }

    /** Checks that a delayed callback still belongs to this unlocked view. / 检查迟到回调仍属当前未锁视图。
     * @param view original generation / 原代次
     * @return true only for current view / 仅当前视图为真 */
    boolean active(long view) {
        return !closed && !locked && generation.get() == view;
    }

    /** Schedules a view action without giving it publication ownership. / 调度视图操作而不授予发布所有权。
     * @param action short page action / 短页面操作 */
    void execute(Runnable action) {
        actions.execute(action);
    }

    /** Returns the current owner generation. / 返回当前所属代次。
     * @return generation / 代次 */
    long viewGeneration() {
        return generation.get();
    }

    /** Returns the selected visible server, if any. / 返回当前可见选定服务器。
     * @return selected server / 选定服务器 */
    Optional<ServerRef> selectedServer() {
        var selected = servers.getSelectedItem();
        return selected instanceof ServerInfo info ? Optional.of(info.ref()) : Optional.empty();
    }

    /** Exposes only purpose-backed business choices to review forms. / 仅向审阅表单提供有原用途的业务选项。
     * @return current choices / 当前选项 */
    List<DeploymentSecretSelection> secretChoices() {
        return secretControls.choices();
    }

    /** Checks whether a separate secret task can be admitted before publication. / 检查独立秘密任务可否在发布前准入。
     * @return true only for a current unlocked idle view / 仅当前未锁空闲视图为真 */
    boolean canCreateSecret() {
        return !closed && !locked && !pendingReview && !secretBusy && selectedServer().isPresent();
    }

    /** Blocks publication while one pre-publication secret task is in progress. / 发布前秘密任务进行时阻止发布。
     * @param busy whether a task is active / 是否有活动任务 */
    void secretBusy(boolean busy) {
        secretBusy = busy;
        SwingUtilities.invokeLater(() -> start
                .setEnabled(!closed && !locked && !pendingReview && !secretBusy && servers.getSelectedItem() != null));
        SwingUtilities.invokeLater(secretControls::updateAvailability);
    }

    /** Validates a form before allowing it to leave the same owner review. / 表单离开同一所属审阅前先校验。
     * @param prompt original questions / 原问题
     * @param answer edited values / 已编辑值 */
    void validateReview(DeploymentStaticWorkflow.Prompt prompt, DeploymentStaticWorkflow.Answers answer) {
        service.validateReview(prompt, answer);
    }

    /** Resolves one localized Mod-owned message. / 解析一条 Mod 自有本地化消息。
     * @param key message key / 消息键
     * @return localized text / 本地化文本 */
    String text(String key) {
        return messages == null ? key : messages.getString(key);
    }

    /** Rejects incorrect Swing callers. / 拒绝错误的 Swing 调用方。 */
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Deployment page must run on the EDT");
    }
}
