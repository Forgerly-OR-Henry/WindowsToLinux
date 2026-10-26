package gold.debug.windowstolinux.mod.deployment.ui.standard;

import java.awt.BorderLayout;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.*;

import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.server.model.ServerInfo;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.ui.page.ModPage;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretSelection;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.DeploymentStandardPageService;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs;
import gold.debug.windowstolinux.mod.deployment.ui.preference.DeploymentPreferenceSelection;
import gold.debug.windowstolinux.mod.deployment.ui.runtime.DeploymentReviewInputs;
import gold.debug.windowstolinux.mod.deployment.ui.runtime.DeploymentRuntimeDraftForm;
import gold.debug.windowstolinux.mod.deployment.ui.style.DeploymentPageStyle;
import gold.debug.windowstolinux.mod.deployment.ui.style.DeploymentDialogs;
import gold.debug.windowstolinux.mod.deployment.ui.target.DeploymentTargetControls;
import gold.debug.windowstolinux.mod.deployment.ui.flow.DeploymentFlowDraft;
import gold.debug.windowstolinux.mod.deployment.ui.flow.DeploymentFlowDraft.SourceMode;

/** Owner-bound static deployment page; task callbacks remain with their original task after a confirmed view closes. / 绑定所属者的静态部署页面；确认后视图关闭，任务回调仍留在原任务。 */
public final class DeploymentStandardPage implements ModPage {
    /** Holds actual source and non-secret runtime input surfaces until this page closes. / 持有真实来源及非秘密运行输入表面至本页关闭。 */
    private static final class SourceForm {
        /** Captured user name. / 抓取的用户名称。 */
        private final JTextField name;

        /** Explicit original source route. / 明确原来源路径。 */
        private final JComboBox<SourceMode> mode;

        /** Localized name label. / 本地化名称标签。 */
        private final JLabel label = new JLabel();

        /** Actual Git fields, created after locale setup. / 语言设置后创建的实际 Git 字段。 */
        private DeploymentStandardGitSourceForm git;

        /** Actual metadata and complete project edit controls. / 实际元数据及完整项目编辑控件。 */
        private DeploymentStandardSourceControls sources;

        /** Actual editable runtime draft. / 实际可编辑运行草稿。 */
        private DeploymentRuntimeDraftForm runtime;

        /** Creates only inert controls. / 仅创建无副作用控件。 */
        private SourceForm(DeploymentFlowDraft shared) {
            name = shared == null ? new JTextField(24) : shared.name;
            mode = shared == null ? new JComboBox<>(SourceMode.values()) : shared.sourceMode;
        }
    }

    /** Original source controls sharing one page lifetime. / 共用一次页面寿命的原来源控件。 */
    private final SourceForm draft;

    private final DeploymentFlowDraft shared;

    private JComponent projectSurface, advancedSurface;

    /** Explicit target metadata and fixed destinations. / 明确目标元数据及固定目的页。 */
    private DeploymentTargetControls targetControls;

    /** Related source-mode controls sharing one view lifetime. / 共用视图寿命的相关来源模式控件。 */
    private static final class ModeControls {
        /** Owned advanced window retaining original fields. / 保留原字段的所属高级窗口。 */
        private DeploymentPageStyle.Advanced advanced;

        /** Creates controls for this original page instance. / 为当前原页面实例创建控件。 */
        private ModeControls() {
        }

        /** Explicit bounded-assistance opt-in. / 明确有界辅助选择。 */
        private final JCheckBox assisted = new JCheckBox();

        /** Confirmed preference editing and original write observation. / 已确认偏好编辑及原写入观察。 */
        private DeploymentPreferenceSelection preferences;

        /** Localized source-mode label. / 本地化来源模式标签。 */
        private final JLabel label = new JLabel();
    }

    /** Mod-owned task and read-only query service. / Mod 所属任务及只读查询服务。 */
    private final DeploymentStandardPageService service;

