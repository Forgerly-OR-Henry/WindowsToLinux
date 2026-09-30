package gold.debug.windowstolinux.app.ui.recovery;

import java.awt.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;

import gold.debug.windowstolinux.app.service.contract.spi.TaskManagement;
import gold.debug.windowstolinux.app.service.contract.spi.TaskManagement.Entry;
import gold.debug.windowstolinux.app.service.contract.spi.TaskManagement.RecoveryState;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.diagnostic.PageMessagePresenter;
import gold.debug.windowstolinux.appapi.task.*;

/** Retained-task inspection and explicit original cancellation or reconciliation. / 保留任务检查及明确原任务取消或核对。 */
public final class TaskRecoveryPane extends JPanel implements AutoCloseable {
    /** Host-only task access. / 仅供宿主的任务访问。 */
    private final TaskManagement service;

    /** Localized text and safe failures. / 本地化文字及安全失败。 */
    private final PageMessagePresenter messages;

    /** Explicit inventory and original-task actions. / 明确清单及原任务操作。 */
    private final JButton reload, cancel, reconcile, history;

    /** Original task identities and states. / 原任务身份及状态。 */
    private final JTable table = new JTable();

    /** Selected progress, identity and outstanding resource descriptions. / 已选进度、身份及未释放资源描述。 */
    private final JTextArea details = new JTextArea();

    /** Actual action or inventory feedback. / 实际操作或清单反馈。 */
    private final JLabel status = new JLabel();

    /** Last received immutable control observations. / 最近收到的不可变操作观察。 */
    private java.util.List<Entry> entries = java.util.List.of();

    /** Current explicit confirmation, disposed immediately on lock or close. / 当前明确确认，锁定或关闭时立即销毁。 */
    private JDialog confirmation;

    /** Privacy generation used to reject late callbacks and decisions. / 用于拒绝迟到回调及决定的隐私代次。 */
    private long epoch;

    /** Privacy, actual background work and view lifetime. / 隐私、实际后台工作及视图寿命。 */
    private boolean locked = true, busy, closed;

