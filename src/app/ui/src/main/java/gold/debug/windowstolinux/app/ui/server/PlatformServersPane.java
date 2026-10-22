package gold.debug.windowstolinux.app.ui.server;

import java.awt.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;

import gold.debug.windowstolinux.app.service.server.PlatformServerService;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerSummary;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;

/** Permanent configuration and authentication-only SSH page with explicit task recovery. / 带显式任务恢复的常驻配置及仅认证 SSH 页面。 */
public final class PlatformServersPane extends JPanel implements AutoCloseable {
    /** Current view lifetime and exact request ownership. / 当前视图寿命及精确请求归属。 */
    private final ViewState view = new ViewState();

    /** Mutable state confined to the EDT. / 限于 EDT 的可变状态。 */
    private static final class ViewState {
        /** Rejects earlier view intents and busy owners. / 拒绝早期视图意图及忙碌状态所有者。 */
        private long epoch, request;

        /** Current privacy, lifetime and active request. / 当前隐私、寿命及活动请求。 */
        private boolean locked = true, closed, busy;

        /** Actual check records belonging to the current view lifetime. / 属于当前视图生命周期的实际检查记录。 */
        private final Map<String, CheckRecord> records = new java.util.HashMap<>();

        /** Original reconciliation output and caption, independent of server selection. / 独立于服务器选择的原核对输出与标题。 */
        private String taskOutput, taskOutputTarget;
    }

    /** Immutable original target and completed-failure eligibility. / 不可变原目标及已完成失败准入。
     * @param id original check / 原检查
     * @param profile original endpoint / 原端点
     * @param handle original resource owner / 原资源所有者
     * @param failed definite completed failure / 明确完成失败 */
    private record CheckBinding(UUID id, ServerProfile profile,
                                gold.debug.windowstolinux.appapi.task.run.TaskHandle<?> handle, boolean failed) {
    }

    /** Parent for configuration and confirmation dialogs. / 配置及确认窗口的父级。 */
    private final Window owner;

    /** Actual platform operations, never a deployment facade. / 实际平台操作，不是部署门面。 */
    private final PlatformServerService service;

    /** Actual independent rescue coordinator installed by the platform shell. / 平台外壳安装的实际独立救援协调器。 */
    private gold.debug.windowstolinux.app.linux.ssh.recover.SshRecovery recovery;

    /** Navigation to permanent model configuration. / 前往常驻模型配置的导航。 */
    private Runnable modelSettings;

    /** Starts or reopens the original selected-server rescue. / 启动或重新打开所选服务器原救援。 */
    private final JButton rescue;

    /** Starts rescue for the original failed check, independent of the current list selection. / 与当前列表选择无关，救援原失败检查目标。 */
    private final JButton failedRescue;

    /** Original check and endpoint bound as one result lifetime. / 作为同一结果生命周期绑定的原检查及端点。 */
    private CheckBinding checkBinding;

    /** Current themed controls. / 当前主题控件。 */
    private final DesktopPartFactory components;

    /** Localized safe output. / 本地化安全输出。 */
    private final PageMessagePresenter messages;

    /** Current non-secret configuration rows. / 当前非秘密配置行。 */
    private final ServerRows rows = new ServerRows();

    /** Single selected saved server. / 单选已保存服务器。 */
    private final JTable table;

    /** Search and stable selection state belonging to this view. / 属于此视图的搜索与稳定选择状态。 */
    private final SelectionControls selection;

    /** Original unfinished checks, including previous boots. / 原始未结束检查，包括前次启动。 */
    private final JComboBox<TaskSnapshot> checks = new JComboBox<>();

    /** Safe progress and completion text. / 安全的进度及完成文本。 */
    private final JTextArea output = DesktopPartFactory.outputArea();

    /** Add configuration action. / 添加配置动作。 */
    private final JButton add;

    /** Edit selected configuration action. / 编辑所选配置动作。 */
    private final JButton edit;

