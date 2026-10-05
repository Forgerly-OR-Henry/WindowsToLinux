package gold.debug.windowstolinux.mod.files.ui;

import java.awt.BorderLayout;
import java.util.List;
import java.util.Optional;
import javax.swing.*;

import gold.debug.windowstolinux.appapi.file.action.FileOperation;
import gold.debug.windowstolinux.appapi.file.model.FileInfo;
import gold.debug.windowstolinux.appapi.ui.page.ModPage;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.files.service.FilesService;

/** Owns one lazy file workspace and its symmetric optional second pane. / 持有一个按需文件工作区及对称可选第二栏。 */
public final class FilesWorkspace implements ModPage {
    /** Original publicly backed service. / 原公开底座服务。 */
    private final FilesService service;

    /** Sole author-owned root. / 唯一作者所属根。 */
    private final JPanel root = new Content();

    /** Width-tracking author scroll surface for large-font minimum windows. / 大字体最小窗口中跟随宽度的作者滚动界面。 */
    private final JScrollPane scroll = new JScrollPane(root, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) {
        /** Rejects inherited scroll shortcuts while locked or closed. / 锁定或关闭时拒绝继承的滚动快捷键。
         * @param stroke actual key stroke / 实际按键
         * @param event original key event / 原按键事件
         * @param condition original focus condition / 原焦点条件
         * @param pressed whether the key was pressed / 是否按下
         * @return whether an allowed binding consumed the key / 是否由允许的绑定消费按键 */
        @Override
        protected boolean processKeyBinding(KeyStroke stroke, java.awt.event.KeyEvent event, int condition, boolean pressed) {
            return !closed && environment != null && !environment.locked() && super.processKeyBinding(stroke, event, condition, pressed);
        }
    };

    /** Symmetric browser slot container. / 对称浏览栏容器。 */
    private final JPanel browsers = new JPanel(new java.awt.GridLayout(1, 1, 6, 0));

    /** Explicit right-pane toggle. / 明确右栏切换。 */
    private final JButton second = new JButton();

    /** Explicit original task cancellation. / 明确原任务取消。 */
    private final JButton cancel = new JButton();

    /** Platform original task-management entry. / 平台原任务管理入口。 */
    private final JButton taskPage = new JButton();

    /** Explicit verified link-only continuation. / 明确经核对的仅链接续作。 */
    private final JButton continuation = new JButton();

    /** Actual independent pane instances. / 实际独立栏实例。 */
    private FilesPane left, right, active;

    /** Current host lifecycle environment. / 当前宿主生命周期环境。 */
    private UiEnvironment environment;

    /** Shared live catalog. / 共享实时文案目录。 */
    private FilesMessages messages;

    /** Original callback evidence surface. / 原回调依据界面。 */
    private FilesFeedback feedback;

    /** Original task observer. / 原任务观测器。 */
    private FilesTasks tasks;

    /** Original shared actions. / 原共享动作。 */
    private FilesActions actions;

    /** Original service command wiring. / 原服务命令接线。 */
    private FilesCommands commands;

    /** Original unsubmitted input owner. / 原未提交输入所有者。 */
    private FilesDialogs dialogs;

    /** Shared-action popup instance. / 共享动作菜单实例。 */
    private JPopupMenu popup;

    /** Lifecycle and explicit expansion flags. / 生命周期及明确展开标记。 */
    private boolean created, closed, dual;

    /** Captures the actual service without opening a directory or submitting work. / 捕获实际服务，不打开目录及提交工作。
     * @param service original platform-backed files service / 原平台文件服务 */
    public FilesWorkspace(FilesService service) {
        this.service = java.util.Objects.requireNonNull(service);
    }

