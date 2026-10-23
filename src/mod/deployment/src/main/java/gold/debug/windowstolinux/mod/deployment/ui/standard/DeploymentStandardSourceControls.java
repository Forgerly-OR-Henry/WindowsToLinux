package gold.debug.windowstolinux.mod.deployment.ui.standard;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.*;

import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.mod.deployment.db.project.DeploymentProjectRecord;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectSource;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.source.DeploymentStandardSourceService;
import gold.debug.windowstolinux.mod.deployment.ui.source.DeploymentLocalSourceControls;
import gold.debug.windowstolinux.mod.deployment.ui.style.DeploymentPageStyle;

/** Owns selected source metadata and complete project drafts, never an active file grant. / 持有所选来源元数据及完整项目草稿，不持有活动文件授权。 */
final class DeploymentStandardSourceControls {
    /** Contains only owned Swing fields, independent of original task state. / 仅含所属 Swing 字段，独立于原任务状态。 */
    private static final class ViewControls {
        /** Original layout and metadata field. / 原布局及元数据字段。 */
        private final JPanel panel = DeploymentPageStyle.form();

        /** Bounded complete project observations. / 有界完整项目观察。 */
        private final JComboBox<DeploymentProjectRecord> projects = new JComboBox<>();

        /** Explicit save-on-publication consent. / 明确发布时保存同意。 */
        private final JCheckBox save = new JCheckBox();

        /** Original project actions. / 原项目操作。 */
        private final JButton reload = new JButton(), newProject = new JButton(), saveProject = new JButton();

        /** Original localized metadata labels. / 原本地化元数据标签。 */
        private final JLabel projectsLabel = new JLabel();

        /** Creates no task or file grant. / 不创建任务或文件授权。 */
        private ViewControls() {
        }
    }

    /** Original owner view and task service. / 原所属视图及任务服务。 */
    private final DeploymentStandardPage page;

    private final gold.debug.windowstolinux.mod.deployment.ui.flow.DeploymentFlowDraft shared;

    /** Original short task service. / 原短任务服务。 */
    private final DeploymentStandardSourceService service;

    /** Captured Git form and selected route. / 已抓取 Git 表单及所选路径。 */
    private final DeploymentStandardGitSourceForm git;

    /** Current route without source access. / 无来源访问的当前路径。 */
    private final BooleanSupplier gitSelected;

    /** User-facing name control. / 面向用户的名称控件。 */
    private final JTextField name;

    /** Applies an explicit saved source kind. / 应用明确已保存来源种类。 */
    private final Consumer<Boolean> selectGit;

    /** Invalidates unanswered original review. / 撤销未答原审阅。 */
    private final Runnable changed;

    /** Safe status feedback. / 安全状态反馈。 */
    private final Consumer<String> feedback;

    /** Recomputes the existing page start gate. / 重算既有页面启动门槛。 */
    private final Runnable availabilityChanged;

    /** Observes only an actual admitted save operation, without granting consent. / 仅观察实际已准入保存操作，不授予同意。 */
    private final Consumer<TaskHandle<?>> watch;

    /** Original controls sharing the source/project view lifetime. / 共用来源及项目视图寿命的原控件。 */
    private final ViewControls controls = new ViewControls();

    /** Common original local picker, independent of standard project actions. / 共用原本地选择器，独立于标准项目操作。 */
    private final DeploymentLocalSourceControls local;

    /** Exact old row captured by an explicit user selection. / 明确用户选择抓取的精确旧行。 */
    private DeploymentProjectRecord expected;

    /** Original view operation retained until actual completion. / 保留至实际完成的原视图操作。 */
    private TaskHandle<?> operation;

    /** Source revision rejecting delayed results. / 拒绝迟到结果的来源修订。 */
    private long revision;

    /** Current original operation or query is active. / 当前原操作或查询活动。 */
    private boolean busy, updating, enabled, clearAllowed;

    /** Original UNKNOWN observer, never a timeout release. / 原 UNKNOWN 观察器，不按超时释放。 */
    private Timer observer;

