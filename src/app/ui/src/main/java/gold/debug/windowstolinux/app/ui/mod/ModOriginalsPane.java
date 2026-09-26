package gold.debug.windowstolinux.app.ui.mod;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import javax.swing.*;
import javax.swing.event.*;

import gold.debug.windowstolinux.app.service.mod.PlatformModInstallation;
import gold.debug.windowstolinux.app.service.mod.PlatformModInstallation.*;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.appapi.task.OperationResult;

/** Manages explicitly discovered originals and their original interrupted installations. / 管理明确发现的原包及其原中断安装。 */
public final class ModOriginalsPane extends JPanel implements AutoCloseable {
    /** Actual serialized installation owner. / 实际串行安装所有者。 */
    private final PlatformModInstallation service;

    /** Current localized feedback. / 当前本地化反馈。 */
    private final PageMessagePresenter messages;

    /** Refreshes the existing installed view after a possible mutation. / 可能修改后刷新已有已安装视图。 */
    private final Runnable installedChanged;

    /** All last scanned files, including invalid and installed originals. / 包含无效及已安装原包的全部上次扫描文件。 */
    private List<OriginalView> all = List.of();

    /** Filtered file observations. / 已筛选文件观察。 */
    private final DefaultListModel<OriginalView> rows = new DefaultListModel<>();

    /** Explicit single original selection. / 明确单个原包选择。 */
    private final JList<OriginalView> inventory = new JList<>(rows);

    /** Filename, declared name, identity or release search. / 文件名、声明名称、身份或发布搜索。 */
    private final JTextField search = new JTextField();

    /** Shared original retention preference. / 共用原包留存偏好。 */
    private final JCheckBox keep = new JCheckBox();

    /** Plain-text file facts and rejection reason. / 纯文本文件事实及拒绝原因。 */
    private final JTextArea details = new JTextArea();

    /** Original interrupted operations, independently of file presence. / 独立于文件是否存在的原中断操作。 */
    private final JComboBox<PendingView> pending = new JComboBox<>();

    /** Original operation state and readable failure cause. / 原操作状态及可读失败原因。 */
    private final JTextArea operationDetails = new JTextArea(2, 40);

    /** Explicit original-file and operation actions. / 明确原文件及操作动作。 */
    private final JButton refresh, install, delete, reconcile, logs;

    /** Persistent operation feedback across a read-only refresh. / 跨只读刷新的操作反馈。 */
    private final JLabel status = new JLabel();

    /** Visible background progress. / 可见后台进度。 */
    private final JProgressBar progress = new JProgressBar();

    /** Modal confirmation owned by this view and the lock boundary. / 此视图及锁定边界所属的模态确认。 */
    private JDialog confirmation;

    /** Current privacy, lifetime and worker ownership. / 当前隐私、寿命及工作线程归属。 */
    private boolean locked = true, closed, busy;

    /** Whether the most recent requested scan completed successfully. / 最近一次请求的扫描是否成功完成。 */
    private boolean current;

    /** Invalidates review results across lock transitions. / 使跨锁定变化的审阅结果失效。 */
    private long epoch;

