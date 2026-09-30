package gold.debug.windowstolinux.app.ui.setting;

import java.awt.*;
import java.util.*;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;

import gold.debug.windowstolinux.app.service.log.PlatformLogService;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.diagnostic.PageMessagePresenter;

/** Host-only diagnostic inventory, bounded record view and explicit history cleanup. / 仅宿主的诊断清单、有界记录视图及明确历史清理。 */
public final class LogSettingsPane extends JPanel implements AutoCloseable {
    /** Actual storage and category coordinator. / 实际存储及类别协调器。 */
    private final PlatformLogService service;

    /** Localized feedback. / 本地化反馈。 */
    private final PageMessagePresenter messages;

    /** Explicit background actions. / 明确后台操作。 */
    private final JButton reload, clean, inspect;

    /** Transient diagnostic-level selection. / 临时诊断级别选择。 */
    private final JCheckBox debug;

    /** Actual category counts without guessed zero values. / 不猜测零值的实际类别计数。 */
    private final JTable table = new JTable();

    /** Already validated redacted record contents. / 已校验脱敏的记录内容。 */
    private final JTextArea records = new JTextArea();

    /** Actual completion, partial-failure or storage status. / 实际完成、部分失败或存储状态。 */
    private final JLabel status = new JLabel();

    /** Last actual category observations. / 最近实际类别观察。 */
    private java.util.List<PlatformLogService.Maintenance> categories = java.util.List.of();

    /** Privacy, actual background operation and view lifetime. / 隐私、实际后台操作及视图寿命。 */
    private boolean locked = true, busy, closed, restoring;