    /** Builds controls on the existing owner page. / 在既有所属页构建控件。
     * @param page original view / 原视图
     * @param service short original tasks / 短原任务
     * @param git Git form / Git 表单
     * @param gitSelected route capture / 路径抓取
     * @param name actual name control / 实际名称控件
     * @param selectGit route edit / 路径编辑
     * @param changed review invalidation / 审阅撤销
     * @param feedback safe feedback / 安全反馈
     * @param availabilityChanged original page start gate / 原页面启动门槛
     * @param watch observes an actual original save task / 观察实际原保存任务 */
    DeploymentStandardSourceControls(DeploymentStandardPage page, DeploymentStandardSourceService service,
                                     DeploymentStandardGitSourceForm git, BooleanSupplier gitSelected, JTextField name, Consumer<Boolean> selectGit,
                                     Runnable changed, Consumer<String> feedback, Runnable availabilityChanged, Consumer<TaskHandle<?>> watch) {
        this(page, service, git, gitSelected, name, selectGit, changed, feedback, availabilityChanged, watch, null);
    }

    DeploymentStandardSourceControls(DeploymentStandardPage page, DeploymentStandardSourceService service,
                                     DeploymentStandardGitSourceForm git, BooleanSupplier gitSelected, JTextField name, Consumer<Boolean> selectGit,
                                     Runnable changed, Consumer<String> feedback, Runnable availabilityChanged, Consumer<TaskHandle<?>> watch,
                                     gold.debug.windowstolinux.mod.deployment.ui.flow.DeploymentFlowDraft shared) {
        this.page = page;
        this.shared = shared;
        this.service = service;
        this.git = git;
        this.gitSelected = gitSelected;
        this.name = name;
        this.selectGit = selectGit;
        this.changed = changed;
        this.feedback = feedback;
        this.availabilityChanged = availabilityChanged;
        this.watch = watch;
        local = shared != null ? shared.local : new DeploymentLocalSourceControls(service.selection(), page::text, page::execute,
            page::viewGeneration, page::active, selection -> {
            selection.ifPresent(value -> {
                if (name.getText().isBlank()) name.setText(value.displayName());
            });
            changed.run();
        }, feedback, availabilityChanged);
        controls.projects.setName("deployment.projects");
        controls.save.setName("deployment.project.saveOnPublication");
        if (shared == null) controls.panel.add(local.panel());
        if (shared == null) DeploymentPageStyle.field(controls.panel, controls.projectsLabel, controls.projects);
        var projectActions = new JPanel();
        projectActions.add(controls.reload);
        projectActions.add(controls.newProject);
        projectActions.add(controls.saveProject);
        controls.panel.add(projectActions);
        controls.panel.add(controls.save);
        controls.projects.setRenderer((list, value, index, selected, focus) -> new JLabel(value == null
            ? page.text("project.none") : value.displayName() + " / " + value.source().kind() + " / " + value.id()));
        controls.reload.addActionListener(ignored -> refresh());
        controls.newProject.addActionListener(ignored -> {
            expected = null;
            controls.projects.setSelectedItem(null);
            controls.save.setSelected(false);
            changed.run();
        });
        controls.saveProject.addActionListener(ignored -> save());
        controls.projects.addActionListener(ignored -> {
            if (!updating) loadSelected();
        });
        controls.save.addActionListener(ignored -> changed.run());
        localize();
    }

    /** Returns the real card rows. / 返回真实卡片行。
     * @return original panel / 原面板 */
    JPanel panel() {
        return controls.panel;
    }

    JComponent projectSelection() {
        return controls.projects;
    }

    /** Reports whether an original source task still occupies this draft. / 报告原来源任务是否仍占用本草稿。
     * @return original activity / 原活动性 */
    boolean busy() {
        return busy || local.busy();
    }

    /** Captures metadata only for later original-task reauthorization. / 仅抓取元数据，供之后原任务重新授权。
     * @param runtime explicit non-secret draft / 明确非秘密草稿
     * @return immutable source options / 不可变来源选项 */
    DeploymentStandardInputs.SourceOptions capture(Map<String, String> runtime) {
        if (!gitSelected.getAsBoolean() && local.selection().isEmpty())
            throw new IllegalArgumentException("Local source is not selected");
        return new DeploymentStandardInputs.SourceOptions(gitSelected.getAsBoolean() ? Optional.empty()
            : Optional.of(local.selection().orElseThrow().location()), controls.save.isSelected() || gitSelected.getAsBoolean() && expected != null
            ? Optional.of(edit()) : Optional.empty(), runtime);
    }

