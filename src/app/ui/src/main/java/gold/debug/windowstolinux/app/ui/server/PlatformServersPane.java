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

import gold.debug.windowstolinux.app.service.contract.profile.ServerProfile;
import gold.debug.windowstolinux.app.service.server.*;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.appapi.task.*;

/** Permanent configuration and authentication-only SSH page with explicit task recovery. / 带显式任务恢复的常驻配置及仅认证 SSH 页面。 */
public final class PlatformServersPane extends JPanel implements AutoCloseable {
    /** Parent for configuration and confirmation dialogs. / 配置及确认窗口的父级。 */
    private final Window owner;

    /** Actual platform operations, never a deployment facade. / 实际平台操作，不是部署门面。 */
    private final PlatformServerService service;

    /** Actual independent rescue coordinator installed by the platform shell. / 平台外壳安装的实际独立救援协调器。 */
    private gold.debug.windowstolinux.app.service.recovery.PlatformRecoveryService recovery;

    /** Navigation to permanent model configuration. / 前往常驻模型配置的导航。 */
    private Runnable modelSettings;

    /** Starts or reopens the original selected-server rescue. / 启动或重新打开所选服务器原救援。 */
    private final JButton rescue;

    /** Current themed controls. / 当前主题控件。 */
    private final DesktopComponentFactory components;

    /** Localized safe output. / 本地化安全输出。 */
    private final PageMessagePresenter messages;

    /** Current non-secret configuration rows. / 当前非秘密配置行。 */
    private final ServerRows rows = new ServerRows();

    /** Single selected saved server. / 单选已保存服务器。 */
    private final JTable table;

    /** Original unfinished checks, including previous boots. / 原始未结束检查，包括前次启动。 */
    private final JComboBox<TaskSnapshot> checks = new JComboBox<>();

    /** Safe progress and completion text. / 安全的进度及完成文本。 */
    private final JTextArea output = DesktopComponentFactory.outputArea();

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

    /** Whether interactive access is currently blocked. / 当前交互访问是否被阻止。 */
    private boolean locked = true;

    /** Whether a configuration or submission worker is pending. / 配置或提交工作线程是否待完成。 */
    private boolean busy;

    /** Whether callbacks must stop touching a disposed view. / 回调是否必须停止访问已销毁视图。 */
    private boolean closed;