    /** Builds native controls without blocking the EDT on SQL or files. / 构造原生控件，不让 SQL 或文件阻塞 EDT。
     * @param service actual log owner / 实际日志所有者
     * @param components themed controls / 主题控件
     * @param messages localized text / 本地化文字 */
    public LogSettingsPane(PlatformLogService service, DesktopComponentFactory components,
            PageMessagePresenter messages) {
        super(new BorderLayout(10, 10));
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Log controls require the EDT");
        this.service = Objects.requireNonNull(service);
        this.messages = Objects.requireNonNull(messages);
        setName("platform.logs");
        setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        reload = messages.bind(components.secondaryButton(""), "logs.reload");
        reload.setName("logs.reload");
        clean = messages.bind(components.secondaryButton(""), "logs.clean");
        clean.setName("logs.clean");
        inspect = messages.bind(components.secondaryButton(""), "logs.inspect");
        inspect.setName("logs.inspect");
        debug = new JCheckBox(messages.text("logs.debug"), service.debug());
        messages.bind(debug, "logs.debug");
        debug.setName("logs.debug");
        reload.addActionListener(event -> load(false));
        clean.addActionListener(event -> load(true));
        inspect.addActionListener(event -> inspect());
        debug.addActionListener(event -> changeDebug());
        var actions = components.transparent(new FlowLayout(FlowLayout.LEFT));
        actions.add(reload);
        actions.add(clean);
        actions.add(inspect);
        actions.add(debug);
        var heading = components.transparent(new BorderLayout(8, 8));
        heading.add(messages.bind(new JLabel(), "logs.explanation"), BorderLayout.NORTH);
        heading.add(actions, BorderLayout.CENTER);
        status.setName("logs.status");
        heading.add(status, BorderLayout.SOUTH);
        add(heading, BorderLayout.NORTH);
        table.setName("logs.categories");
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(event -> controls());
        records.setName("logs.records");
        records.setEditable(false);
        records.setLineWrap(true);
        records.setWrapStyleWord(true);
        var split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(records));
        split.setResizeWeight(0.45);
        add(split, BorderLayout.CENTER);
        display();
        controls();
    }

    /** Applies privacy and refreshes confirmed storage only after unlocking. / 应用隐私，仅解锁后刷新已确认存储。
     * @param value actual lock state / 实际锁定状态 */
    public void setLocked(boolean value) {
        locked = value;
        if (value)
            records.setText("");
        controls();
        if (!value)
            load(false);
    }

    /** Runs explicit replay/cleanup with one operation at a time. / 每次一个操作地执行明确回填或清理。
     * @param cleanup whether the user requested history removal / 用户是否请求删除历史 */
    private void load(boolean cleanup) {
        if (locked || busy || closed)
            return;
        busy = true;
        controls();
        records.setText("");
        status.setText(messages.text("logs.working"));
        DesktopTaskExecutor.run(() -> service.inventory(cleanup), result -> {
            busy = false;
            if (closed)
                return;
            categories = result.categories();
            display();
            long failed = categories.stream().filter(item -> item.problem().isPresent()).count();
            status.setText(
                    messages.text(failed == 0 && result.unregistered().isEmpty() ? "logs.complete" : "logs.partial"));
            if (!locked && !result.unregistered().isEmpty())
                records.setText(messages.text("logs.unregistered") + "\n" + String.join("\n", result.unregistered()));
            controls();
        }, this::failed);
    }

    /** Displays actual confirmed counts, retaining unknown cells explicitly. / 显示实际已确认计数，明确保留未知单元格。 */
    private void display() {
        var model = new DefaultTableModel(
                new String[]{messages.text("logs.owner"), messages.text("logs.schema"), messages.text("logs.category"),
                        messages.text("logs.database"), messages.text("logs.databaseRemoved"),
                        messages.text("logs.files"), messages.text("logs.filesRemoved"), messages.text("logs.result")},
                0) {
            /** Prevents edits to observed storage facts. / 禁止编辑已观察存储事实。
             * @param row observed row / 观察行
             * @param column observed column / 观察列
             * @return always false / 始终为假 */
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        for (var item : categories)
            model.addRow(new Object[]{item.target().owner(), item.target().logical(), categoryName(item.target().logical(), item.target().category(), item.target().displayName()),
                    count(item.databaseAfter()), count(item.databaseRemoved()), count(item.fileAfter()),
                    count(item.fileRemoved()),
                    messages.text(item.problem().isEmpty() ? "logs.verified" : "logs.unconfirmed")});
        table.setModel(model);
    }

    /** Displays localized platform categories or the author's registered label. / 显示本地化平台类别或作者登记标签。
     * @param logical original logical owner / 原逻辑归属
     * @param category original category identifier / 原类别标识
     * @param displayName author's registered category label / 作者登记的类别标签
     * @return visible category name / 可见类别名称 */
    private String categoryName(String logical, String category, String displayName) {
        return logical.equals("app") ? messages.text("logs.category." + category) : displayName;
    }

    /** Formats absence as unknown rather than zero. / 将缺失格式化为未知而不是零。
     * @param count confirmed count / 已确认计数
     * @return visible count / 可见计数 */
    private String count(Optional<Long> count) {
        return count.map(Object::toString).orElse(messages.text("logs.unknown"));
    }

    /** Reads only the selected original registration on a worker. / 仅在工作线程读取选定原登记。 */
    private void inspect() {
        int row = table.getSelectedRow();
        if (locked || busy || closed || row < 0)
            return;
        var target = categories.get(row).target();
        busy = true;
        controls();
        status.setText(messages.text("logs.working"));
        DesktopTaskExecutor.run(() -> service.inspect(target.key()), view -> {
            busy = false;
            if (closed)
                return;
            if (!locked) {
                var text = new StringBuilder(view.target().owner() + " / " + view.target().logical() + " / "
                        + view.target().category() + "\n\n");
                for (var record : view.records()) {
                    text.append(java.time.Instant.ofEpochMilli(record.recordedAt())).append("  ").append(record.level())
                            .append("  ").append(record.eventCode()).append("  ").append(record.id()).append('\n')
                            .append(record.message()).append('\n');
                    if (record.details() != null)
                        text.append(record.details()).append('\n');
                    if (record.truncated())
                        text.append(messages.text("logs.truncated")).append('\n');
                    text.append('\n');
                }
                records.setText(text.toString());
                records.setCaretPosition(0);
            }
            status.setText(messages.text(view.problem().isEmpty() ? "logs.complete" : "logs.partial"));
            controls();
        }, this::failed);
    }

    /** Updates only this boot's DEBUG setting with failure rollback. / 仅更新此启动的 DEBUG 设置，失败时回滚。 */
    private void changeDebug() {
        if (restoring || locked || busy || closed)
            return;
        boolean requested = debug.isSelected();
        busy = true;
        controls();
        DesktopTaskExecutor.run(() -> {
            service.debug(requested);
            return service.debug();
        }, value -> {
            busy = false;
            if (closed)
                return;
            restoreDebug();
            controls();
        }, this::failed);
    }

    /** Restores the actual transient setting without another action. / 恢复实际临时设置，不触发另一操作。 */
    private void restoreDebug() {
        restoring = true;
        try {
            debug.setSelected(service.debug());
        } finally {
            restoring = false;
        }
    }

    /** Retains prior observations when a background operation is unconfirmed. / 后台操作未确认时保留先前观察。
     * @param failure actual operation failure / 实际操作失败 */
    private void failed(Exception failure) {
        busy = false;
        if (closed)
            return;
        restoreDebug();
        status.setText(messages.text("logs.failed") + " " + messages.safe(failure));
        controls();
    }

    /** Prevents overlapping cleanup, reads and privacy-sensitive interaction. / 防止重叠清理、读取及隐私相关交互。 */
    private void controls() {
        boolean available = !locked && !busy && !closed;
        reload.setEnabled(available);
        clean.setEnabled(available);
        debug.setEnabled(available);
        table.setEnabled(available);
        inspect.setEnabled(available && table.getSelectedRow() >= 0);
    }

    /** Detaches the view without abandoning accepted storage operations. / 分离视图，不丢弃已接纳存储操作。 */
    @Override
    public void close() {
        closed = true;
        controls();
        records.setText("");
    }
}