    /** View-local background actions, never the publication worker. / 仅视图本地后台操作，非发布工作线程。 */
    private final ExecutorService actions = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "deployment-page-view");
        thread.setDaemon(true);
        return thread;
    });

    /** Invalidates stale chooser and review callbacks. / 撤销迟到的选材与审阅回调。 */
    private final AtomicLong generation = new AtomicLong();

    /** Root owned component. / 所属根组件。 */
    private final JPanel root = new JPanel(new BorderLayout(12, 12));

    /** Actual platform server choices. / 实际平台服务器选择。 */
    private final JComboBox<ServerInfo> servers;

    /** Source-mode label and explicit assistance choice. / 来源模式标签与明确辅助选择。 */
    private final ModeControls modeControls = new ModeControls();

    /** Observes the original accepted task without changing its consent or lifecycle. / 观察原已接纳任务，不改变其同意或寿命。 */
    private gold.debug.windowstolinux.mod.deployment.ui.feedback.DeploymentTaskFeedback feedback;

    /** Pre-publication secret choices and one-shot creation controls. / 发布前秘密选项及一次性创建控件。 */
    private DeploymentStandardSecretControls secretControls;

    /** Current and previously retained task observations. / 当前及之前保留的任务观察。 */
    private final JTextArea status = new JTextArea(2, 40);

    /** Start action. / 启动操作。 */
    private final JButton start = new JButton();

    /** Current server label and source label. / 当前服务器与来源标签。 */
    private final JLabel serverLabel = new JLabel();

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

    /** Original activation's host review capability, enabled only when available. / 仅可用时启用的原启用宿主核验能力。 */
    private gold.debug.windowstolinux.appapi.ui.intervention.UiInterventionService interventions;

    /** Exact original UUID currently represented by this page. / 此页面当前呈现的精确原 UUID。 */
    private java.util.UUID focusedTask;

    /** Original technical details kept separate from the concise progress display. / 与简明进度展示分开保留的原技术详情。 */
    private String retainedTaskDetails = "";

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

    /** Original task that owns the retained failure, excluding unrelated focused tasks. / 保留失败所属的原任务，用于排除无关聚焦任务。 */
    private volatile java.util.UUID recentProblemTask;

    /** Returns the source defaults captured by this page service. / 返回当前页面服务捕获的来源默认值。
     * @return source limits / 来源限额 */
    gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.Source sourceLimits() {
        return service.configuration().source();
    }

    /** Returns initial values before the user reviews a build. / 返回用户审阅构建前的初始值。
     * @return configured non-root defaults / 已配置非 root 默认值 */
    gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentBuildLimits buildDefaults() {
        return service.configuration().build().limits();
    }

    /** Creates one fresh page instance for a lazy registration. / 为惰性登记创建新的页面实例。
     * @param service Mod-owned page task service / Mod 所属页面任务服务 */
    public DeploymentStandardPage(DeploymentStandardPageService service) {
        this(service, null);
    }

    /** Uses the unified page's actual common controls instead of a second draft. / 使用统一页面的真实公共控件，不建立第二份草稿。 */
    public DeploymentStandardPage(DeploymentStandardPageService service, DeploymentFlowDraft shared) {
        this.service = java.util.Objects.requireNonNull(service);
        this.shared = shared;
        draft = new SourceForm(shared);
        servers = shared == null ? new JComboBox<>() : shared.servers;
        modeControls.preferences = shared != null ? shared.preferences : new DeploymentPreferenceSelection(actions, service::savePreferences,
            () -> modeControls.assisted.isSelected() ? "ASSISTED" : "STATIC",
            selection -> modeControls.assisted.setSelected(selection.mode().equals("ASSISTED")),
            detail -> {
                retainedTaskDetails = detail;
                status.setText(text("preferenceError"));
            }, this::policyAvailability);
        modeControls.assisted.setName("deployment.preference.assisted");
        modeControls.assisted.addActionListener(ignored -> modeControls.preferences.changed());
        servers.setRenderer((list, value, index, selected, focus) -> {
            var label = new JLabel(value == null ? text("target.select") : value.name() + " (" + value.host() + ")");
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
        try {
            interventions = service.interventions();
        } catch (ApiException unavailable) {
            interventions = null;
        }
        root.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0) publishVisibility();
        });
        root.setBorder(shared == null ? BorderFactory.createEmptyBorder(18, 20, 18, 20) : BorderFactory.createEmptyBorder());
        servers.setName("deployment.server");
        draft.name.setName("deployment.sourceName");
        draft.mode.setName("deployment.sourceMode");
        status.setName("deployment.status");
        var controls = DeploymentPageStyle.form();
        serverLabel.setText(text("server"));

        draft.label.setText(text("sourceName"));
        DeploymentPageStyle.field(controls, draft.label, draft.name);
        modeControls.label.setText(text("sourceMode"));
        DeploymentPageStyle.field(controls, modeControls.label, draft.mode);
        draft.git = new DeploymentStandardGitSourceForm(this, shared);
        draft.sources = new DeploymentStandardSourceControls(this, service.sources(), draft.git,
            () -> draft.mode.getSelectedItem() == SourceMode.GIT, draft.name,
            selected -> draft.mode.setSelectedItem(selected ? SourceMode.GIT : SourceMode.LOCAL),
            () -> {
                revoke();
                policyAvailability();
            }, status::setText, this::policyAvailability,
            handle -> SwingUtilities.invokeLater(() -> feedback.watch(handle)), shared);
        controls.add(draft.sources.panel());
        draft.mode.setRenderer((list, value, index, selected, focus) -> {
            var label = new JLabel(value == null ? "" : text("sourceMode." + value.name()));
            label.setOpaque(true);
            label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            return label;
        });
        draft.mode.addActionListener(ignored -> updateSourceMode());
        updateSourceMode();
        composePage(controls);
        if (shared == null) DeploymentPageStyle.scrollPage(root);
        modeControls.advanced.environment(messages, locked);
        feedback.configure(environment);
        DeploymentPageStyle.refresh(root, messages);
        start.setEnabled(false);
        if (shared == null) {
            loadPreferences();
            loadServers();
        }
        secretControls.refresh(generation.get());
        draft.sources.refresh();
        refresh();
        return root;
    }

    /** Composes primary cards, policy and advanced controls. / 组合主要卡片、策略及高级控件。
     * @param controls original source fields / 原来源字段 */
    private void composePage(JPanel controls) {
        var buttons = new JPanel();
        start.setText(text("start"));
        start.addActionListener(ignored -> submit());
        refreshButton.setText(text("refresh"));
        refreshButton.addActionListener(ignored -> refresh());
        buttons.add(start);
        buttons.add(refreshButton);
        var targetControls = DeploymentPageStyle.form();
        DeploymentPageStyle.field(targetControls, serverLabel, servers);
        this.targetControls = new DeploymentTargetControls(this::text, service::openPlatform, servers,
            () -> {
                revoke();
                policyAvailability();
            }, this::loadServers, status::setText);
        targetControls.add(this.targetControls.panel());
        var policyControls = new JPanel(new BorderLayout(0, 6));
        modeControls.assisted.setText(text("assisted"));
        policyControls.add(modeControls.assisted, BorderLayout.NORTH);
        policyControls.add(modeControls.preferences.panel(this::text), BorderLayout.CENTER);
        var advancedContent = DeploymentPageStyle.form();
        if (shared != null) {
            DeploymentPageStyle.field(advancedContent, draft.label, draft.name);
            advancedContent.add(draft.sources.panel());
            projectSurface = draft.sources.projectSelection();
        }
        advancedContent.add(DeploymentPageStyle.card("style.git", draft.git.panel()));
        draft.runtime = new DeploymentRuntimeDraftForm(this::text);
        advancedContent.add(DeploymentPageStyle.card("runtimeDraft.title", draft.runtime.panel()));
        secretControls = new DeploymentStandardSecretControls(this, service);
        advancedContent.add(DeploymentPageStyle.card(null, secretControls.panel()));
        modeControls.advanced = new DeploymentPageStyle.Advanced(root, advancedContent);
        advancedSurface = advancedContent;
        buttons.add(modeControls.advanced.button());
        start.putClientProperty("deployment.style.primary", true);
        var top = new JPanel(new BorderLayout(0, 12));
        top.add(DeploymentPageStyle.selections(DeploymentPageStyle.card("style.source", controls),
            DeploymentPageStyle.card("style.server", targetControls)), BorderLayout.CENTER);
        top.add(policyControls, BorderLayout.SOUTH);
        if (shared == null) root.add(top, BorderLayout.NORTH);
        status.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        feedback = new gold.debug.windowstolinux.mod.deployment.ui.feedback.DeploymentTaskFeedback(service.feedback(), actions, () -> {
            try {
                service.openPlatform(gold.debug.windowstolinux.appapi.ui.page.PlatformPageKind.TASKS);
            } catch (ApiException unavailable) {
                status.setText(text("target.unavailable"));
            }
        }, service::delivery, service.configuration().view());
        feedback.originalTaskNavigation(id -> {
            try {
                service.openTasks(java.util.Optional.of(id));
            } catch (ApiException unavailable) {
                status.setText(text("target.unavailable"));
            }
        });
        feedback.presentationLookup(id -> service.presentation(id).map(value ->
            new gold.debug.windowstolinux.mod.deployment.ui.feedback.DeploymentTaskFeedback.Presentation(
                value.fraction(), false, value.pendingCount(), service.paused(id), false)));
        feedback.interventionNavigation(id -> {
            try {
                service.intervene(id);
            } catch (ApiException unavailable) {
                status.setText(text("intervention.invalid"));
            }
        });
        feedback.additionalDetails(() -> retainedTaskDetails);
        if (shared != null) feedback.compactIdle(true);
        var observations = new JPanel(new BorderLayout());
        observations.add(DeploymentPageStyle.notice(status), BorderLayout.NORTH);
        observations.add(feedback.component(), BorderLayout.CENTER);
        root.add(shared == null ? DeploymentPageStyle.card("style.results", observations) : observations, BorderLayout.CENTER);
        if (shared == null) root.add(buttons, BorderLayout.SOUTH);
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
        draft.label.setText(text(draft.mode.getSelectedItem() == SourceMode.GIT ? "sourceNameGit" : "sourceName"));
        modeControls.label.setText(text("sourceMode"));
        modeControls.assisted.setText(text("assisted"));
        modeControls.preferences.localize(this::text);
        draft.git.localize();
        draft.sources.localize();
        targetControls.localize();
        draft.runtime.localize();
        if (dialog != null) {
            DeploymentReviewInputs.refresh(dialog.getRootPane(), this::text);
            DeploymentDialogs.refresh(dialog, messages);
        }
        feedback.configure(environment);
        secretControls.localize();
        secretControls.updateAvailability();
        draft.mode.repaint();
        refreshButton.setText(text("refresh"));
        start.setText(text("start"));
        start.setEnabled(modeControls.preferences.ready() && !closed && !locked && !pendingReview && !secretBusy
            && servers.getSelectedItem() != null);
        policyAvailability();
        modeControls.advanced.environment(messages, locked);
        DeploymentPageStyle.refresh(root, messages);
        if (locked) {
            if (interventions == null) revoke();
            else {
                publishVisibility();
                secretControls.revoke();
            }
        } else {
            if (shared == null && !modeControls.preferences.ready())
                loadPreferences();
            if (shared == null && servers.getItemCount() == 0)
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
        if (modeControls.advanced != null)
            modeControls.advanced.close();
        if (shared == null) modeControls.preferences.close();
        revoke();
        if (secretControls != null) secretControls.close();
        if (feedback != null) feedback.close();
        actions.shutdown();
    }

    /** Restores stored page choices on a worker, leaving invalid values untouched and publication disabled. / 在工作线程恢复存储页面选择，保留非法值并禁用发布。 */
    private void loadPreferences() {
        policyAvailability();
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
                    modeControls.preferences.load(result.value().orElseThrow());
                    policyAvailability();
                }));
            } catch (Exception failure) {
                SwingUtilities.invokeLater(() -> {
                    if (!closed && generation.get() == view)
                        status.setText(text("preferenceError"));
                });
            }
        });
    }

    /** Freezes only mode and policy editing during the original accepted operation. / 在原已接纳操作期间仅冻结模式与策略编辑。 */
    private void policyAvailability() {
        boolean enabled = modeControls.preferences.ready() && !closed && !locked && !pendingReview && !secretBusy
            && (shared == null || !shared.frozen);
        modeControls.assisted.setEnabled(enabled);
        if (shared == null) modeControls.preferences.editable(enabled);
        start.setEnabled(enabled && servers.getSelectedItem() != null && (draft.sources == null || !draft.sources.busy()));
        boolean clearAllowed = !closed && !locked && !secretBusy && (shared == null ? !pendingReview || !confirmed : !shared.frozen);
        if (draft.sources != null) draft.sources.availability(enabled, clearAllowed);
        if (targetControls != null) targetControls.availability(enabled && !draft.sources.busy(), clearAllowed);
        draft.mode.setEnabled(enabled && (draft.sources == null || !draft.sources.busy()));
        draft.name.setEnabled(enabled && (draft.sources == null || !draft.sources.busy()));
        if (draft.git != null) draft.git.enabled(enabled && !draft.sources.busy());
        if (draft.runtime != null) draft.runtime.enabled(enabled);
        if (secretControls != null) secretControls.updateAvailability();
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
                        if (failure != null || result == null || result.outcome() != TaskResult.SUCCEEDED || result.value().isEmpty()) {
                            status.setText(text("serverError"));
                            return;
                        }
                        targetControls.replace(result.value().orElseThrow());
                        policyAvailability();
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
        boolean git = draft.mode.getSelectedItem() == SourceMode.GIT;
        draft.label.setText(text(git ? "sourceNameGit" : "sourceName"));
        if (draft.git != null)
            draft.git.visible(git);
        if (draft.sources != null) {
            draft.sources.revoke();
            policyAvailability();
        }
        root.revalidate();
    }

    /** Reopens the exact task's real feedback while preserving the deployment draft. / 保留部署草稿并重开精确原任务的真实反馈。
     * @param id selected original task UUID / 选定原任务 UUID */
    public void focusOriginalTask(java.util.UUID id) {
        if (closed || locked) throw new IllegalStateException("Original view is unavailable");
        setFocusedTask(id);
        if (interventions == null) return;
        actions.execute(() -> {
            try {
                var original = service.observeOriginal(id);
                SwingUtilities.invokeLater(() -> {
                    if (!closed && !locked && id.equals(focusedTask)) feedback.watch(original);
                });
            } catch (ApiException unavailable) {
                SwingUtilities.invokeLater(() -> {
                    if (!closed && !locked && id.equals(focusedTask)) status.setText(text("taskError"));
                });
            }
        });
    }

    /** Withdraws the previous original task's visibility before changing the focused UUID. / 改变聚焦 UUID 前撤回先前原任务的可见性。
     * @param id newly focused original task / 新聚焦原任务 */
    private void setFocusedTask(java.util.UUID id) {
        if (interventions != null && focusedTask != null && !focusedTask.equals(id))
            try {
                interventions.visible(focusedTask, false);
            } catch (ApiException unavailable) {
            }
        focusedTask = id;
        publishVisibility();
    }

    /** Reports only the actual visible, unlocked original-task surface to the host queue. / 仅向宿主队列报告实际可见且已解锁的原任务界面。 */
    public void publishVisibility() {
        if (interventions == null || focusedTask == null) return;
        try {
            interventions.visible(focusedTask, !closed && !locked && root.isShowing());
        } catch (ApiException unavailable) {
        }
    }

    /** Uses the current application name for readable review without exposing an internal identifier. / 以当前应用名称提供可读核验文字，避免暴露内部标识。
     * @return application caption with a localized unnamed fallback / 应用文字，未命名时使用本地化缺省文字 */
    String applicationDisplayName() {
        String name = draft.name.getText().strip();
        return name.isBlank() ? text("confirmation.currentApplication") : name;
    }

    /** Resolves the selected original server name and disambiguates duplicate names with its host. / 解析所选原服务器名称，并用主机区分重名。
     * @param id original selected server identity / 原所选服务器身份
     * @return readable name with a safe localized fallback / 可读名称及安全本地化缺省文字 */
    String serverDisplayName(ServerId id) {
        for (int index = 0; index < servers.getItemCount(); index++) {
            var item = servers.getItemAt(index);
            if (!item.ref().equals(id)) continue;
            int sameName = 0;
            for (int other = 0; other < servers.getItemCount(); other++)
                if (servers.getItemAt(other).name().equals(item.name())) sameName++;
            return item.name() + (sameName > 1 ? " (" + item.host() + ")" : "");
        }
        return text("confirmation.selectedServer");
    }

    public JComponent flowResults() {
        return root;
    }

    public JComponent flowAdvanced() {
        return advancedSurface;
    }

    public JComponent flowProjects() {
        return projectSurface;
    }

    public void flowSubmit() {
        submit();
    }

    public boolean flowBusy() {
        return pendingReview || secretBusy;
    }

    public java.util.UUID flowTask() {
        return currentTask == null ? null : currentTask.id();
    }

    public void flowRefresh() {
        refresh();
    }

    public void flowRefreshServers() {
        loadServers();
    }

    public void flowAvailability() {
        policyAvailability();
    }

    public void flowMode(String mode) {
        boolean standard = !mode.equals("AGENT");
        draft.runtime.panel().setVisible(standard);
        secretControls.panel().setVisible(standard);
    }

    public boolean flowSourceBusy() {
        return draft.sources.busy();
    }

    /** Submits the selected server and source name through one original publication task. / 将选定服务器及来源名提交为一项原发布任务。 */
    private void submit() {
        requireEdt();
        if (closed || locked || pendingReview || secretBusy || draft.sources.busy() || !modeControls.preferences.ready()
            || servers.getSelectedItem() == null)
            return;
        var server = ((ServerInfo) servers.getSelectedItem()).ref();
        var name = draft.name.getText().trim();
        var mode = (SourceMode) draft.mode.getSelectedItem();
        Optional<DeploymentStandardInputs.GitSource> git;
        DeploymentStandardInputs.SourceOptions sourceOptions;
        try {
            if (mode == SourceMode.GIT && name.isBlank())
                throw new IllegalArgumentException("Git project name is required");
            git = mode == SourceMode.GIT ? Optional.of(draft.git.source()) : Optional.empty();
            sourceOptions = draft.sources.capture(draft.runtime.values());
        } catch (IllegalArgumentException invalid) {
            status.setText(text(mode == SourceMode.GIT ? "invalidGitInput" : "source.invalid"));
            return;
        }
        boolean assisted = shared == null ? modeControls.assisted.isSelected() : shared.mode.equals("ASSISTED");
        String approvalMode = modeControls.preferences.approval();
        boolean rememberAuthorization = modeControls.preferences.rememberAuthorization();
        long view = generation.get();
        pendingReview = true;
        policyAvailability();
        secretControls.updateAvailability();
        confirmed = false;
        recentProblem = null;
        recentProblemTask = null;
        start.setEnabled(false);
        try {
            actions.execute(() -> {
                if (closed || locked || generation.get() != view) {
                    pendingReview = false;
                    return;
                }
                boolean admitted = false;
                try {
                    var handle = service.submit(server,
                        new DeploymentStandardInputs.Draft(name, sourceLimits().bytes(), sourceLimits().entries(),
                            sourceLimits().archiveBytes(), sourceOptions),
                        git, new DeploymentStandardPageReviewer(this, view), assisted, approvalMode, rememberAuthorization);
                    admitted = true;
                    currentTask = handle;
                    recentProblemTask = handle.id();
                    SwingUtilities.invokeLater(() -> {
                        feedback.watch(handle);
                        setFocusedTask(handle.id());
                    });
                    if (closed || locked || generation.get() != view)
                        cancelUnconfirmed(handle);
                    handle.completion().whenComplete((result, failure) -> {
                        pendingReview = false;
                        if (currentTask == handle)
                            currentTask = null;
                        SwingUtilities.invokeLater(() -> {
                            secretControls.updateAvailability();
                            policyAvailability();
                        });
                        if (active(view) && failure == null && result != null)
                            recentProblem = result.problem().orElse(null);
                        refresh();
                        SwingUtilities.invokeLater(this::policyAvailability);
                    });
                    refresh();
                } catch (Exception failure) {
                    SwingUtilities.invokeLater(() -> {
                        if (!closed && !locked && generation.get() == view)
                            status.setText(text(
                                failure instanceof IllegalArgumentException ? "invalidGitInput" : "submitError"));
                        policyAvailability();
                    });
                } finally {
                    if (!admitted) {
                        pendingReview = false;
                        SwingUtilities.invokeLater(() -> {
                            secretControls.updateAvailability();
                            policyAvailability();
                        });
                        SwingUtilities.invokeLater(this::policyAvailability);
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
                            var problem = focusedTask == null || focusedTask.equals(recentProblemTask) ? recentProblem : null;
                            String diagnostic = problem == null
                                ? ""
                                : problem.eventCode().equals("DEPLOYMENT_STATIC_VIEW_REVOKED")
                                ? "\n" + text("reviewCancelled")
                                : "\n" + problem.eventCode() + ": "
                                + (problem.eventCode().equals("DEPLOYMENT_SCHEMA_REVIEW_REQUIRED")
                                ? text("schemaReviewRequired")
                                : problem.message());
                            retainedTaskDetails = diagnostic + "\n" + modeControls.preferences.diagnostic();
                            status.setText(problem == null ? (shared == null && shown.isEmpty() ? text("noTasks") : "")
                                : problem.eventCode().equals("DEPLOYMENT_SCHEMA_REVIEW_REQUIRED")
                                ? text("schemaReviewRequired") : text("intervention.invalid"));
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
        if (draft.sources != null) draft.sources.revoke();
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
        return approveValidated(view, panel, title, task, finalReview, () -> {
        }, onAccepted);
    }

    /** Requires current validated form input before recording original-task consent. / 记录原任务同意前要求当前有效表单输入。
     * @param view original view generation / 原视图代次
     * @param panel actual review controls / 实际核验控件
     * @param title localized review title / 本地化核验标题
     * @param task authentic original callback / 真实原回调
     * @param finalReview whether this grants final publication consent / 是否为最终发布同意
     * @param validator current same-EDT input validation / 当前同 EDT 输入校验
     * @return explicit acceptance after original boundary checks / 原边界检查后的明确接受 */
    boolean approveValidated(long view, JPanel panel, String title, TaskContext task, boolean finalReview, Runnable validator) {
        return approveValidated(view, panel, title, task, finalReview, validator, () -> {
        });
    }

    /** Uses hosted review for original publication tasks and preserves the existing short-task dialog path. / 对原发布任务使用宿主核验，并保留既有短任务对话框路径。
     * @param view original view generation / 原视图代次
     * @param panel actual review controls / 实际核验控件
     * @param title localized review title / 本地化核验标题
     * @param task authentic original task / 真实原任务
     * @param finalReview whether this is final publication consent / 是否为最终发布同意
     * @param validator same-EDT validation before acceptance / 接受前的同 EDT 校验
     * @param onAccepted original owner transition after validation / 校验后的原所有者状态转换
     * @return explicit acceptance, or false after withdrawal or unavailability / 明确接受，撤回或不可用后为假 */
    private boolean approveValidated(long view, JPanel panel, String title, TaskContext task, boolean finalReview,
                                     Runnable validator, Runnable onAccepted) {
        if (hostedInterventions(task)) {
            try {
                var request = interventions.request(task,
                    new gold.debug.windowstolinux.appapi.ui.intervention.UiInterventionSpec(
                        finalReview ? text("finalConfirm") : text("intervention.title"), "",
                        text(finalReview ? "intervention.finalAccept" : "intervention.continue"), true),
                    () -> new gold.debug.windowstolinux.appapi.ui.intervention.UiInterventionForm<Boolean>() {
                        /** Refreshes this opening's localized form before host mounting. / 宿主挂载前刷新本次打开表单的本地化文字。
                         * @return original review controls / 原核验控件 */
                        @Override
                        public JComponent component() {
                            DeploymentPageStyle.refresh(panel, messages);
                            return panel;
                        }

                        /** Rechecks view, task and inputs before recording the original acceptance transition. / 记录原接受转换前重查视图、任务及输入。
                         * @return affirmative validated decision / 已校验的肯定决定 */
                        @Override
                        public Boolean submit() {
                            if (closed || locked || generation.get() != view || task.cancelToken().requested())
                                throw new IllegalArgumentException("Original review is unavailable");
                            validator.run();
                            if (finalReview) confirmed = true;
                            onAccepted.run();
                            return Boolean.TRUE;
                        }

                        /** Clears every password document when this review opening ends. / 本次核验打开结束时清除全部密码文档。 */
                        @Override
                        public void close() {
                            clearPasswords(panel);
                        }
                    });
                return Boolean.TRUE.equals(request.completion().toCompletableFuture().get());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            } catch (ApiException | java.util.concurrent.ExecutionException |
                     java.util.concurrent.CancellationException unavailable) {
                return false;
            }
        }
        if (closed || locked || generation.get() != view || task.cancelToken().requested())
            return false;
        var answer = new java.util.concurrent.CompletableFuture<Boolean>();
        SwingUtilities.invokeLater(() -> {
            if (closed || locked || generation.get() != view) {
                answer.complete(false);
                return;
            }
            JDialog owned = null;
            try {
                DeploymentPageStyle.refresh(panel, messages);
                var pane = new JOptionPane(null, JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
                int height = panel.getClientProperty("deployment.dialog.preferredHeight") instanceof Number preferred
                    ? preferred.intValue() : 480;
                owned = DeploymentDialogs.option(root, title, panel, pane, 760, height);
                DeploymentDialogs.refresh(owned, messages);
                dialog = owned;
                if (panel.getClientProperty("deployment.review.focus") instanceof JComponent focus)
                    SwingUtilities.invokeLater(() -> {
                        DeploymentDialogs.reveal(focus);
                        focus.requestFocusInWindow();
                    });
                service.userWaiting(task.id(), true);
                boolean accepted;
                for (; ; ) {
                    owned.setVisible(true);
                    accepted = !closed && !locked && generation.get() == view
                        && Integer.valueOf(JOptionPane.OK_OPTION).equals(pane.getValue());
                    if (!accepted) break;
                    try {
                        validator.run();
                        break;
                    } catch (IllegalArgumentException invalid) {
                        pane.setValue(JOptionPane.UNINITIALIZED_VALUE);
                    }
                }
                if (accepted && finalReview)
                    confirmed = true;
                if (accepted)
                    onAccepted.run();
                answer.complete(accepted);
            } catch (RuntimeException failed) {
                answer.complete(false);
            } finally {
                service.userWaiting(task.id(), false);
                if (owned != null)
                    owned.dispose();
                if (dialog == owned)
                    dialog = null;
                answer.complete(false);
            }
        });
        try {
            while (!answer.isDone()) {
                if (closed || locked || generation.get() != view || task.cancelToken().requested()) {
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

    /** Limits hosted author forms to authentic publication callbacks rather than short editor tasks. / 将宿主作者表单限制为真实发布回调，避免混入短编辑任务。
     * @param task original callback context / 原回调上下文
     * @return whether hosted publication review is available / 宿主发布核验是否可用 */
    boolean hostedInterventions(TaskContext task) {
        try {
            return interventions != null && service.publicationTask(task);
        } catch (ApiException unavailable) {
            return false;
        }
    }

    /** Transfers one freshly validated password buffer back to the original publication worker. / 将一个新校验密码缓冲区交回原发布工作线程。
     * @param view original view generation / 原视图代次
     * @param task authentic original publication task / 真实原发布任务
     * @param title human-readable password role / 供用户阅读的密码用途
     * @param label localized meaning of the requested password / 所需密码含义的本地化文字
     * @param allowEmpty whether the original operation permits an empty password / 原操作是否允许空密码
     * @param onAccepted original owner transition before returning the buffer / 返回缓冲区前的原所有者状态转换
     * @return caller-owned buffer requiring disposal after use / 由调用方持有并须在使用后清除的缓冲区
     * @throws Exception if original review, validation or waiting is unavailable / 原核验、校验或等待不可用时 */
    char[] requestPassword(long view, TaskContext task, String title, String label, boolean allowEmpty, Runnable onAccepted) throws Exception {
        var request = interventions.request(task,
            new gold.debug.windowstolinux.appapi.ui.intervention.UiInterventionSpec(
                text("intervention.title"), "", text("intervention.continue"), true),
            () -> new gold.debug.windowstolinux.appapi.ui.intervention.UiInterventionForm<char[]>() {
                /** Secret input scoped to this one fresh host opening. / 仅属于本次新宿主打开的秘密输入。 */
                private final JPasswordField field = new JPasswordField(32);

                /** Password meaning and masked input mounted by the host. / 宿主挂载的密码含义及遮蔽输入。 */
                private final JPanel panel = passwordPanel();

                /** Builds a fresh masked field under the original question label. / 在原问题标签下创建新遮蔽字段。
                 * @return one disposable password form / 单次可释放密码表单 */
                private JPanel passwordPanel() {
                    var content = DeploymentDialogs.form();
                    content.add(DeploymentDialogs.fact(() -> title));
                    var technical = DeploymentDialogs.fact(() -> label);
                    technical.setVisible(false);
                    var details = new JToggleButton(text("automatic.details"));
                    details.addActionListener(ignored -> {
                        technical.setVisible(details.isSelected());
                        content.revalidate();
                    });
                    content.add(details);
                    content.add(technical);
                    field.setName("deployment.intervention.password");
                    content.add(field);
                    return content;
                }

                /** Returns this opening's masked password form. / 返回本次打开的遮蔽密码表单。
                 * @return current password panel / 当前密码面板 */
                @Override
                public JComponent component() {
                    return panel;
                }

                /** Validates original lifetime and password bounds before transferring disposable characters. / 转交可清除字符前校验原寿命及密码范围。
                 * @return validated caller-owned password buffer / 已校验且由调用方持有的密码缓冲区 */
                @Override
                public char[] submit() {
                    char[] value = field.getPassword();
                    if (closed || locked || generation.get() != view || task.cancelToken().requested()
                        || value.length > 262144 || !allowEmpty && value.length == 0) {
                        java.util.Arrays.fill(value, '\0');
                        throw new IllegalArgumentException("Password input is unavailable or invalid");
                    }
                    try {
                        onAccepted.run();
                        return value;
                    } catch (RuntimeException failure) {
                        java.util.Arrays.fill(value, '\0');
                        throw failure;
                    }
                }

                /** Clears the field document on acceptance, deferral, cancellation and invalidation. / 接受、延期、取消及失效时清除字段文档。 */
                @Override
                public void close() {
                    field.setText("");
                }
            });
        return request.completion().toCompletableFuture().get();
    }

    /** Clears nested password documents before author review controls leave the host window. / 作者核验控件离开宿主窗口前清除嵌套密码文档。
     * @param value original review component or descendant container / 原核验组件或其后代容器 */
    private static void clearPasswords(java.awt.Component value) {
        if (value instanceof JPasswordField password) password.setText("");
        if (value instanceof java.awt.Container parent)
            for (var child : parent.getComponents()) clearPasswords(child);
    }

    /** Creates or reads one Swing form only on the EDT. / 仅在 EDT 创建或读取一份 Swing 表单。
     * @param work form action / 表单操作
     * @param <T> return type / 返回类型
     * @return EDT result / EDT 结果 */
    <T> T onEdt(java.util.concurrent.Callable<T> work) {
        if (SwingUtilities.isEventDispatchThread())
            try {
                return work.call();
            } catch (RuntimeException failure) {
                throw failure;
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
        if (failure.get() instanceof RuntimeException invalid)
            throw invalid;
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
    Optional<ServerId> selectedServer() {
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
        return !closed && !locked && !pendingReview && !secretBusy && !draft.sources.busy()
            && (shared == null || !shared.frozen) && selectedServer().isPresent();
    }

    /** Blocks publication while one pre-publication secret task is in progress. / 发布前秘密任务进行时阻止发布。
     * @param busy whether a task is active / 是否有活动任务 */
    void secretBusy(boolean busy) {
        secretBusy = busy;
        SwingUtilities.invokeLater(this::policyAvailability);
        SwingUtilities.invokeLater(secretControls::updateAvailability);
    }

    /** Validates a form before allowing it to leave the same owner review. / 表单离开同一所属审阅前先校验。
     * @param prompt original questions / 原问题
     * @param answer edited values / 已编辑值 */
    void validateReview(DeploymentStandardInputs.Prompt prompt, DeploymentStandardInputs.Answers answer) {
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
