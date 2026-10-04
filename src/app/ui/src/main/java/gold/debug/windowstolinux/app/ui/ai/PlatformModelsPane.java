package gold.debug.windowstolinux.app.ui.ai;

import java.awt.*;
import java.util.*;
import java.util.List;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.ai.PlatformModelService;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;

/** Permanent model inventory and explicit interrupted-probe recovery. / 常驻模型清单及明确的中断探测恢复。 */
public final class PlatformModelsPane extends JPanel implements AutoCloseable {
    /** Current view lifetime and exact request ownership. / 当前视图寿命及精确请求归属。 */
    private final ViewState view = new ViewState();

    /** Mutable state confined to the EDT. / 限于 EDT 的可变状态。 */
    private static final class ViewState {
        /** Rejects earlier view intents and busy owners. / 拒绝早期视图意图及忙碌状态所有者。 */
        private long epoch, request;

        /** Current privacy, lifetime and active request. / 当前隐私、寿命及活动请求。 */
        private boolean locked = true, closed, busy;
    }

    /** Opens the actual model editor with fixed isolated values and no service capability. / 以固定隔离值及空服务能力打开真实模型编辑器。
     * @param owner isolated gallery / 隔离目录
     * @param components current theme controls / 当前主题控件
     * @param messages current localized display / 当前本地化显示
     * @return capability-free actual editor / 不持有能力的真实编辑器 */
    public static javax.swing.JDialog preview(java.awt.Window owner, DesktopPartFactory components,
                                              PageMessagePresenter messages) {
        return PlatformModelDialog.preview(owner, components, messages);
    }

    /** Actual settings and task owner. / 实际设置及任务所有者。 */
    private final PlatformModelService service;

    /** Reused inventory and independent purpose editor. / 复用的清单及独立用途编辑器。 */
    private final AiModelListPane inventory;

    /** Current unfinished host checks. / 当前未结束宿主检查。 */
    private final JComboBox<TaskSnapshot> checks = new JComboBox<>();

    /** Non-secret operation feedback. / 非秘密操作反馈。 */
    private final JLabel status = new JLabel();

    /** Explicit cancellation and reconciliation actions. / 明确取消及核对操作。 */
    private final JButton cancel, reconcile;

    /** In-memory task observer. / 内存任务观察器。 */
    private final javax.swing.Timer timer;

    /** Mounts existing inventory controls without deployment analysis. / 挂载现有清单控件，不包含部署分析。
     * @param owner actual desktop / 实际桌面
     * @param service actual model settings / 实际模型设置
     * @param components themed controls / 主题控件
     * @param messages localized feedback / 本地化反馈 */
    public PlatformModelsPane(Window owner, PlatformModelService service, DesktopPartFactory components,
                              PageMessagePresenter messages) {
        super(new BorderLayout(10, 10));
        this.service = service;
        setName("platform.models");
        components.stylePage(this);
        setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        inventory = new AiModelListPane(service, components, messages, existing -> {
            if (view.locked || view.busy || view.closed)
                return;
            view.busy = true;
            long generation = view.epoch, identity = ++view.request;
            inventoryLock();
            DesktopTaskRunner.run(
                () -> existing == null ? OptionalInt.empty() : service.outputLimit(existing.profile().id()),
                limit -> {
                    if (!returned(generation, identity)) return;
                    inventoryLock();
                    if (!view.locked && !view.closed)
                        new PlatformModelDialog(owner, service, components, messages, existing, limit,
                            this::refresh).setVisible(true);
                }, failure -> {
                    if (!returned(generation, identity)) return;
                    inventoryLock();
                    status.setText(messages.safe(failure));
                });
        });
        add(inventory);
        var actions = components.transparent(new FlowLayout(FlowLayout.LEFT));
        checks.setName("models.checks");
        checks.setPreferredSize(new Dimension(400, 30));
        checks.setRenderer((list, value, index, selected,
                            focus) -> new JLabel(value == null ? "" : value.progressText() + " · " + value.state()));
        actions.add(checks);
        cancel = messages.bind(components.secondaryButton(""), "button.cancel");
        cancel.setName("models.cancel");
        reconcile = messages.bind(components.secondaryButton(""), "platform.models.reconcile");
        reconcile.setName("models.reconcile");
        installTaskActions(messages);
        var reload = messages.bind(components.secondaryButton(""), "server.refresh");
        reload.setName("models.refresh");
        reload.addActionListener(event -> refresh());
        actions.add(cancel);
        actions.add(reconcile);
        actions.add(reload);
        var footer = components.card(new BorderLayout());
        footer.add(actions);
        footer.add(status, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);
        timer = new javax.swing.Timer(500, event -> observe());
        timer.start();
    }