    /** Delete selected configuration action. / 删除所选配置动作。 */
    private final JButton delete;

    /** Refresh persisted settings action. / 刷新持久设置动作。 */
    private final JButton refresh;

    /** Explicit authentication-only check action. / 显式仅认证检查动作。 */
    private final JButton check;

    /** Cooperative check cancellation action. / 协作取消检查动作。 */
    private final JButton cancel;

    /** Original-identity resource reconciliation action. / 原身份资源核对动作。 */
    private final JButton reconcile;

    /** In-memory task polling, stopped on actual page disposal. / 内存任务轮询，在页面实际销毁时停止。 */
    private final Timer timer;

    /** Visual regions independent of task and selection ownership. / 独立于任务与选择归属的展示区域。 */
    private final ServerPagePresentation presentation;

    /** Check output bound to its exact original configuration. / 检查输出绑定精确原配置。
     * @param profile original configuration / 原配置
     * @param task original task identity / 原任务身份
     * @param text localized actual output / 本地化实际输出 */
    private record CheckRecord(ServerProfile profile, UUID task, java.util.function.Supplier<String> text) {
    }

    /**
     * Creates the permanent view on the event thread. / 在事件线程创建常驻视图。
     * @param owner actual parent window / 实际父窗口
     * @param service platform server operations / 平台服务器操作
     * @param components current themed controls / 当前主题控件
     * @param messages localized safe diagnostics / 本地化安全诊断
     */
    public PlatformServersPane(Window owner, PlatformServerService service, DesktopPartFactory components,
                               PageMessagePresenter messages) {
        super(new BorderLayout(12, 12));
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Server page requires the EDT");
        this.owner = owner;
        this.service = Objects.requireNonNull(service);
        this.components = Objects.requireNonNull(components);
        this.messages = Objects.requireNonNull(messages);
        table = new JTable(rows);
        selection = new SelectionControls();
        setName("platform.servers");
        components.stylePage(this);
        setBorder(BorderFactory.createCompoundBorder(null, new com.formdev.flatlaf.ui.FlatEmptyBorder(12, 20, 12, 20)));
        add = button("server.addShort", "servers.add", () -> editor(null));
        edit = button("server.editShort", "servers.edit", () -> editor(selected()));
        delete = button("server.delete", "servers.delete", this::delete);
        refresh = button("server.refresh", "servers.refresh", this::refresh);
        check = button("server.check", "servers.check", this::check);
        rescue = button("server.rescueShort", "servers.rescue", this::rescue);
        cancel = button("server.cancelCheck", "servers.cancel", this::cancel);
        reconcile = button("server.reconcile", "servers.reconcile", this::reconcile);
        var more = button("server.more", "servers.more", () -> {
        });
        failedRescue = button("server.rescueFailedCheck", "servers.rescueFailedCheck", () -> rescue(checkBinding == null ? null : checkBinding.profile()));
        presentation = new ServerPagePresentation(components, messages, table, selection.search, () -> rows.values,
            checks, output, new ServerPagePresentation.Controls(add, edit, delete, refresh, check, rescue, more, cancel, reconcile, failedRescue));
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) selection.remember();
            controls();
        });
        add(presentation, BorderLayout.CENTER);
        checks.addActionListener(event -> controls());
        timer = new Timer(250, event -> poll());
        timer.start();
        controls();
        components.scrollPage(this);
    }

    /** Binds the real rescue entry once without adding a business extension dependency. / 绑定一次真实救援入口，不添加业务扩展依赖。
     * @param service actual platform rescue / 实际平台救援
     * @param modelSettings permanent model-settings navigation / 常驻模型设置导航 */
    public void installRecovery(gold.debug.windowstolinux.app.linux.ssh.recover.SshRecovery service,
                                Runnable modelSettings) {
        if (recovery != null || !SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Rescue must be installed once on the EDT");
        recovery = Objects.requireNonNull(service);
        this.modelSettings = Objects.requireNonNull(modelSettings);
        controls();
    }

    /** Opens the existing original rescue or submits one new complete-server task. / 打开既有原救援或提交一个新的完整服务器任务。 */
    private void rescue() {
        rescue(selected());
    }

    /** Uses the captured configuration and the existing rescue coordinator. / 使用捕获配置及既有救援协调器。
     * @param profile original checked or explicitly selected configuration / 原检查或明确选定配置
     */
    private void rescue(ServerProfile profile) {
        if (profile == null || recovery == null)
            return;
        work(() -> recovery.start(new gold.debug.windowstolinux.app.linux.ssh.recover.SshRecovery.Server(profile.id(), profile.host(), profile.sshPort(), profile.username(), profile.credentialKey(), profile.displayName())), session -> {
            gold.debug.windowstolinux.app.ui.server.SshRecoveryDialog.platform(this, profile, session, modelSettings,
                components, messages);
            poll();
        });
    }

    /**
     * Applies privacy state and reloads saved profiles after unlocking. / 应用隐私状态并在解锁后重新读取已保存配置。
     * @param value whether interactive data must remain hidden / 交互数据是否必须保持隐藏
     */
    public void setLocked(boolean value) {
        if (view.locked != value) {
            view.epoch++;
            view.request++;
            view.busy = false;
            checkBinding = null;
            failedRescue.setVisible(false);
            view.records.clear();
            view.taskOutput = null;
        }
        view.locked = value;
        if (!value && !view.busy) {
            refresh();
        }
        controls();
    }

    /**
     * Creates one localized action control. / 创建一个本地化动作控件。
     * @param key localized label key / 本地化标签键
     * @param name stable UI identity / 稳定 UI 身份
     * @param action event-thread action / 事件线程动作
     * @return configured button / 已配置按钮
     */
    private JButton button(String key, String name, Runnable action) {
        var button = messages.bind(name.equals("servers.add") || name.equals("servers.check")
            ? components.primaryButton("") : components.secondaryButton(""), key);
        button.setName(name);
        button.setMargin(new Insets(8, 14, 8, 14));
        if (name.equals("servers.add")) button.setIcon(gold.debug.windowstolinux.app.ui.control.DesktopIcons.icon(
            "action/plus", 16, button::getForeground));
        if (name.equals("servers.refresh")) button.setIcon(gold.debug.windowstolinux.app.ui.control.DesktopIcons.icon(
            "action/refresh-cw", 16, button::getForeground));
        button.addActionListener(event -> action.run());
        return button;
    }

    /** Returns the selected saved profile or no selection. / 返回所选已保存配置或无选择。
     * @return selected profile, possibly null / 所选配置，可为空 */
    private ServerProfile selected() {
        int row = table.getSelectedRow();
        return row < 0 || row >= table.getRowCount()
            ? null
            : rows.values.get(table.convertRowIndexToModel(row)).profile();
    }

    /**
     * Opens an independent configuration editor without connecting to SSH. / 打开独立配置编辑器，不连接 SSH。
     * @param profile selected profile or null for addition / 所选配置，添加时为空
     */
    private void editor(ServerProfile profile) {
        if (!view.locked && !view.busy)
            new ServerProfileDialog(owner, service, components, messages, profile, saved -> refresh()).setVisible(true);
    }

    /** Reloads configuration on a worker, preserving the selected stable identity. / 在工作线程重新加载配置，保留所选稳定身份。 */
    private void refresh() {
        work(service::list, selection::replace);
    }

    /** Confirms removal and delegates task, reference and credential ownership checks. / 确认删除并委派任务、引用及凭据归属检查。 */
    private void delete() {
        var profile = selected();
        if (profile == null || view.locked || view.busy)
            return;
        if (JOptionPane.showConfirmDialog(owner,
            messages.text("server.deleteConfirm", Map.of("name", profile.displayName())),
            messages.text("server.delete"), JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION)
            return;
        work(() -> {
            service.delete(profile.id());
            return null;
        }, ignored -> refresh());
    }

    /** Submits one central authentication check and observes its actual terminal result. / 提交一次中央认证检查并观察实际终态结果。 */
    private void check() {
        var profile = selected();
        if (profile == null)
            return;
        work(() -> service.check(profile), handle -> {
            long generation = view.epoch;
            checkBinding = new CheckBinding(handle.id(), profile, handle, false);
            failedRescue.setVisible(false);
            view.taskOutput = null;
            view.records.put(profile.id(), new CheckRecord(profile, handle.id(), () -> messages.text("server.checking")));
            showSelection();
            handle.completion().whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
                if (view.closed || view.locked || generation != view.epoch || checkBinding == null || !handle.id().equals(checkBinding.id()))
                    return;
                view.records.put(profile.id(), new CheckRecord(profile, handle.id(), () -> failure != null
                    ? messages.safe(new java.util.concurrent.CompletionException(failure))
                    : messages.text("platform.outcome." + result.outcome())
                    + result.value().map(facts -> "\n" + facts.fingerprint()).orElse("")
                    + result.problem().map(problem -> "\n" + messages.safe(
                    new gold.debug.windowstolinux.appapi.task.result.ApiException(problem))).orElse("")));
                if (failure == null && result.outcome() == gold.debug.windowstolinux.appapi.task.result.TaskResult.FAILED
                    && handle.snapshot().state() == TaskState.ENDED) {
                    checkBinding = new CheckBinding(handle.id(), profile, handle, true);
                    failedRescue.setVisible(true);
                }
                poll();
                refresh();
            }));
            poll();
        });
    }

    /** Requests cooperative cancellation without releasing live resource ownership. / 请求协作取消，不释放存活资源归属。 */
    private void cancel() {
        var task = (TaskSnapshot) checks.getSelectedItem();
        if (task != null)
            work(() -> {
                if (task.type()
                    .equals(gold.debug.windowstolinux.app.linux.ssh.recover.SshRecovery.TASK_TYPE))
                    recovery.cancel(task.id());
                else
                    service.cancel(task.id());
                return null;
            }, ignored -> poll());
    }

    /** Confirms explicit interruption recovery, which never repeats the old connection. / 确认显式中断恢复，绝不重复旧连接。 */
    private void reconcile() {
        var task = (TaskSnapshot) checks.getSelectedItem();
        if (task == null || view.locked || view.busy)
            return;
        boolean rescueTask = task.type()
            .equals(gold.debug.windowstolinux.app.linux.ssh.recover.SshRecovery.TASK_TYPE);
        if (JOptionPane.showConfirmDialog(owner,
            messages.text(rescueTask ? "recovery.taskReconcileConfirm" : "server.reconcileConfirm"),
            messages.text("server.reconcile"), JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION)
            return;
        work(() -> (rescueTask ? recovery.reconcile(task.id(), true) : service.reconcile(task.id()))
            .toCompletableFuture().get(), result -> {
            view.taskOutputTarget = task.progressText() + " · " + messages.text("server.taskRecord", Map.of("id", task.id()));
            view.taskOutput = messages.text("platform.task." + result.state()) + "\n"
                + (result.state() == TaskState.ENDED
                ? messages.text(rescueTask ? "recovery.taskInterrupted" : "server.interrupted")
                : String.join("\n", result.blockers()));
            poll();
        });
    }

    /** Updates only in-memory task snapshots on the event thread. / 在事件线程仅更新内存任务快照。 */
    private void poll() {
        if (view.locked || view.closed)
            return;
        var selected = (TaskSnapshot) checks.getSelectedItem();
        UUID id = selected == null ? null : selected.id();
        var values = java.util.stream.Stream
            .concat(service.checks().stream(),
                recovery == null ? java.util.stream.Stream.<TaskSnapshot>empty() : recovery.pending().stream())
            .toList();
        boolean changed = checks.getItemCount() != values.size();
        for (int i = 0; !changed && i < values.size(); i++)
            changed = !values.get(i).equals(checks.getItemAt(i));
        if (changed) {
            checks.removeAllItems();
            for (var task : values) {
                checks.addItem(task);
                if (task.id().equals(id))
                    checks.setSelectedItem(task);
            }
        }
        controls();
    }

    /** Updates action availability without database or network calls. / 更新动作可用状态，不调用数据库或网络。 */
    private void controls() {
        boolean enabled = !view.closed && !view.locked && !view.busy;
        selection.search.setEnabled(enabled);
        add.setEnabled(enabled);
        refresh.setEnabled(enabled);
        edit.setEnabled(enabled && selected() != null);
        delete.setEnabled(enabled && selected() != null);
        check.setEnabled(enabled && selected() != null);
        rescue.setEnabled(enabled && recovery != null && selected() != null);
        presentation.targetEnabled(enabled && selected() != null);
        presentation.extensionContext(java.util.Optional.ofNullable(selected())
                .map(profile -> new gold.debug.windowstolinux.appapi.server.model.ServerId(profile.id())),
            enabled && selected() != null);
        failedRescue.setEnabled(enabled && recovery != null && checkBinding != null && checkBinding.failed()
            && checkBinding.handle().snapshot().state() == TaskState.ENDED);
        var task = (TaskSnapshot) checks.getSelectedItem();
        cancel.setEnabled(
            enabled && task != null && (task.state() == TaskState.RUNNING || task.state() == TaskState.QUEUED));
        reconcile.setEnabled(
            enabled && task != null && (task.state() == TaskState.UNKNOWN || task.state() == TaskState.FINISHING));
        showSelection();
    }

    /** Displays only records belonging to the selected configuration. / 仅展示归属所选配置的记录。 */
    private void showSelection() {
        int index = table.getSelectedRow();
        var summary = index < 0 ? null : rows.values.get(table.convertRowIndexToModel(index));
        String text, target, identity = null;
        if (view.taskOutput != null) {
            target = view.taskOutputTarget;
            identity = target;
            text = view.taskOutput;
        } else if (summary == null) {
            target = "";
            text = messages.text("server.choose");
        } else {
            var record = view.records.get(summary.profile().id());
            if (record != null && record.profile().equals(summary.profile())) {
                target = summary.profile().displayName() + " · " + record.task();
                identity = summary.profile().id() + " · " + ServerDetailsPane.endpoint(summary) + " · " + record.task();
                text = record.text().get();
            } else {
                target = summary.profile().displayName();
                identity = summary.profile().id() + " · " + ServerDetailsPane.endpoint(summary);
                text = summary.checkedAt().isEmpty() ? messages.text("server.recordsEmpty")
                    : messages.text("server.lastCheck") + ": " + ServerDetailsPane.status(summary, messages)
                    + "\n" + messages.text("server.checkedAt") + ": " + ServerDetailsPane.checkedAt(summary);
            }
        }
        presentation.show(summary, rows.values.size(), text, target, identity);
        if (checkBinding != null && checkBinding.failed())
            failedRescue.setToolTipText(checkBinding.profile().displayName() + " · " + checkBinding.id());
    }

    /** Updates dynamic labels and geometry without rebuilding selection or tasks. / 更新动态文案与尺寸，不重建选择或任务。 */
    public void localize() {
        selection.search.putClientProperty("JTextField.placeholderText", messages.text("server.search"));
        presentation.localize();
        controls();
    }

    /**
     * Runs a bounded UI operation off the event thread and suppresses locked-view callbacks. / 在事件线程之外执行有界 UI 操作，并抑制锁定视图回调。
     * @param operation blocking platform operation / 阻塞平台操作
     * @param success unlocked event-thread result handler / 解锁状态事件线程结果处理器
     * @param <T> operation result type / 操作结果类型
     */
    private <T> void work(Callable<T> operation, Consumer<T> success) {
        if (view.closed || view.locked || view.busy)
            return;
        view.busy = true;
        presentation.feedback("");
        long generation = view.epoch, identity = ++view.request;
        controls();
        DesktopTaskRunner.run(operation, value -> {
            if (view.closed || view.locked || view.epoch != generation || view.request != identity) return;
            view.busy = false;
            if (!view.closed && !view.locked)
                success.accept(value);
            controls();
        }, failure -> {
            if (view.closed || view.locked || view.epoch != generation || view.request != identity) return;
            view.busy = false;
            if (!view.closed && !view.locked)
                presentation.feedback(messages.safe(failure));
            controls();
        });
    }

    /** Stops view polling while accepted central tasks keep their original ownership. / 停止视图轮询，已接纳中央任务继续保持原归属。 */
    @Override
    public void close() {
        presentation.closeExtensions();
        view.closed = true;
        view.epoch++;
        view.request++;
        view.busy = false;
        timer.stop();
        rows.replace(List.of());
        checks.removeAllItems();
        output.setText("");
        view.records.clear();
        view.taskOutput = null;
    }

    /** Installs the host menu extension capability. / 安装宿主菜单扩展能力。
     * @param extensions APP-owned extension service / APP 所属扩展服务 */
    public void installExtensions(gold.debug.windowstolinux.appapi.ui.extension.UiExtensionService extensions) {
        try {
            presentation.installExtensions(extensions);
            controls();
        } catch (gold.debug.windowstolinux.appapi.task.result.ApiException failure) {
            throw new IllegalStateException("Server extension point could not be installed", failure);
        }
    }

    /** Owns explicit name/host search and preserves real user selection across refreshes. / 持有明确名称主机搜索，并跨刷新保留真实用户选择。 */
    private final class SelectionControls {
        /** Unmodified user search input. / 未修改的用户搜索输入。 */
        private final JTextField search = new JTextField(20);

        /** Converts visible rows to original model rows. / 将可见行转换为原模型行。 */
        private final javax.swing.table.TableRowSorter<ServerRows> sorter = new javax.swing.table.TableRowSorter<>(
            rows);

        /** Last explicitly selected stable identity, possibly cleared by the user. / 最后明确选定的稳定身份，可由用户清空。 */
        private String selectedId;

        /** Whether initial inventory has already been presented. / 是否已展示首次库存。 */
        private boolean initialized;

        /** Whether model/filter events must not replace user intent. / 模型过滤事件是否不得替换用户意图。 */
        private boolean updating;

        /** Binds actual table filtering on the EDT. / 在EDT绑定实际表格过滤。 */
        private SelectionControls() {
            search.setName("servers.search");
            table.setRowSorter(sorter);
            search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                /** Filters inserted input. / 过滤插入输入。
                 * @param event original document change / 原文档变化 */
                public void insertUpdate(javax.swing.event.DocumentEvent event) {
                    filter();
                }

                /** Filters removed input. / 过滤删除输入。
                 * @param event original document change / 原文档变化 */
                public void removeUpdate(javax.swing.event.DocumentEvent event) {
                    filter();
                }

                /** Filters styled input. / 过滤样式输入。
                 * @param event original document change / 原文档变化 */
                public void changedUpdate(javax.swing.event.DocumentEvent event) {
                    filter();
                }
            });
        }

        /** Remembers only an actual visible user selection or explicit clearing. / 仅记录实际可见用户选择或明确清空。 */
        private void remember() {
            if (!updating) {
                var profile = selected();
                selectedId = profile == null ? null : profile.id();
                view.taskOutput = null;
            }
        }

        /** Replaces the inventory without selecting another hidden or formerly cleared server. / 替换库存，不选择其他隐藏或曾清空的服务器。
         * @param values actual persisted summaries / 实际持久摘要 */
        private void replace(List<ServerSummary> values) {
            updating = true;
            try {
                rows.replace(values);
                view.records.keySet().retainAll(values.stream().map(value -> value.profile().id()).toList());
                if (!view.locked && !initialized && !values.isEmpty()) {
                    selectedId = values.getFirst().profile().id();
                    initialized = true;
                }
                restore();
            } finally {
                updating = false;
            }
        }

        /** Applies literal, case-insensitive name or host matching without parsing a regular expression. / 应用字面且不区分大小写的名称主机匹配，不解析正则表达式。 */
        private void filter() {
            String query = search.getText().trim().toLowerCase(java.util.Locale.ROOT);
            updating = true;
            try {
                sorter.setRowFilter(new RowFilter<ServerRows, Integer>() {
                    /** Keeps only literal matching server metadata. / 仅保留字面匹配的服务器元数据。
                     * @param entry original model row / 原模型行
                     * @return whether this actual name or host matches / 实际名称或主机是否匹配 */
                    public boolean include(Entry<? extends ServerRows, ? extends Integer> entry) {
                        var profile = rows.values.get(entry.getIdentifier()).profile();
                        return profile.displayName().toLowerCase(java.util.Locale.ROOT).contains(query)
                            || profile.host().toLowerCase(java.util.Locale.ROOT).contains(query);
                    }
                });
                restore();
            } finally {
                updating = false;
            }
            controls();
        }

        /** Restores only the same identity when visible, otherwise leaves the table unselected. / 仅在可见时恢复同一身份，否则保持表格未选择。 */
        private void restore() {
            table.clearSelection();
            if (selectedId == null)
                return;
            for (int model = 0; model < rows.values.size(); model++) {
                if (!rows.values.get(model).profile().id().equals(selectedId))
                    continue;
                int view = table.convertRowIndexToView(model);
                if (view >= 0)
                    table.setRowSelectionInterval(view, view);
                return;
            }
        }
    }

    /** Immutable configuration snapshots displayed by the table. / 表格展示的不可变配置快照。 */
    private final class ServerRows extends AbstractTableModel {
        /** Creates the original view's table model. / 创建原视图的表格模型。 */
        private ServerRows() {
        }

        /** Current non-secret rows. / 当前非秘密行。 */
        private List<ServerSummary> values = List.of();

        /**
         * Replaces the current snapshot on the event thread. / 在事件线程替换当前快照。
         * @param values copied saved summaries / 复制的已保存摘要
         */
        private void replace(List<ServerSummary> values) {
            this.values = List.copyOf(values);
            fireTableDataChanged();
        }

        /** Returns row count. / 返回行数。
         * @return number of saved profiles / 已保存配置数量 */
        @Override
        public int getRowCount() {
            return values.size();
        }

        /** Returns visible non-secret columns. / 返回可见非秘密列。
         * @return fixed column count / 固定列数 */
        @Override
        public int getColumnCount() {
            return 5;
        }

        /**
         * Returns a localized column heading. / 返回本地化列标题。
         * @param column visible column / 可见列
         * @return localized heading / 本地化标题
         */
        @Override
        public String getColumnName(int column) {
            return messages.text(switch (column) {
                case 0 -> "server.name";
                case 1 -> "field.host";
                case 2 -> "field.sshUser";
                case 3 -> "server.lastCheck";
                default -> "server.checkedAt";
            });
        }

        /**
         * Returns dated observations without implying a new live probe. / 返回带时间观测，不暗示新的实时探测。
         * @param row saved profile index / 已保存配置索引
         * @param column visible column / 可见列
         * @return display value / 展示值
         */
        @Override
        public Object getValueAt(int row, int column) {
            var value = values.get(row);
            return switch (column) {
                case 0 -> value.profile().displayName();
                case 1 -> value.profile().host() + ":" + value.profile().sshPort();
                case 2 -> value.profile().username();
                case 3 -> messages.text(value.checkedAt().isEmpty()
                    ? "server.unchecked"
                    : value.connected() ? "server.connected" : "server.failed");
                default -> value.checkedAt().map(time -> DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault()).format(time)).orElse("—");
            };
        }
    }
}