    /** Captures a complete old-row CAS with an actual observation. / 以实际观测抓取完整旧行 CAS。
     * @return exact edit / 精确编辑 */
    private DeploymentStandardInputs.ProjectEdit edit() {
        return new DeploymentStandardInputs.ProjectEdit(expected == null ? UUID.randomUUID() : expected.id(),
            name.getText().trim(), Optional.ofNullable(expected), Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    /** Reads a bounded prefix without silently selecting the first project. / 读取有界前缀，不静默选择首个项目。 */
    void refresh() {
        if (busy()) return;
        long view = page.viewGeneration(), sourceRevision = revision;
        busy(true);
        availability(enabled);
        page.execute(() -> {
            try {
                var handle = service.projects();
                observe(handle, view, sourceRevision, result -> {
                    updating = true;
                    try {
                        controls.projects.removeAllItems();
                        result.value().orElseThrow().forEach(controls.projects::addItem);
                        controls.projects.setSelectedItem(expected);
                        controls.projects.setToolTipText(page.text("project.listLimit"));
                    } finally {
                        updating = false;
                    }
                });
            } catch (Exception failure) {
                failed(view, sourceRevision);
            }
        });
    }

    /** Loads only explicit source metadata from the selected complete row. / 仅从所选完整行加载明确来源元数据。 */
    private void loadSelected() {
        if (!(controls.projects.getSelectedItem() instanceof DeploymentProjectRecord row)) return;
        if (shared != null && occupied()) {
            updating = true;
            try {
                controls.projects.setSelectedItem(expected);
            } finally {
                updating = false;
            }
            feedback.accept(page.text("source.alreadySelected"));
            return;
        }
        revoke();
        expected = row;
        name.setText(row.displayName());
        controls.save.setSelected(true);
        boolean remote = row.source().kind() == DeploymentProjectSource.KindType.GIT;
        selectGit.accept(remote);
        if (remote) git.load(row.source());
        else
            local.load(new gold.debug.windowstolinux.mod.deployment.service.core.source.DeploymentSourceSelectionService.LocalLocation(row.source().location(), row.displayName()));
        changed.run();
    }

    /** Saves one new or revised source independently of publication. / 独立于发布地保存一次新来源或修订。 */
    private void save() {
        if (!enabled || busy()) return;
        final DeploymentStandardInputs.ProjectEdit edit;
        final Optional<DeploymentStandardInputs.GitSource> source;
        try {
            edit = edit();
            source = gitSelected.getAsBoolean() ? Optional.of(git.source()) : Optional.empty();
            if (source.isEmpty() && local.selection().isEmpty())
                throw new IllegalArgumentException("Missing local selection");
        } catch (IllegalArgumentException invalid) {
            feedback.accept(page.text("source.invalid"));
            return;
        }
        long view = page.viewGeneration(), sourceRevision = revision;
        var location = local.selection();
        busy(true);
        availability(enabled);
        page.execute(() -> {
            try {
                var handle = source.isPresent() ? service.saveGit(source.orElseThrow(), edit, page.sourceLimits().bytes(), page.sourceLimits().entries())
                    : service.saveLocal(location.orElseThrow(), edit, page.sourceLimits().bytes(), page.sourceLimits().entries(), (row, task) -> {
                    var form = page.onEdt(() -> {
                        var lines = new JPanel(new java.awt.GridLayout(0, 1));
                        lines.add(new JLabel(row.displayName()));
                        lines.add(new JLabel(row.source().location().toString()));
                        lines.add(new JLabel(page.text("sourceDigest") + ": " + row.source().sha256()));
                        return lines;
                    });
                    return page.approve(view, form, page.text("project.save"), task);
                });
                watch.accept(handle);
                observe(handle, view, sourceRevision, result -> {
                    expected = result.value().orElseThrow();
                    controls.save.setSelected(true);
                    feedback.accept(page.text("project.saved") + " " + expected.id() + " / " + expected.source().sha256());
                    refresh();
                });
            } catch (Exception failure) {
                failed(view, sourceRevision);
            }
        });
    }

    /** Observes exact task completion and UNKNOWN without releasing it by time. / 观察精确任务完成及 UNKNOWN，不按时间释放。
     * @param <T> small result type / 小结果类型
     * @param handle original handle / 原句柄
     * @param view original view generation / 原视图代次
     * @param sourceRevision original source revision / 原来源修订
     * @param apply confirmed metadata update / 已确认元数据更新 */
    private <T> void observe(TaskHandle<T> handle, long view, long sourceRevision, Consumer<OperationResult<T>> apply) {
        SwingUtilities.invokeLater(() -> {
            operation = handle;
            if (!page.active(view) || revision != sourceRevision) cancel(handle);
            observer = new Timer(100, ignored -> {
                if (handle.snapshot().state() == TaskState.UNKNOWN) {
                    observer.stop();
                    feedback.accept(page.text("source.unknown") + " " + handle.id());
                }
            });
            observer.start();
        });
        handle.completion().whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
            if (operation == handle) {
                operation = null;
                busy(false);
                if (observer != null) observer.stop();
            }
            if (page.active(view) && revision == sourceRevision) {
                if (failure == null && result != null && result.outcome() == TaskResult.SUCCEEDED) {
                    if (result.value().isPresent()) apply.accept(result);
                    else {
                        feedback.accept(page.text("source.recovered") + " " + handle.id());
                        refresh();
                    }
                } else feedback.accept(page.text("source.failed") + " " + handle.id());
            }
            availability(enabled);
        }));
    }

    /** Preserves the failed original identity without accepting stale metadata. / 保留失败原身份，不接受迟到元数据。
     * @param view original view / 原视图
     * @param sourceRevision original revision / 原修订 */
    private void failed(long view, long sourceRevision) {
        SwingUtilities.invokeLater(() -> {
            busy(false);
            if (page.active(view) && revision == sourceRevision) feedback.accept(page.text("source.failed"));
            availability(enabled);
        });
    }

    /** Updates only activity, without revoking a source or accepting late metadata. / 仅更新活动性，不撤销来源或接受迟到元数据。
     * @param value actual activity / 实际活动性 */
    private void busy(boolean value) {
        busy = value;
        availabilityChanged.run();
    }

    /** Revokes delayed results and cooperatively cancels only the original short operation. / 撤销迟到结果且仅协作取消原短操作。 */
    void revoke() {
        revision++;
        local.revoke();
        if (operation != null) cancel(operation);
        if (observer != null) observer.stop();
    }

    /** Cooperatively cancels an exact original handle, leaving its recovery to the host. / 协作取消精确原句柄，将恢复留给宿主。
     * @param handle original handle / 原句柄 */
    private void cancel(TaskHandle<?> handle) {
        try {
            handle.requestCancel();
        } catch (gold.debug.windowstolinux.appapi.task.result.ApiException retained) {
            feedback.accept(page.text("source.unknown") + " " + handle.id() + " / " + retained.problem().eventCode());
        }
    }

    /** Recomputes editing while preserving original UNKNOWN occupancy. / 重算编辑能力，保留原 UNKNOWN 占用。
     * @param enabled unlocked idle owner controls / 未锁定空闲所属控件 */
    void availability(boolean enabled) {
        this.enabled = enabled;
        boolean edit = enabled && !busy();
        local.availability(enabled && !busy, clearAllowed && !busy, !gitSelected.getAsBoolean());
        controls.projects.setEnabled(edit && (shared == null || !occupied()));
        controls.reload.setEnabled(edit);
        controls.newProject.setEnabled(edit);
        controls.saveProject.setEnabled(edit);
        controls.save.setEnabled(edit && !gitSelected.getAsBoolean());
        if (gitSelected.getAsBoolean()) controls.save.setSelected(true);
    }

    /** Separates a new source task from clearing an unanswered original deployment. / 区分新来源任务与清除未答原部署。
     * @param enabled whether new edits and tasks are admitted / 是否接纳新编辑及任务
     * @param clearAllowed whether the original unanswered choice can be revoked / 是否可撤销原未答选择 */
    void availability(boolean enabled, boolean clearAllowed) {
        this.clearAllowed = clearAllowed;
        availability(enabled);
    }

    private boolean occupied() {
        return local.selection().isPresent() || shared != null && shared.gitSelected() && !shared.remote.getText().isBlank();
    }

    /** Updates labels without discarding metadata or original rows. / 更新标签，不丢弃元数据或原行。 */
    void localize() {
        local.localize();
        controls.projectsLabel.setText(page.text("project.selection"));
        controls.reload.setText(page.text("project.refresh"));
        controls.newProject.setText(page.text("project.new"));
        controls.saveProject.setText(page.text("project.save"));
        controls.save.setText(page.text("project.saveOnPublication"));
        controls.projects.repaint();
    }

}
