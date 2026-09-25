package gold.debug.windowstolinux.app.ui.shell;

import java.awt.*;
import java.awt.event.*;
import java.util.*;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.contract.security.SecurityApplicationFacade;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;

/** Covers the shell and every owned dialog with the same security boundary. / 统一遮挡桌面外壳和所有从属窗口。 */
final class DesktopLockController implements AutoCloseable {
    /** Desktop frame owning the lock boundary. / 持有锁定边界的桌面主窗口。
     */
    /** Attaches the security surface and lock action to a compatible desktop. / 为支持安全契约的桌面接入锁定界面及动作。
     * @param frame owning desktop / 所属桌面
     * @param service application facade / 应用门面
     * @param messages localized catalog / 本地化目录
     * @param reports safe failure reports / 安全失败报告
     * @param sidebar navigation panel / 导航面板
     * @param components themed controls / 主题控件
     * @param initialWindow whether this is the initial window / 是否为首次打开的窗口
     * @param openSettings opens the settings page / 打开设置页
     * @return attached controller or null for nonsecurity preview facades / 已接入控制器，非安全预览门面为空
     */
    static DesktopLockController attach(JFrame frame, Object service,
            gold.debug.windowstolinux.app.ui.i18n.MessageCatalog messages,
            gold.debug.windowstolinux.app.ui.diagnostic.FailureReportStore reports, JPanel sidebar,
            gold.debug.windowstolinux.app.ui.component.DesktopComponentFactory components, boolean initialWindow,
            Runnable openSettings) {
        if (!(service instanceof SecurityApplicationFacade security))
            return null;
        var controller = new DesktopLockController(frame, security, new PageMessagePresenter(messages, reports));
        JButton lock = components.secondaryButton(messages.text("security.lock"));
        controller.lockButton = lock;
        lock.setEnabled(security.securityState().configured());
        lock.setName("security.lock");
        lock.addActionListener(event -> controller.lock());
        sidebar.add(lock);
        if (initialWindow)
            frame.addWindowListener(new WindowAdapter() {
                /** Prompts after the main window becomes visible. / 在主窗口可见后显示设置提示。
                 * @param event window-open event / 窗口打开事件
                 */
                @Override
                public void windowOpened(WindowEvent event) {
                    SwingUtilities.invokeLater(() -> controller.promptSetup(openSettings));
                }
            });
        return controller;
    }

    /** Desktop frame owning the lock boundary. / 持有锁定边界的桌面主窗口。
     */
    private final JFrame frame;

    /** Service-owned interactive authorization boundary. / 服务持有的交互授权边界。
     */
    private final SecurityApplicationFacade security;

    /** Localized safe message presenter. / 本地化安全消息呈现器。
     */
    private final PageMessagePresenter messages;

    /** Installed covers indexed by owned root pane. / 按从属根面板索引的已安装遮罩。
     */
    private final Map<RootPaneContainer, Cover> covers = new IdentityHashMap<>();

    /** Event-thread refresh timer. / 事件线程刷新定时器。
     */
    private final javax.swing.Timer timer;

    /** Keyboard gate for locked windows. / 锁定窗口的键盘门禁。
     */
    private final KeyEventDispatcher keys;

    /** Listener covering newly opened owned windows. / 遮挡新从属窗口的监听器。
     */
    private final AWTEventListener windows;

    /** Guarded application shutdown callback. / 受保护的应用关闭回调。
     */
    private Runnable exit;

    /** Manual lock control, available after setup. / 完成设置后可用的手动锁定控件。
     */
    private JButton lockButton;

    /** Last observed lock state. / 上次观测的锁定状态。
    */
    private boolean lastLocked;

    /** Observer notified after the host covers have been applied. / 宿主遮罩应用后通知的观察者。 */
    private java.util.function.Consumer<Boolean> lockObserver = ignored -> {
    };