    /** Binds cancellation and reconciliation to the current view generation. / 将取消及核对绑定到当前视图代次。
     * @param messages localized safe feedback / 本地化安全反馈 */
    private void installTaskActions(PageMessagePresenter messages) {
        cancel.addActionListener(event -> {
            var selected = (TaskSnapshot) checks.getSelectedItem();
            if (selected != null && !view.locked && !view.closed) {
                long generation = view.epoch;
                DesktopTaskRunner.run(() -> {
                        service.cancel(selected.id());
                        return true;
                    }, ignored -> {
                        if (!view.closed && !view.locked && generation == view.epoch) observe();
                    },
                    failure -> {
                        if (!view.closed && !view.locked && generation == view.epoch)
                            status.setText(messages.safe(failure));
                    });
            }
        });
        reconcile.addActionListener(event -> {
            var selected = (TaskSnapshot) checks.getSelectedItem();
            if (selected != null && !view.locked && !view.busy) {
                view.busy = true;
                long generation = view.epoch, identity = ++view.request;
                inventoryLock();
                DesktopTaskRunner.run(() -> service.reconcile(selected.id()),
                    stage -> stage.whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
                        if (!returned(generation, identity)) return;
                        inventoryLock();
                        status.setText(failure == null
                            ? messages.text("platform.models.reconciled")
                            : messages.text("platform.models.failed"));
                        refresh();
                        observe();
                    })), failure -> {
                        if (!returned(generation, identity)) return;
                        inventoryLock();
                        status.setText(messages.safe(failure));
                    });
            }
        });
    }

    /** Applies the actual desktop privacy boundary. / 应用实际桌面隐私边界。
     * @param value current lock state / 当前锁定状态 */
    public void setLocked(boolean value) {
        if (view.locked != value) {
            view.epoch++;
            view.request++;
            view.busy = false;
        }
        view.locked = value;
        inventoryLock();
        if (!value)
            refresh();
        observe();
    }

    /** Disables conflicting inventory edits. / 禁止冲突的清单编辑。 */
    private void inventoryLock() {
        inventory.setLocked(view.locked || view.busy || view.closed);
    }

    /** Releases only the newest request's view state. / 仅释放最新请求的视图状态。
     * @param generation captured view generation / 捕获的视图代次
     * @param identity exact request identity / 精确请求身份
     * @return whether the completion still belongs to this view / 完成通知是否仍属于此视图 */
    private boolean returned(long generation, long identity) {
        if (view.closed || view.locked || view.epoch != generation || view.request != identity) return false;
        view.busy = false;
        return true;
    }

    /** Updates existing inventory labels without reading or modifying model configuration. / 更新已有清单标签，不读取或修改模型配置。 */
    public void localize() {
        inventory.localize();
    }

    /** Reloads non-secret settings through the background inventory loader. / 经后台清单加载器重读非秘密设置。 */
    private void refresh() {
        if (!view.locked && !view.closed && !view.busy)
            inventory.refresh();
    }

    /** Refreshes only in-memory task state on the EDT. / 在 EDT 仅刷新内存任务状态。 */
    private void observe() {
        if (view.closed)
            return;
        var selected = (TaskSnapshot) checks.getSelectedItem();
        var rows = view.locked ? List.<TaskSnapshot>of() : service.checks();
        checks.removeAllItems();
        rows.forEach(checks::addItem);
        if (selected != null)
            rows.stream().filter(task -> task.id().equals(selected.id())).findFirst()
                .ifPresent(checks::setSelectedItem);
        var current = (TaskSnapshot) checks.getSelectedItem();
        cancel.setEnabled(!view.locked && current != null && current.state() != TaskState.UNKNOWN);
        reconcile.setEnabled(!view.locked && !view.busy && current != null
            && Set.of(TaskState.UNKNOWN, TaskState.FINISHING).contains(current.state()));
    }

    /** Stops view observation without discarding accepted central tasks. / 停止视图观察，不丢弃已接纳中央任务。 */
    @Override
    public void close() {
        view.closed = true;
        view.epoch++;
        view.request++;
        view.busy = false;
        timer.stop();
        inventory.close();
        checks.removeAllItems();
        status.setText("");
    }
}