    /** Creates an initially empty single-pane author root on the EDT. / 在 EDT 创建初始为空的单栏作者根。
     * @param environment actual host environment / 实际宿主环境
     * @return original workspace root / 原工作区根 */
    @Override
    public JComponent create(UiEnvironment environment) {
        requireEdt();
        if (closed || created) throw new IllegalStateException("Files workspace already used");
        created = true;
        this.environment = environment;
        messages = new FilesMessages(environment.locale());
        feedback = new FilesFeedback(messages);
        dialogs = new FilesDialogs(root, messages);
        tasks = new FilesTasks(service, feedback, this::update, () -> commands.refreshAll());
        commands = new FilesCommands(this, service, tasks, dialogs, messages);
        actions = new FilesActions(this, messages);
        left = new FilesPane("left", messages, this);
        active = left;
        actions.bind(left.table());
        browsers.add(left);
        popup = actions.popup();
        second.setName("files.second");
        second.addActionListener(event -> toggle());
        cancel.setName("files.cancel");
        cancel.addActionListener(event -> {
            if (!closed && !environment.locked()) tasks.cancel();
        });
        taskPage.setName("files.tasks");
        taskPage.addActionListener(event -> {
            if (!closed && !environment.locked()) try {
                service.openTasks();
            } catch (Exception failure) {
                problem(failure);
            }
        });
        continuation.setName("files.continueLinks");
        continuation.addActionListener(event -> {
            if (available()) commands.continueLinks();
        });
        var heading = new JPanel(new BorderLayout(4, 4));
        heading.add(actions.toolbar(), BorderLayout.CENTER);
        heading.add(second, BorderLayout.EAST);
        var taskControls = new JPanel(new java.awt.GridLayout(1, 3, 4, 0));
        taskControls.add(cancel);
        taskControls.add(taskPage);
        taskControls.add(continuation);
        var status = new JPanel(new BorderLayout(4, 4));
        status.add(taskControls, BorderLayout.NORTH);
        status.add(feedback, BorderLayout.CENTER);
        root.setName("files.workspace");
        scroll.setName("files.scroll");
        scroll.setBorder(BorderFactory.createEmptyBorder());
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.add(heading, BorderLayout.NORTH);
        root.add(browsers, BorderLayout.CENTER);
        root.add(status, BorderLayout.SOUTH);
        tasks.pause(environment.locked());
        update();
        return scroll;
    }

    /** Opens or hides the same independent right pane, activating left when hidden. / 展开或收起相同独立右栏，收起时激活左栏。 */
    private void toggle() {
        if (!available()) return;
        dual = !dual;
        if (dual) {
            if (right == null) {
                right = new FilesPane("right", messages, this);
                actions.bind(right.table());
            }
            browsers.setLayout(new java.awt.GridLayout(1, 2, 6, 0));
            browsers.add(right);
        } else {
            browsers.remove(right);
            browsers.setLayout(new java.awt.GridLayout(1, 1));
            active = left;
        }
        update();
        browsers.revalidate();
        browsers.repaint();
    }

    /** Returns the actual current shared-action target. / 返回实际当前共享动作目标。
     * @return active original pane / 活动原栏 */
    FilesPane active() {
        return active;
    }

    /** Makes actual pointer or keyboard focus determine shared action context. / 由实际指针及键盘焦点确定共享动作上下文。
     * @param pane original focused pane / 原获得焦点栏 */
    void activate(FilesPane pane) {
        if (!closed && (pane == left || dual && pane == right)) {
            active = pane;
            update();
        }
    }

    /** Returns the original service command owner. / 返回原服务命令所有者。
     * @return original commands / 原命令 */
    FilesCommands commands() {
        return commands;
    }

    /** Returns both actual retained panes, including a hidden right pane. / 返回两个实际保留栏，包含隐藏右栏。
     * @return actual existing panes / 实际存在栏 */
    List<FilesPane> panes() {
        return right == null ? List.of(left) : List.of(left, right);
    }

    /** Returns only links already observed by this workspace. / 仅返回本工作区已经观测的链接。
     * @return immutable deduplicated observations / 不可变去重观测 */
    List<FileInfo> observedLinks() {
        var links = new java.util.LinkedHashMap<String, FileInfo>();
        for (var pane : panes())
            for (var info : pane.entries())
                if (info.kind() == FileInfo.Kind.LINK) links.putIfAbsent(info.requested().key(), info);
        return List.copyOf(links.values());
    }

    /** Returns lifecycle-aware command availability. / 返回符合生命周期的命令可用状态。
     * @return whether new business input is allowed / 是否允许新业务输入 */
    boolean available() {
        return created && !closed && !environment.locked() && !tasks.busy();
    }

    /** Returns internal clipboard presence only. / 仅返回内部剪贴存在状态。
     * @return clipboard presence / 剪贴存在 */
    boolean hasClipboard() {
        return commands.hasClipboard();
    }

    /** Returns explicit second-pane expansion. / 返回明确第二栏展开状态。
     * @return expanded / 已展开 */
    boolean dual() {
        return dual;
    }

    /** Returns the popup shared with all tool and keyboard behaviors. / 返回与全部工具及键盘行为共享的菜单。
     * @return original popup / 原菜单 */
    JPopupMenu popup() {
        return popup;
    }

    /** Returns actual original link-only continuation evidence. / 返回实际原仅链接续作依据。
     * @return eligible original operation / 可核对原操作 */
    Optional<FileOperation> continuation() {
        return feedback.continuation();
    }

    /** Displays an actual view-side problem. / 展示实际视图问题。
     * @param failure original failure / 原失败 */
    void problem(Throwable failure) {
        feedback.problem(failure);
        update();
    }