    /** Binds the validated inputs and owned state for this scope. / 绑定当前作用域的已验证输入及持有状态。
     * @param frame frame supplied to this boundary / 传入当前边界的frame值
     * @param security security supplied to this boundary / 传入当前边界的security值
     * @param messages messages supplied to this boundary / 传入当前边界的messages值
     */
    DesktopLockController(JFrame frame, SecurityApplicationFacade security, PageMessagePresenter messages) {
        this.frame = frame;
        this.security = security;
        this.messages = messages;
        exit = frame::dispose;
        gold.debug.windowstolinux.app.ui.component.DesktopTaskExecutor.bindSecurity(security);
        keys = event -> {
            Component source = event.getComponent();
            Window window = source instanceof Window value ? value : SwingUtilities.getWindowAncestor(source);
            if (!owned(window))
                return false;
            if (event.getID() == KeyEvent.KEY_PRESSED && event.getKeyCode() == KeyEvent.VK_L && event.isControlDown()
                    && event.isShiftDown()) {
                lock();
                return true;
            }
            if (locked()) {
                synchronize();
                Cover cover = window instanceof RootPaneContainer root ? covers.get(root) : null;
                return cover == null || !SwingUtilities.isDescendingFrom(source, cover.panel) || event.isAltDown()
                        || event.isControlDown()
                                && !java.util.Set.of(KeyEvent.VK_A, KeyEvent.VK_C, KeyEvent.VK_V, KeyEvent.VK_X)
                                        .contains(event.getKeyCode())
                        || event.getKeyCode() >= KeyEvent.VK_F1 && event.getKeyCode() <= KeyEvent.VK_F12;
            }
            return false;
        };
        windows = event -> {
            if (event.getSource() instanceof Window window && owned(window))
                synchronize();
        };
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keys);
        Toolkit.getDefaultToolkit().addAWTEventListener(windows, AWTEvent.WINDOW_EVENT_MASK);
        timer = new javax.swing.Timer(100, event -> synchronize());
        timer.start();
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            /** Allows shutdown only after task and security-change completion. / 仅在任务及安全变更完成后允许关闭。
             * @param event event supplied to this boundary / 传入当前边界的event值
             */
            @Override
            public void windowClosing(WindowEvent event) {
                if (security.securityState().activeTasks() > 0 || security.securityState().changing()) {
                    synchronize();
                    if (locked())
                        covers.get(frame).status.setText(messages.text("security.exit.unlock"));
                    else
                        JOptionPane.showMessageDialog(frame, messages.text("security.exit.tasks"));
                } else {
                    security.lock();
                    exit.run();
                }
            }
        });
        synchronize();
    }

    /** Registers the application shutdown callback behind the task guard. / 在任务保护之后注册应用关闭回调。
     * @param action action supplied to this boundary / 传入当前边界的action值
     */
    void onExit(Runnable action) {
        exit = Objects.requireNonNull(action);
    }

    /**
     * Connects Mod environment updates after applying the host privacy boundary. / 在应用宿主隐私边界后连接 Mod 环境更新。
     * @param observer current lock-state observer / 当前锁定状态观察者
     */
    void onLockChange(java.util.function.Consumer<Boolean> observer) {
        lockObserver = Objects.requireNonNull(observer);
        observer.accept(locked());
    }

    /** Drops interactive access while preserving accepted task capabilities. / 释放交互会话，保留已接纳任务的凭据能力。
     */
    void lock() {
        if (!security.securityState().configured())
            return;
        security.lock();
        synchronize();
    }

    /** Offers settings navigation without hiding the first-use workspace. / 提供设置导航，不遮挡首次使用的主界面。
     * @param openSettings opens the settings page / 打开设置页
     */
    private void promptSetup(Runnable openSettings) {
        if (!frame.isShowing() || security.securityState().configured())
            return;
        Object[] options = {messages.text("security.setup.openSettings"), messages.text("security.setup.later")};
        int choice = JOptionPane.showOptionDialog(frame, messages.text("security.setup.hint"),
                messages.text("security.setup"), JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null,
                options, options[0]);
        if (choice == 0 && frame.isShowing())
            openSettings.run();
    }

    /** Distinguishes a locked vault from an unconfigured first-use workspace. / 区分已锁定凭据库与尚未配置的首次使用界面。
     * @return whether configured credentials are locked / 已配置凭据是否锁定
     */
    private boolean locked() {
        var state = security.securityState();
        return state.configured() && !state.unlocked();
    }

    /** Checks whether a window belongs to this desktop frame. / 检查窗口是否属于当前桌面主窗口。
     * @param window window supplied to this boundary / 传入当前边界的window值
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    private boolean owned(Window window) {
        for (Window current = window; current != null; current = current.getOwner())
            if (current == frame)
                return true;
        return false;
    }

    /** Covers or restores every owned window according to current lock state. / 依据当前锁定状态遮挡或恢复所有从属窗口。
     */
    private void synchronize() {
        boolean locked = locked();
        if (lockButton != null)
            lockButton.setEnabled(security.securityState().configured());
        if (locked && !lastLocked) {
            MenuSelectionManager.defaultManager().clearSelectedPath();
            for (Window window : Window.getWindows())
                if (owned(window))
                    clearPasswords(window);
        }
        for (Window window : Window.getWindows()) {
            if (!owned(window) || !(window instanceof RootPaneContainer root))
                continue;
            if (locked) {
                Cover cover = covers.computeIfAbsent(root, Cover::new);
                cover.update();
            } else {
                Cover cover = covers.remove(root);
                if (cover != null)
                    cover.restore();
            }
        }
        boolean changed = lastLocked != locked;
        lastLocked = locked;
        if (changed)
            lockObserver.accept(locked);
    }

    /** Clears password controls recursively before covering a window. / 在遮挡窗口前递归清除密码控件。
     * @param component component supplied to this boundary / 传入当前边界的component值
     */
    private static void clearPasswords(Component component) {
        if (component instanceof JPasswordField field)
            field.setText("");
        if (component instanceof Container container)
            for (Component child : container.getComponents())
                clearPasswords(child);
    }

    /** Opaque authorization surface for one owned window. / 单个从属窗口的不透明授权界面。
     */
    private final class Cover {
        /** Window root protected by this cover. / 当前遮罩保护的窗口根节点。
         */
        private final RootPaneContainer root;

        /** Original glass pane restored on unlock. / 解锁时恢复的原玻璃面板。
         */
        private final Component oldGlass;

        /** Original default button restored on unlock. / 解锁时恢复的原默认按钮。
         */
        private final JButton oldDefault;

        /** Original dialog title hidden while locked. / 锁定时隐藏的原对话框标题。
         */
        private final String oldTitle;

        /** Opaque lock surface. / 不透明锁定界面。
         */
        private final JPanel panel = new JPanel(new GridBagLayout());

        /** Safe status and error label. / 安全状态及错误标签。
         */
        /** Task-count overview without private details. / 不含私密详情的任务数量概要。
         */
        private final JLabel status = new JLabel(), summary = new JLabel();

        /** Master password input control. / 主密码输入控件。
         */
        private final JPasswordField password = new JPasswordField(24);

        /** Explicit password submission action. / 显式密码提交动作。
         */
        private final JButton submit = new JButton();

        /** Action revealing the initially hidden password form. / 显示初始隐藏密码表单的动作。
         */
        private final JButton enter = new JButton();

        /** Unlock form. / 解锁表单。
         */
        private final JPanel form = new JPanel(new GridLayout(0, 1, 0, 10));

        /** Prevents concurrent form submissions. / 防止并发提交表单。
         */
        private boolean busy;

        /** Binds the validated inputs and owned state for this scope. / 绑定当前作用域的已验证输入及持有状态。
         * @param root root supplied to this boundary / 传入当前边界的root值
         */
        Cover(RootPaneContainer root) {
            this.root = root;
            clearPasswords(root.getContentPane());
            oldTitle = root instanceof Dialog dialog ? dialog.getTitle() : null;
            if (root instanceof Dialog dialog)
                dialog.setTitle(messages.text("security.locked"));
            oldGlass = root.getGlassPane();
            oldDefault = root.getRootPane().getDefaultButton();
            root.getRootPane().setDefaultButton(submit);
            panel.setName("security.locked");
            panel.setOpaque(true);
            panel.setFocusCycleRoot(true);
            form.setBorder(BorderFactory.createEmptyBorder(28, 28, 28, 28));
            JLabel title = new JLabel(messages.text("security.locked"));
            title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
            form.add(title);
            form.add(summary);
            enter.setText(messages.text("security.unlock"));
            form.add(enter);
            JLabel label = new JLabel(messages.text("security.password"));
            form.add(label);
            form.add(password);
            form.add(submit);
            form.add(status);
            label.setVisible(false);
            password.setVisible(false);
            submit.setVisible(false);
            enter.addActionListener(event -> {
                enter.setVisible(false);
                label.setVisible(true);
                password.setVisible(true);
                submit.setVisible(true);
                submit.setText(messages.text("security.unlock"));
                password.requestFocusInWindow();
                panel.revalidate();
            });
            password.setName("security.password");
            submit.setName("security.submit");
            enter.setName("security.enter");
            password.addActionListener(event -> unlock());
            submit.addActionListener(event -> unlock());
            panel.add(form);
            panel.addMouseListener(new MouseAdapter() {
            });
            panel.addMouseMotionListener(new MouseMotionAdapter() {
            });
            root.getContentPane().setVisible(false);
            root.setGlassPane(panel);
            panel.setVisible(true);
            SwingUtilities.invokeLater(enter::requestFocusInWindow);
        }

        /** Refreshes visible status from the current security state. / 按当前安全状态刷新可见状态。
         */
        void update() {
            summary.setText(messages.text("security.tasks", Map.of("count", security.securityState().activeTasks())));
            if (!panel.isVisible())
                panel.setVisible(true);
            root.getContentPane().setVisible(false);
        }

        /** Verifies the password and opens a session without exposing the data key. / 验证密码并建立会话，不暴露数据密钥。
         */
        void unlock() {
            if (busy || !submit.isVisible())
                return;
            busy = true;
            submit.setEnabled(false);
            password.setEnabled(false);
            char[] supplied = password.getPassword();
            clearPasswords(form);
            new SwingWorker<Void, Void>() {
                /** Executes the security request away from the event thread and clears buffers. / 在事件线程外执行安全请求并清理缓冲区。
                 * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
                 * @throws Exception if validation or the scoped operation fails / 校验或限定操作失败时
                 */
                @Override
                protected Void doInBackground() throws Exception {
                    try {
                        security.unlock(supplied);
                        return null;
                    } finally {
                        Arrays.fill(supplied, '\0');
                    }
                }

                /** Reports completion on the event thread without changing lock state implicitly. / 在事件线程报告完成，不隐式修改锁定状态。
                 */
                @Override
                protected void done() {
                    busy = false;
                    submit.setEnabled(true);
                    password.setEnabled(true);
                    try {
                        get();
                        synchronize();
                    } catch (Exception failure) {
                        status.setText(messages.safe(failure));
                        password.requestFocusInWindow();
                    }
                }
            }.execute();
        }

        /** Restores the covered window and clears lock-form password buffers. / 恢复被遮挡窗口并清除锁定表单密码缓冲区。
         */
        void restore() {
            if (root instanceof Dialog dialog)
                dialog.setTitle(oldTitle);
            root.getRootPane().setDefaultButton(oldDefault);
            clearPasswords(form);
            panel.setVisible(false);
            root.setGlassPane(oldGlass);
            root.getContentPane().setVisible(true);
        }
    }

    /** Releases this scope and its owned resources. / 释放当前作用域及其持有资源。
     */
    @Override
    public void close() {
        gold.debug.windowstolinux.app.ui.component.DesktopTaskExecutor.unbindSecurity(security);
        timer.stop();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keys);
        Toolkit.getDefaultToolkit().removeAWTEventListener(windows);
        covers.values().forEach(cover -> clearPasswords(cover.panel));
        covers.clear();
    }
}