    /** Builds an original-package view on the event thread. / 在事件线程构造原包视图。
     * @param service actual original package service / 实际原包服务
     * @param components themed controls / 主题控件
     * @param messages localized feedback / 本地化反馈
     * @param installedChanged refreshes registered versions / 刷新登记版本
     * @param showLogs opens actual platform logs / 打开实际平台日志 */
    public ModOriginalsPane(PlatformModInstallation service, DesktopComponentFactory components,
            PageMessagePresenter messages, Runnable installedChanged, Runnable showLogs) {
        super(new BorderLayout(10, 10));
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Original management requires the EDT");
        this.service = Objects.requireNonNull(service);
        this.messages = messages;
        this.installedChanged = installedChanged;
        setName("platform.originals");
        setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));
        var top = new JPanel(new BorderLayout(8, 8));
        var location = new JTextArea(
                messages.text("originals.location", Map.of("path", service.originalsDirectory().toString())));
        location.setName("originals.location");
        location.setEditable(false);
        location.setLineWrap(true);
        location.setWrapStyleWord(true);
        location.setOpaque(false);
        top.add(location, BorderLayout.NORTH);
        search.setName("originals.search");
        search.setToolTipText(messages.text("originals.search"));
        var filter = new JPanel(new BorderLayout(8, 0));
        filter.add(new JLabel(messages.text("originals.search")), BorderLayout.WEST);
        filter.add(search);
        top.add(filter, BorderLayout.CENTER);
        keep.setName("originals.keep");
        keep.setText(messages.text("originals.keep"));
        keep.addActionListener(event -> saveRetention());
        top.add(keep, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        inventory.setName("originals.inventory");
        inventory.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        inventory.setCellRenderer((list, value, index, selected, focused) -> {
            var renderer = new DefaultListCellRenderer();
            renderer.putClientProperty("html.disable", true);
            return renderer.getListCellRendererComponent(list,
                    value.file().getFileName() + " · " + state(value.state()), index, selected, focused);
        });
        inventory.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting())
                render();
        });
        search.getDocument().addDocumentListener(new DocumentListener() {
            /** Updates a pure in-memory filter. / 更新纯内存筛选。
             * @param event changed text / 变化文本 */
            public void insertUpdate(DocumentEvent event) {
                filter();
            }

            /** Updates a pure in-memory filter. / 更新纯内存筛选。
             * @param event changed text / 变化文本 */
            public void removeUpdate(DocumentEvent event) {
                filter();
            }

            /** Updates a pure in-memory filter. / 更新纯内存筛选。
             * @param event changed text / 变化文本 */
            public void changedUpdate(DocumentEvent event) {
                filter();
            }
        });
        details.setName("originals.details");
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(inventory), new JScrollPane(details));
        split.setResizeWeight(0.4);
        split.setDividerLocation(320);
        add(split);
        refresh = button(components, "refresh", this::refresh);
        install = button(components, "install", this::reviewInstall);
        delete = button(components, "delete", this::reviewDelete);
        reconcile = button(components, "reconcile", this::reconcile);
        logs = button(components, "logs", showLogs);
        pending.setName("originals.pending");
        pending.setRenderer((list, value, index, selected, focused) -> {
            var renderer = new DefaultListCellRenderer();
            renderer.putClientProperty("html.disable", true);
            return renderer.getListCellRendererComponent(list, value == null
                    ? ""
                    : value.owner() + " · " + value.version() + " · " + state(value.phase()) + " · " + value.id(),
                    index, selected, focused);
        });
        pending.addActionListener(event -> renderPending());
        var retained = new JPanel(new BorderLayout(8, 0));
        retained.setBorder(BorderFactory.createTitledBorder(messages.text("originals.pending")));
        retained.add(pending);
        retained.add(reconcile, BorderLayout.EAST);
        operationDetails.setName("originals.operationDetails");
        operationDetails.setEditable(false);
        operationDetails.setLineWrap(true);
        operationDetails.setWrapStyleWord(true);
        operationDetails.setOpaque(false);
        retained.add(operationDetails, BorderLayout.SOUTH);
        var actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        for (var button : List.of(refresh, install, delete, logs))
            actions.add(button);
        var footer = new JPanel();
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        for (var part : List.of(retained, actions, progress, status)) {
            part.setAlignmentX(Component.LEFT_ALIGNMENT);
            footer.add(part);
        }
        progress.setName("originals.progress");
        progress.setIndeterminate(true);
        status.setName("originals.status");
        status.putClientProperty("html.disable", true);
        add(footer, BorderLayout.SOUTH);
        controls();
    }

    /** Creates one user-triggered named action. / 创建一个用户触发的命名动作。
     * @param components themed controls / 主题控件
     * @param key action key / 动作键
     * @param action explicit user action / 明确用户动作
     * @return named control / 命名控件 */
    private JButton button(DesktopComponentFactory components, String key, Runnable action) {
        var button = components.secondaryButton(messages.text("originals." + key));
        button.setName("originals." + key);
        button.addActionListener(event -> {
            if (!locked && !closed && !busy)
                action.run();
        });
        return button;
    }

    /** Clears private observations and invalidates confirmations on every lock transition. / 每次锁定变化均清空私有观察并使确认失效。
     * @param value current lock state / 当前锁定状态 */
    public void setLocked(boolean value) {
        locked = value;
        epoch++;
        current = false;
        if (confirmation != null)
            confirmation.dispose();
        all = List.of();
        rows.clear();
        pending.removeAllItems();
        details.setText("");
        status.setText("");
        search.setText("");
        keep.setSelected(false);
        controls();
        if (!locked)
            refresh();
    }

    /** Scans on startup, explicit entry or refresh; never starts installation. / 启动、明确进入或刷新时扫描，绝不启动安装。 */
    public void refresh() {
        if (locked || closed || busy)
            return;
        var selected = inventory.getSelectedValue();
        var operation = (PendingView) pending.getSelectedItem();
        current = false;
        run(service::scan, snapshot -> {
            current = true;
            all = snapshot.originals();
            keep.setSelected(snapshot.keepOriginal());
            pending.removeAllItems();
            for (var item : snapshot.pending()) {
                pending.addItem(item);
                if (operation != null && item.id().equals(operation.id()))
                    pending.setSelectedItem(item);
            }
            filter();
            if (selected != null)
                for (int i = 0; i < rows.size(); i++)
                    if (rows.get(i).file().equals(selected.file()))
                        inventory.setSelectedIndex(i);
            controls();
        }, false);
    }

    /** Applies filename and author metadata filtering without filesystem effects. / 不产生文件系统效果地按文件名及作者元数据筛选。 */
    private void filter() {
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        rows.clear();
        for (var value : all) {
            String text = value.file().getFileName()
                    + value.manifest().map(m -> " " + m.name() + " " + m.id() + " " + m.version()).orElse("");
            if (text.toLowerCase(Locale.ROOT).contains(query))
                rows.addElement(value);
        }
        if (!rows.isEmpty())
            inventory.setSelectedIndex(0);
        render();
    }

    /** Displays plain-text source, declaration and independently read installation state. / 以纯文本展示来源、声明及独立读取的安装状态。 */
    private void render() {
        var value = inventory.getSelectedValue();
        if (value == null)
            details.setText(messages.text(all.isEmpty() ? "originals.empty" : "originals.noMatch"));
        else {
            var text = new StringBuilder(value.file().toString()).append("\n\n")
                    .append(messages.text("originals.local"));
            value.manifest().ifPresent(m -> text.append("\n\n").append(m.name()).append("\n").append(m.id())
                    .append("\n").append(m.version()));
            text.append("\n\n").append(state(value.state()));
            value.problem().ifPresent(
                    p -> text.append("\n").append(messages.text("originals.invalid")).append("\n").append(p.message()));
            details.setText(text.toString());
        }
        details.setCaretPosition(0);
        controls();
    }

    /** Displays retained failure facts even when no original can be selected. / 即使无法选择原包也显示保留失败事实。 */
    private void renderPending() {
        var value = (PendingView) pending.getSelectedItem();
        operationDetails.setText(value == null
                ? ""
                : state(value.state()) + " · " + state(value.phase())
                        + value.failure().map(reason -> "\n" + reason).orElse(""));
        operationDetails.setVisible(value != null);
        operationDetails.setCaretPosition(0);
        controls();
    }

    /** Reflects actual view, worker and observation boundaries without granting authority. / 反映实际视图、工作线程及观察边界，不授予权限。 */
    private void controls() {
        boolean available = !closed && !locked && !busy;
        var selected = inventory.getSelectedValue();
        var operation = (PendingView) pending.getSelectedItem();
        inventory.setEnabled(available);
        search.setEnabled(available);
        keep.setEnabled(available && current);
        pending.setEnabled(available);
        progress.setVisible(busy && !locked && !closed);
        if (refresh == null)
            return;
        refresh.setEnabled(available);
        logs.setEnabled(available);
        install.setEnabled(available && current && selected != null && selected.state().equals("AVAILABLE"));
        delete.setEnabled(available && current && selected != null && selected.deletable());
        reconcile.setEnabled(available && current && operation != null && operation.type().equals("INSTALL")
                && operation.state().equals("UNKNOWN"));
    }

    /** Persists only the selected preference and reloads uncertain saves without replay. / 仅持久选定偏好，保存不确定时重读而非重放。 */
    private void saveRetention() {
        boolean value = keep.isSelected();
        run(() -> {
            service.keepOriginal(value);
            return value;
        }, result -> {
            status.setText(messages.text("originals.preferenceSaved"));
            refresh();
        }, true);
    }

    /** Reviews original bytes before showing the exact installation effects. / 展示精确安装效果之前审阅原字节。 */
    private void reviewInstall() {
        var file = inventory.getSelectedValue().file();
        run(() -> service.review(file), review -> {
            var manifest = review.inspection().manifest();
            String text = messages.text("originals.confirmInstall",
                    Map.of("path", file.toString(), "name", manifest.name(), "owner", manifest.id(), "version",
                            manifest.version().toString(), "retention",
                            messages.text(review.keepOriginal() ? "originals.keep" : "originals.removeAfter")));
            if (confirm(text))
                run(() -> service.install(review), this::outcome, true);
        }, true);
    }

    /** Reviews removal of the original archive only, including invalid packages. / 审阅仅移除原归档，包括无效包。 */
    private void reviewDelete() {
        var file = inventory.getSelectedValue().file();
        run(() -> service.reviewDelete(file), review -> {
            if (confirm(messages.text("originals.confirmDelete", Map.of("path", review.file().toString()))))
                run(() -> {
                    service.deleteOriginal(review);
                    return true;
                }, removed -> {
                    status.setText(messages.text("originals.deleted"));
                    refresh();
                }, true);
        }, true);
    }

    /** Reconciles the selected original operation independently of current file selection. / 独立于当前文件选择核对选定原操作。 */
    private void reconcile() {
        var operation = (PendingView) pending.getSelectedItem();
        if (confirm(messages.text("originals.confirmReconcile", Map.of("owner", operation.owner(), "version",
                operation.version(), "id", operation.id().toString(), "phase", state(operation.phase())))))
            run(() -> service.reconcile(operation.id()), this::outcome, true);
    }

    /** Reports actual installation and original cleanup separately. / 分别报告实际安装及原包清理。
     * @param result original operation result / 原操作结果 */
    private void outcome(OperationResult<Receipt> result) {
        if (result.outcome() == gold.debug.windowstolinux.appapi.task.Outcome.SUCCEEDED) {
            var receipt = result.value().orElseThrow();
            status.setText(messages.text("originals.installed") + " "
                    + messages.text("originals.cleanup." + receipt.originalCleanup()));
        } else
            status.setText(
                    messages.text("originals.unconfirmed") + " " + result.problem().map(messages::safe).orElse(""));
        changed();
    }

    /** Refreshes both file and installed observations after a possible mutation. / 可能修改后刷新文件及已安装观察。 */
    private void changed() {
        installedChanged.run();
        refresh();
    }

    /** Opens a plain-text owned confirmation that cannot survive lock transitions. / 打开不能跨锁定变化存续的纯文本从属确认。
     * @param text exact reviewed effects / 精确审阅效果
     * @return confirmation within the same privacy epoch / 同一隐私代次内的确认 */
    private boolean confirm(String text) {
        long original = epoch;
        var area = new JTextArea(text, 12, 62);
        area.setName("originals.confirmation");
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        var option = new JOptionPane(new JScrollPane(area), JOptionPane.WARNING_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        confirmation = option.createDialog(this, messages.text("originals.confirm"));
        try {
            confirmation.setVisible(true);
            return Objects.equals(option.getValue(), JOptionPane.OK_OPTION) && !closed && !locked && original == epoch;
        } finally {
            confirmation.dispose();
            confirmation = null;
        }
    }

    /** Localizes known states and retains unknown platform codes as plain text. / 本地化已知状态，并以纯文本保留未知平台代码。
     * @param value platform state / 平台状态
     * @return current localized state / 当前本地化状态 */
    private String state(String value) {
        try {
            return messages.text("originals.state." + value);
        } catch (IllegalArgumentException unavailable) {
            if (unavailable.getCause() instanceof MissingResourceException)
                return value;
            throw unavailable;
        }
    }

    /** Runs one background action; stale callbacks only trigger a fresh read. / 运行一个后台动作，过期回调仅触发重新读取。
     * @param <T> result type / 结果类型
     * @param action original host action / 原宿主动作
     * @param success current-view result / 当前视图结果
     * @param mutation whether failure may leave durable changes / 失败是否可能留下持久变化 */
    private <T> void run(Callable<T> action, Consumer<T> success, boolean mutation) {
        if (locked || closed || busy)
            return;
        busy = true;
        long original = epoch;
        controls();
        DesktopTaskExecutor.run(action, value -> {
            busy = false;
            controls();
            if (!closed && !locked) {
                if (original == epoch)
                    success.accept(value);
                else
                    refresh();
            }
        }, failure -> {
            busy = false;
            controls();
            if (!closed && !locked) {
                status.setText(messages.safe(failure));
                if (original != epoch || mutation)
                    refresh();
            }
        });
    }

    /** Disposes private view state without cancelling runtime-owned accepted work. / 销毁私有视图状态，不取消运行时所属的已接纳工作。 */
    @Override
    public void close() {
        closed = true;
        setLocked(true);
    }
}