    /**
     * Creates the permanent view on the event thread. / 在事件线程创建常驻视图。
     * @param owner actual parent window / 实际父窗口
     * @param service platform server operations / 平台服务器操作
     * @param components current themed controls / 当前主题控件
     * @param messages localized safe diagnostics / 本地化安全诊断
     */
    public PlatformServersPane(Window owner, PlatformServerService service, DesktopComponentFactory components,
            PageMessagePresenter messages) {
        super(new BorderLayout(12, 12));
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Server page requires the EDT");
        this.owner = owner;
        this.service = Objects.requireNonNull(service);
        this.components = Objects.requireNonNull(components);
        this.messages = Objects.requireNonNull(messages);
        table = new JTable(rows);
        setName("platform.servers");
        setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        var heading = new JPanel(new BorderLayout(8, 8));
        heading.add(new JLabel(messages.text("server.platform.help")), BorderLayout.NORTH);
        var actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        add = button("auto.addServer", "servers.add", () -> editor(null));
        edit = button("server.edit", "servers.edit", () -> editor(selected()));
        delete = button("server.delete", "servers.delete", this::delete);
        refresh = button("server.refresh", "servers.refresh", this::refresh);
        check = button("server.check", "servers.check", this::check);
        rescue = button("recovery.title", "servers.rescue", this::rescue);
        for (var button : List.of(add, edit, delete, refresh, check, rescue))
            actions.add(button);
        heading.add(actions, BorderLayout.SOUTH);
        add(heading, BorderLayout.NORTH);
        table.setName("servers.table");
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getSelectionModel().addListSelectionListener(event -> controls());
        add(new JScrollPane(table), BorderLayout.CENTER);
        var tasks = new JPanel(new BorderLayout(8, 8));
        var taskActions = new JPanel(new BorderLayout(8, 8));
        checks.setName("servers.checks");
        checks.setRenderer(new DefaultListCellRenderer() {
            /**
             * Formats original task identity and state without secret data. / 格式化原任务身份及状态，不含秘密数据。
             * @param list owning list / 所属列表
             * @param value task snapshot / 任务快照
             * @param index row index / 行索引
             * @param selected whether selected / 是否选中
             * @param focus whether focused / 是否聚焦
             * @return rendered row / 已呈现行
             */
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                    boolean focus) {
                String label = value instanceof TaskSnapshot task
                        ? task.progressText() + " · " + messages.text("platform.task." + task.state()) + " · "
                                + task.id()
                        : messages.text("server.noChecks");
                return super.getListCellRendererComponent(list, label, index, selected, focus);
            }
        });
        checks.addActionListener(event -> controls());
        taskActions.add(checks, BorderLayout.CENTER);
        var recoveryActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        cancel = button("server.cancelCheck", "servers.cancel", this::cancel);
        reconcile = button("server.reconcile", "servers.reconcile", this::reconcile);
        recoveryActions.add(cancel);
        recoveryActions.add(reconcile);
        taskActions.add(recoveryActions, BorderLayout.EAST);
        tasks.add(taskActions, BorderLayout.NORTH);
        output.setName("servers.output");
        output.setRows(5);
        output.setLineWrap(true);
        output.setWrapStyleWord(true);
        tasks.add(new JScrollPane(output), BorderLayout.CENTER);
        add(tasks, BorderLayout.SOUTH);
        timer = new Timer(250, event -> poll());
        timer.start();
        controls();
    }

    /** Binds the real rescue entry once without adding a business extension dependency. / 绑定一次真实救援入口，不添加业务扩展依赖。
     * @param service actual platform rescue / 实际平台救援
     * @param modelSettings permanent model-settings navigation / 常驻模型设置导航 */
    public void installRecovery(gold.debug.windowstolinux.app.service.recovery.PlatformRecoveryService service,
            Runnable modelSettings) {
        if (recovery != null || !SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Rescue must be installed once on the EDT");
        recovery = Objects.requireNonNull(service);
        this.modelSettings = Objects.requireNonNull(modelSettings);
        controls();
    }

    /** Opens the existing original rescue or submits one new complete-server task. / 打开既有原救援或提交一个新的完整服务器任务。 */
    private void rescue() {
        var profile = selected();
        if (profile == null || recovery == null)
            return;
        work(() -> recovery.start(profile), session -> {
            gold.debug.windowstolinux.app.ui.recovery.SshRecoveryDialog.platform(this, profile, session, modelSettings,
                    components, messages);
            poll();
        });
    }

    /**
     * Applies privacy state and reloads saved profiles after unlocking. / 应用隐私状态并在解锁后重新读取已保存配置。
     * @param locked whether interactive data must remain hidden / 交互数据是否必须保持隐藏
     */
    public void setLocked(boolean locked) {
        this.locked = locked;
        if (locked) {
            rows.replace(List.of());
            checks.removeAllItems();
            output.setText("");
        } else if (!busy) {
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
        var button = components.secondaryButton(messages.text(key));
        button.setName(name);
        button.addActionListener(event -> action.run());
        return button;
    }

    /** Returns the selected saved profile or no selection. / 返回所选已保存配置或无选择。
     * @return selected profile, possibly null / 所选配置，可为空 */
    private ServerProfile selected() {
        int row = table.getSelectedRow();
        return row < 0 || row >= rows.values.size() ? null : rows.values.get(row).profile();
    }

    /**
     * Opens an independent configuration editor without connecting to SSH. / 打开独立配置编辑器，不连接 SSH。
     * @param profile selected profile or null for addition / 所选配置，添加时为空
     */
    private void editor(ServerProfile profile) {
        if (!locked && !busy)
            new ServerProfileDialog(owner, service, components, messages, profile, saved -> refresh()).setVisible(true);
    }

    /** Reloads configuration on a worker, preserving the selected stable identity. / 在工作线程重新加载配置，保留所选稳定身份。 */
    private void refresh() {
        String selected = selected() == null ? null : selected().id();
        work(service::list, values -> {
            rows.replace(values);
            for (int i = 0; i < values.size(); i++)
                if (values.get(i).profile().id().equals(selected))
                    table.setRowSelectionInterval(i, i);
        });
    }

    /** Confirms removal and delegates task, reference and credential ownership checks. / 确认删除并委派任务、引用及凭据归属检查。 */
    private void delete() {
        var profile = selected();
        if (profile == null || locked || busy)
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
            output.setText(messages.text("server.checking"));
            handle.completion().whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
                if (closed || locked)
                    return;
                output.setText(failure != null
                        ? messages.safe(new java.util.concurrent.CompletionException(failure))
                        : messages.text("platform.outcome." + result.outcome())
                                + result.value().map(facts -> "\n" + facts.fingerprint()).orElse(""));
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
                        .equals(gold.debug.windowstolinux.app.service.recovery.PlatformRecoveryService.TASK_TYPE))
                    recovery.cancel(task.id());
                else
                    service.cancel(task.id());
                return null;
            }, ignored -> poll());
    }

    /** Confirms explicit interruption recovery, which never repeats the old connection. / 确认显式中断恢复，绝不重复旧连接。 */
    private void reconcile() {
        var task = (TaskSnapshot) checks.getSelectedItem();
        if (task == null || locked || busy)
            return;
        boolean rescueTask = task.type()
                .equals(gold.debug.windowstolinux.app.service.recovery.PlatformRecoveryService.TASK_TYPE);
        if (JOptionPane.showConfirmDialog(owner,
                messages.text(rescueTask ? "recovery.taskReconcileConfirm" : "server.reconcileConfirm"),
                messages.text("server.reconcile"), JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION)
            return;
        work(() -> (rescueTask ? recovery.reconcile(task.id(), true) : service.reconcile(task.id()))
                .toCompletableFuture().get(), result -> {
                    output.setText(messages.text("platform.task." + result.state()) + "\n"
                            + (result.state() == TaskState.ENDED
                                    ? messages.text(rescueTask ? "recovery.taskInterrupted" : "server.interrupted")
                                    : String.join("\n", result.blockers())));
                    poll();
                });
    }

    /** Updates only in-memory task snapshots on the event thread. / 在事件线程仅更新内存任务快照。 */
    private void poll() {
        if (locked || closed)
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
        boolean enabled = !closed && !locked && !busy;
        add.setEnabled(enabled);
        refresh.setEnabled(enabled);
        edit.setEnabled(enabled && selected() != null);
        delete.setEnabled(enabled && selected() != null);
        check.setEnabled(enabled && selected() != null);
        rescue.setEnabled(enabled && recovery != null && selected() != null);
        var task = (TaskSnapshot) checks.getSelectedItem();
        cancel.setEnabled(
                enabled && task != null && (task.state() == TaskState.RUNNING || task.state() == TaskState.QUEUED));
        reconcile.setEnabled(
                enabled && task != null && (task.state() == TaskState.UNKNOWN || task.state() == TaskState.FINISHING));
    }

    /**
     * Runs a bounded UI operation off the event thread and suppresses locked-view callbacks. / 在事件线程之外执行有界 UI 操作，并抑制锁定视图回调。
     * @param operation blocking platform operation / 阻塞平台操作
     * @param success unlocked event-thread result handler / 解锁状态事件线程结果处理器
     * @param <T> operation result type / 操作结果类型
     */
    private <T> void work(Callable<T> operation, Consumer<T> success) {
        if (closed || locked || busy)
            return;
        busy = true;
        controls();
        DesktopTaskExecutor.run(operation, value -> {
            busy = false;
            if (!closed && !locked)
                success.accept(value);
            controls();
        }, failure -> {
            busy = false;
            if (!closed && !locked)
                output.setText(messages.safe(failure));
            controls();
        });
    }

    /** Stops view polling while accepted central tasks keep their original ownership. / 停止视图轮询，已接纳中央任务继续保持原归属。 */
    @Override
    public void close() {
        closed = true;
        timer.stop();
        rows.replace(List.of());
        checks.removeAllItems();
        output.setText("");
    }

    /** Immutable configuration snapshots displayed by the table. / 表格展示的不可变配置快照。 */
    private final class ServerRows extends AbstractTableModel {
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