    /** Refreshes existing labels and availability without replacing any pane. / 刷新已有标签及可用状态，不替换任何栏。 */
    void update() {
        if (!created || closed) return;
        boolean ready = available();
        second.setText(messages.text(dual ? "hideSecond" : "showSecond"));
        second.setEnabled(ready);
        cancel.setText(messages.text("cancel"));
        cancel.setEnabled(!environment.locked());
        taskPage.setText(messages.text("tasks"));
        taskPage.setEnabled(!environment.locked());
        continuation.setText(messages.text("continueLinks"));
        continuation.setEnabled(ready && feedback.continuation().isPresent());
        actions.update();
        for (var pane : panes()) {
            pane.localize(messages, pane == active);
            pane.interactive(ready);
        }
        feedback.interactive(!environment.locked());
        interactiveScroll(!environment.locked());
    }

    /** Applies host appearance and lock changes while retaining location, selection and drafts. / 应用宿主外观及锁定变化，保留位置、选择及草稿。
     * @param environment actual host environment / 实际宿主环境 */
    @Override
    public void environmentChanged(UiEnvironment environment) {
        requireEdt();
        if (closed) return;
        boolean lockChanged = this.environment.locked() != environment.locked();
        this.environment = environment;
        messages.update(environment.locale());
        SwingUtilities.updateComponentTreeUI(scroll);
        dialogs.environmentChanged();
        if (lockChanged) {
            if (environment.locked()) {
                dialogs.revoke();
                popup.setVisible(false);
            }
            tasks.pause(environment.locked());
        }
        feedback.render();
        update();
    }

    /** Releases actual view observation and unsubmitted input, preserving accepted tasks. / 释放实际视图观测及未提交输入，保留已接受任务。
     * @throws Exception if original view worker cleanup remains pending / 原视图线程清理仍待完成时 */
    @Override
    public void close() throws Exception {
        requireEdt();
        closed = true;
        interactiveScroll(false);
        if (!created) return;
        dialogs.revoke();
        popup.setVisible(false);
        commands.clear();
        tasks.close();
        root.removeAll();
    }

    /** Gates the author page's wheel, scrollbar and focus interactions together. / 同时控制作者页面的滚轮、滚动条及焦点交互。
     * @param enabled whether the unlocked view permits scrolling / 未锁定视图是否允许滚动 */
    private void interactiveScroll(boolean enabled) {
        scroll.setEnabled(enabled);
        scroll.setWheelScrollingEnabled(enabled);
        scroll.getVerticalScrollBar().setEnabled(enabled);
        scroll.getHorizontalScrollBar().setEnabled(enabled);
    }

    /** Retains real preferred content height while tracking the enclosing viewport width. / 保留真实首选内容高度并跟随外层视口宽度。 */
    private static final class Content extends JPanel implements Scrollable {
        /** Builds the original author content layout. / 创建原作者内容布局。 */
        private Content() {
            super(new BorderLayout(6, 6));
        }

        /** Uses the actual content's preferred viewport size. / 使用实际内容的首选视口尺寸。
         * @return preferred content size / 首选内容尺寸 */
        @Override
        public java.awt.Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        /** Scrolls one current font line at a time. / 每次滚动当前字体的一行。
         * @param visible actual visible rectangle / 实际可见矩形
         * @param orientation requested axis / 请求方向
         * @param direction requested sign / 请求符号
         * @return current font line height / 当前字体行高 */
        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction) {
            return Math.max(1, getFontMetrics(getFont()).getHeight());
        }

        /** Leaves one font line of overlap during page scrolling. / 整页滚动保留一行字体重叠。
         * @param visible actual visible rectangle / 实际可见矩形
         * @param orientation requested axis / 请求方向
         * @param direction requested sign / 请求符号
         * @return visible height less one current line / 可见高度减去当前一行 */
        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction) {
            return Math.max(1, visible.height - getScrollableUnitIncrement(visible, orientation, direction));
        }

        /** Keeps both panes within the one actual viewport width. / 使两个栏保持在同一实际视口宽度内。
         * @return always true / 始终为真 */
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        /** Fills roomy windows without compressing preferred content in short windows. / 填满充裕窗口并避免在矮窗口压缩首选内容。
         * @return whether the viewport can contain the full preferred height / 视口是否能容纳完整首选高度 */
        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport viewport && viewport.getHeight() >= getPreferredSize().height;
        }
    }

    /** Keeps author-owned components inside their actual EDT lifecycle. / 将作者所属组件保持在其实际 EDT 生命周期内。 */
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Files workspace requires EDT");
    }
}