    /** Builds controls without reading tasks on the event thread. / 构造控件，不在事件线程读取任务。
     * @param service original host task access / 原宿主任务访问
     * @param components themed controls / 主题控件
     * @param messages localized feedback / 本地化反馈
     * @param history opens the existing log view containing completed task summaries / 打开含已结束任务摘要的现有日志界面 */
    public TaskRecoveryPane(TaskManagement service, DesktopComponentFactory components, PageMessagePresenter messages,
            Runnable history) {
        super(new BorderLayout(10, 10));
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Task controls require the EDT");
        this.service = Objects.requireNonNull(service);
        this.messages = Objects.requireNonNull(messages);
        Objects.requireNonNull(history);
        setName("platform.tasks");
        setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        reload = messages.bind(components.secondaryButton(""), "tasks.reload");
        cancel = messages.bind(components.secondaryButton(""), "tasks.cancel");
        reconcile = messages.bind(components.secondaryButton(""), "tasks.reconcile");
        this.history = messages.bind(components.secondaryButton(""), "tasks.history");
        reload.setName("tasks.reload");
        cancel.setName("tasks.cancel");
        reconcile.setName("tasks.reconcile");
        this.history.setName("tasks.history");
        reload.addActionListener(event -> refresh());
        cancel.addActionListener(event -> act(true));
        reconcile.addActionListener(event -> act(false));
        this.history.addActionListener(event -> {
            if (!locked && !closed)
                history.run();
        });
        var actions = components.transparent(new FlowLayout(FlowLayout.LEFT));
        actions.add(reload);
        actions.add(cancel);
        actions.add(reconcile);
        actions.add(this.history);
        var heading = components.transparent(new BorderLayout(8, 8));
        var explanation = new JTextArea();
        explanation.setEditable(false);
        messages.bind(explanation, "tasks.explanation");
        explanation.setOpaque(false);
        explanation.setLineWrap(true);
        explanation.setWrapStyleWord(true);
        explanation.setRows(2);
        heading.add(explanation, BorderLayout.NORTH);
        heading.add(actions, BorderLayout.CENTER);
        status.setName("tasks.status");
        heading.add(status, BorderLayout.SOUTH);
        add(heading, BorderLayout.NORTH);
        table.setName("tasks.table");
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(event -> showSelection());
        details.setName("tasks.details");
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setRows(12);
        var split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(details));
        split.setResizeWeight(0.5);
        add(split, BorderLayout.CENTER);
        display(java.util.List.of(), null);
    }

    /** Clears private observations and invalidates old decisions on every lock transition. / 每次锁定切换清空私有观察并使旧决定失效。
     * @param value actual desktop lock state / 实际桌面锁定状态 */
    public void setLocked(boolean value) {
        epoch++;
        locked = value;
        dismissConfirmation();
        display(java.util.List.of(), null);
        status.setText("");
        if (!value)
            refresh();
    }

    /** Refreshes only inventory; entering this page never runs recovery. / 仅刷新清单，进入此页绝不运行恢复。 */
    public void refresh() {
        var selected = selected();
        UUID id = selected == null ? null : selected.snapshot().id();
        work(service::list, result -> {
            display(result, id);
            status.setText(messages.text(result.isEmpty() ? "tasks.empty" : "tasks.refreshed"));
        });
    }

    /** Runs one original-task action after its own current explicit confirmation. / 经本操作当前明确确认后运行一个原任务操作。
     * @param cancellation whether cooperative cancellation was chosen / 是否选择协作取消 */
    private void act(boolean cancellation) {
        var selected = selected();
        if (selected == null || locked || busy || closed || confirmation != null
                || cancellation && !selected.cancellable() || !cancellation && !recoverable(selected))
            return;
        if (!confirm(selected, cancellation))
            return;
        work(() -> {
            var snapshot = cancellation
                    ? service.requestCancel(selected.snapshot().id())
                    : service.reconcile(selected.snapshot().id(), selected.recovery() == RecoveryState.CONFIRM_RESCUE)
                            .toCompletableFuture().get();
            return new ActionResult(snapshot, service.list());
        }, result -> {
            display(result.entries(), selected.snapshot().id());
            var snapshot = result.snapshot();
            String state = snapshot.outcome().map(value -> messages.text("tasks.outcome." + value.name()))
                    .orElseGet(() -> messages.text("tasks.state." + snapshot.state().name()));
            status.setText(messages.text("tasks.result", Map.of("state", state)));
        });
    }

    /** Confirms the exact identity and required recovery semantics in an owned dialog. / 在从属窗口确认精确身份及必要恢复语义。
     * @param entry original selected task / 原已选任务
     * @param cancellation whether this is cancellation / 是否为取消
     * @return whether the same unlocked view received acceptance / 同一已解锁视图是否收到接受 */
    private boolean confirm(Entry entry, boolean cancellation) {
        long observed = epoch;
        String key = cancellation ? "tasks.cancelConfirm" : switch (entry.recovery()) {
            case ARCHIVE_ONLY -> "tasks.archiveConfirm";
            case CONFIRM_RESCUE -> "recovery.taskReconcileConfirm";
            default -> "tasks.reconcileConfirm";
        };
        var text = new JTextArea(messages.text(key) + "\n\n" + identity(entry.snapshot()));
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        var content = new JScrollPane(text);
        content.setPreferredSize(new Dimension(600, 220));
        var choice = new JOptionPane(content, JOptionPane.WARNING_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        var dialog = choice.createDialog(this, messages.text("tasks.title"));
        dialog.setName("tasks.confirmation");
        confirmation = dialog;
        controls();
        try {
            dialog.setVisible(true);
            return !locked && !closed && observed == epoch && Objects.equals(choice.getValue(), JOptionPane.OK_OPTION);
        } finally {
            dialog.dispose();
            if (confirmation == dialog)
                confirmation = null;
            controls();
        }
    }

    /** Runs existing task APIs off the EDT and rejects results from an older privacy generation. / 在 EDT 外运行已有任务接口，拒绝旧隐私代次结果。
     * @param <T> result type / 结果类型
     * @param operation original read or explicit action / 原读取或明确操作
     * @param success current-view rendering / 当前视图呈现 */
    private <T> void work(Callable<T> operation, Consumer<T> success) {
        if (locked || busy || closed || confirmation != null)
            return;
        long observed = epoch;
        busy = true;
        controls();
        status.setText(messages.text("tasks.working"));
        DesktopTaskExecutor.run(operation, result -> {
            if (!returned(observed))
                return;
            success.accept(result);
            controls();
        }, failure -> {
            if (!returned(observed))
                return;
            display(java.util.List.of(), null);
            status.setText(messages.safe(failure));
            controls();
        });
    }

    /** Releases UI work ownership and refreshes after a lock changed during the operation. / 释放界面工作归属，在操作期间锁定状态变化后刷新。
     * @param observed starting privacy generation / 开始时的隐私代次
     * @return whether this result still belongs to the unlocked view / 结果是否仍属于已解锁视图 */
    private boolean returned(long observed) {
        busy = false;
        if (closed)
            return false;
        controls();
        if (locked || observed != epoch) {
            if (!locked)
                refresh();
            return false;
        }
        return true;
    }

    /** Displays only received observations and restores selection by original UUID. / 仅显示收到的观察，并按原 UUID 恢复选择。
     * @param values current task observations / 当前任务观察
     * @param selected original selection, if any / 可选的原选择 */
    private void display(java.util.List<Entry> values, UUID selected) {
        entries = java.util.List.copyOf(values);
        var model = new DefaultTableModel(new String[]{messages.text("tasks.owner"), messages.text("tasks.version"),
                messages.text("tasks.type"), messages.text("tasks.state"), messages.text("tasks.availability")}, 0) {
            /** Keeps task observations read-only. / 保持任务观察只读。
             * @param row observed row / 观察行
             * @param column observed column / 观察列
             * @return always false / 始终为假 */
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        for (var entry : entries) {
            var snapshot = entry.snapshot();
            model.addRow(new Object[]{snapshot.ownerId(), snapshot.ownerVersion(), snapshot.type(),
                    messages.text("tasks.state." + snapshot.state().name()),
                    messages.text("tasks.recovery." + entry.recovery().name())});
        }
        table.setModel(model);
        for (int index = 0; index < entries.size(); index++)
            if (entries.get(index).snapshot().id().equals(selected))
                table.setRowSelectionInterval(index, index);
        showSelection();
    }

    /** Returns only a valid row from the current observation set. / 仅返回当前观察集合中的有效行。
     * @return selected task or null / 已选任务或空 */
    private Entry selected() {
        int row = table.getSelectedRow();
        return row >= 0 && row < entries.size() ? entries.get(row) : null;
    }

    /** Shows original identifiers and retained resource blockers without recovery payloads. / 显示原标识及保留资源阻塞，不显示恢复载荷。 */
    private void showSelection() {
        var entry = selected();
        details.setText(entry == null || locked
                ? ""
                : identity(entry.snapshot()) + "\n\n" + entry.snapshot().progressText() + "\n\n"
                        + messages.text("tasks.blockers") + "\n" + String.join("\n", entry.snapshot().blockers()));
        details.setCaretPosition(0);
        controls();
    }

    /** Formats exact original ownership for details and confirmation. / 为详情及确认格式化精确原归属。
     * @param task original task / 原任务
     * @return readable original identity / 可读原身份 */
    private String identity(TaskSnapshot task) {
        return task.id() + "\n" + task.ownerId() + " @ " + task.ownerVersion() + "\n" + task.type() + "\n"
                + messages.text("tasks.started") + ": " + task.startedAt();
    }

    /** Checks only displayed availability; the service revalidates before execution. / 仅检查显示的可用性，服务在执行前重新核验。
     * @param entry current observation / 当前观察
     * @return whether a recovery action can be offered / 是否可提供恢复操作 */
    private static boolean recoverable(Entry entry) {
        return Set.of(RecoveryState.RECONCILE, RecoveryState.ARCHIVE_ONLY, RecoveryState.CONFIRM_RESCUE)
                .contains(entry.recovery());
    }

    /** Keeps unavailable and stale actions disabled. / 禁用不可用及过期操作。 */
    private void controls() {
        boolean available = !locked && !closed && !busy && confirmation == null;
        var entry = selected();
        reload.setEnabled(available);
        history.setEnabled(available);
        table.setEnabled(available);
        cancel.setEnabled(available && entry != null && entry.cancellable());
        reconcile.setEnabled(available && entry != null && recoverable(entry));
        messages.bind(reconcile,
                entry != null && entry.recovery() == RecoveryState.ARCHIVE_ONLY ? "tasks.archive" : "tasks.reconcile");
    }

    /** Re-renders the same immutable task observations without fetching or reconciling tasks. / 重绘同一不可变任务观察，不重新读取或核对任务。 */
    public void localize() {
        if (!closed) {
            var selected = selected();
            display(entries, selected == null ? null : selected.snapshot().id());
        }
    }

    /** Disposes the exact confirmation without accepting its decision. / 销毁精确确认窗口，不接受其决定。 */
    private void dismissConfirmation() {
        if (confirmation != null) {
            confirmation.dispose();
            confirmation = null;
        }
    }

    /** Clears observations and invalidates pending UI callbacks without cancelling business. / 清空观察并使待处理界面回调失效，不取消业务。 */
    @Override
    public void close() {
        closed = true;
        epoch++;
        dismissConfirmation();
        display(java.util.List.of(), null);
        status.setText("");
    }

    /** Original action result with a refreshed inventory. / 原操作结果及已刷新清单。
     * @param snapshot actual original-task result / 实际原任务结果
     * @param entries current retained tasks / 当前保留任务 */
    private record ActionResult(TaskSnapshot snapshot, java.util.List<Entry> entries) {
    }
}
