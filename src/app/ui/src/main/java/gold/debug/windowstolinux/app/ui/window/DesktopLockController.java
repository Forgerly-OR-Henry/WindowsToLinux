package gold.debug.windowstolinux.app.ui.window;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import javax.swing.*;

import gold.debug.windowstolinux.app.service.security.SecurityAccess;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;

/** Locks content while preserving navigation and accepted tasks. / 锁定内容，保留导航和已接受任务。 */
final class DesktopLockController implements AutoCloseable {
    /** Owned desktop privacy boundary. / 所属桌面隐私边界。 */
    private final JFrame frame;

    /** Existing authorization facade. / 既有授权门面。 */
    private final SecurityAccess security;

    /** Localized safe diagnostic presenter. / 本地化安全诊断展示器。 */
    private final PageMessagePresenter messages;

    /** Transparent input gate preserving navigation. / 保留导航的透明输入门禁。 */
    private final DesktopLockGate gate;

    /** Original glass pane restored on unlock or disposal. / 解锁或释放时恢复的原玻璃层。 */
    private final Component oldGlass;

    /** Owned dialogs hidden while locked and their original visibility. / 锁定时隐藏的所属对话框及其原可见状态。 */
    private final Map<Window, Boolean> hidden = new IdentityHashMap<>();

    /** EDT security-state observer stopped on disposal. / 释放时停止的 EDT 安全状态观察计时器。 */
    private final javax.swing.Timer timer;

    /** Owned-window keyboard admission filter. / 所属窗口键盘准入过滤器。 */
    private final KeyEventDispatcher keys;

    /** Synchronizes newly opened owned windows with privacy state. / 将新打开的所属窗口与隐私状态同步。 */
    private final AWTEventListener windows;

    /** Sidebar state and lock or unlock action. / 侧栏状态及锁定或解锁操作。 */
    private final DesktopLockStatus controls;

    /** Original default button withheld while locked. / 锁定时暂时撤除的原默认按钮。 */
    private JButton oldDefault;

    /** Guarded shutdown callback. / 受门禁保护的关闭回调。 */
    private Runnable exit;

    /** Last privacy and admission state, disposal and reentrancy guards. / 上次隐私及准入状态、释放及重入保护。 */
    private boolean lastLocked, lastBlocked, closed, updating;

    /** Receives admission changes after applying the input gate. / 输入门禁应用后接收准入变化。 */
    private java.util.function.Consumer<Boolean> lockObserver = ignored -> {
    };

    /** Single owned password prompt, absent until requested. / 唯一所属密码提示，请求前为空。 */
    private DesktopUnlockDialog unlock;

    /** Attaches the sidebar status and authorization boundary. / 接入侧栏状态及授权边界。
     * @param frame desktop / 桌面
     * @param service authorization / 授权
     * @param catalog language / 语言
     * @param reports diagnostics / 诊断
     * @param sidebar bottom controls / 底部控件
     * @param components themed controls / 主题控件
     * @param initialWindow first-use prompt / 首次提示
     * @param openSettings settings navigation / 设置导航
     * @return controller or null for preview facades / 控制器，预览门面为空 */
    static DesktopLockController attach(JFrame frame, Object service,
                                        gold.debug.windowstolinux.app.ui.language.MessageCatalog catalog,
                                        gold.debug.windowstolinux.app.ui.error.FailureReportStore reports, JPanel sidebar,
                                        gold.debug.windowstolinux.app.ui.control.DesktopPartFactory components,
                                        boolean initialWindow, Runnable openSettings) {
        if (!(service instanceof SecurityAccess security)) return null;
        var controller = new DesktopLockController(frame, security, new PageMessagePresenter(catalog, reports));
        sidebar.add(controller.controls);
        if (initialWindow) frame.addWindowListener(new WindowAdapter() {
            /** Schedules the first-use security settings prompt after the desktop opens. / 桌面打开后安排首次安全设置提示。
             * @param event original event notification / 原事件通知
             */
            @Override
            public void windowOpened(WindowEvent event) {
                SwingUtilities.invokeLater(() -> controller.promptSetup(openSettings));
            }
        });
        return controller;
    }

    /** Attaches privacy listeners and the original input boundary. / 接入隐私监听器及原输入边界。
     * @param frame owned desktop / 所属桌面
     * @param security original authorization facade / 原授权门面
     * @param messages localized safe messages / 本地化安全消息
     */
    DesktopLockController(JFrame frame, SecurityAccess security, PageMessagePresenter messages) {
        this.frame = frame;
        this.security = security;
        this.messages = messages;
        exit = frame::dispose;
        oldGlass = frame.getGlassPane();
        gate = new DesktopLockGate(frame);
        controls = new DesktopLockStatus(messages, () -> {
            if (locked()) openUnlock();
            else lock();
        });
        gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner.bindSecurity(security);
        keys = this::key;
        windows = event -> {
            if (!closed && event.getSource() instanceof Window window && owned(window)) synchronize();
        };
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keys);
        Toolkit.getDefaultToolkit().addAWTEventListener(windows, AWTEvent.WINDOW_EVENT_MASK);
        timer = new javax.swing.Timer(100, event -> synchronize());
        timer.start();
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            /** Applies the existing task and privacy guards before closing. / 关闭前应用既有任务及隐私门禁。
             * @param event original event notification / 原事件通知
             */
            @Override
            public void windowClosing(WindowEvent event) {
                if (security.securityState().activeTasks() > 0 || security.securityState().changing()) {
                    if (locked()) controls.blockedExit();
                    else JOptionPane.showMessageDialog(frame, messages.text("security.exit.tasks"));
                } else {
                    security.lock();
                    exit.run();
                }
            }
        });
        synchronize();
    }

    /** Filters owned-window shortcuts and preserves locked navigation. / 过滤所属窗口快捷键并保留锁定导航。
     * @param event original event notification / 原事件通知
     * @return whether the original key is consumed / 是否消费原按键
     */
    private boolean key(KeyEvent event) {
        Component source = event.getComponent();
        Window window = source instanceof Window value ? value : SwingUtilities.getWindowAncestor(source);
        if (!owned(window)) return false;
        if (event.getID() == KeyEvent.KEY_PRESSED && event.getKeyCode() == KeyEvent.VK_L
            && event.isControlDown() && event.isShiftDown()) {
            lock();
            return true;
        }
        if (!locked()) return false;
        if (unlock != null && window == unlock.window()) return false;
        if (window != frame && navigationPopup(window)) return DesktopLockGate.blocks(event);
        if (window == frame && event.getKeyCode() == KeyEvent.VK_TAB) {
            if (event.getID() == KeyEvent.KEY_PRESSED) focusNavigation(event.isShiftDown());
            return true;
        }
        return window != frame || DesktopLockGate.blocks(event);
    }

    /** Moves keyboard focus through currently admitted navigation controls. / 在当前准入导航控件间移动键盘焦点。
     * @param reverse whether to traverse backwards / 是否反向遍历
     */
    private void focusNavigation(boolean reverse) {
        var candidates = new java.util.ArrayList<Component>();
        collectNavigation(frame.getContentPane(), candidates);
        if (candidates.isEmpty()) return;
        int current = candidates.indexOf(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
        candidates.get(Math.floorMod(current + (reverse ? -1 : 1), candidates.size())).requestFocusInWindow();
    }

    /** Collects visible enabled host navigation controls in tree order. / 按树顺序收集可见且启用的宿主导航控件。
     * @param component original component tree / 原组件树
     * @param result collected admitted controls / 收集到的准入控件
     */
    private static void collectNavigation(Component component, java.util.List<Component> result) {
        if (component.isShowing() && component.isEnabled() && component.isFocusable()
            && (component instanceof JTabbedPane || component instanceof JComponent control
            && Boolean.TRUE.equals(control.getClientProperty("desktop.navigation")))) result.add(component);
        if (component instanceof Container container)
            for (Component child : container.getComponents()) collectNavigation(child, result);
    }

    /** Installs guarded shutdown. / 安装受保护的关闭动作。
     * @param action shutdown / 关闭 */
    void onExit(Runnable action) {
        exit = Objects.requireNonNull(action);
    }

    /** Observes authorization after the input boundary. / 输入边界应用后观察授权。
     * @param observer state observer / 状态观察器 */
    void onLockChange(java.util.function.Consumer<Boolean> observer) {
        lockObserver = Objects.requireNonNull(observer);
        lastBlocked = !security.securityState().unlocked();
        observer.accept(lastBlocked);
    }

    /** Revokes new authorization without cancelling accepted tasks. / 撤销新授权，不取消已接受任务。 */
    void lock() {
        if (!security.securityState().configured()) return;
        security.lock();
        synchronize();
    }

    /** Offers security settings on first use without starting business work. / 首次使用时提示安全设置，不启动业务任务。
     * @param openSettings security settings navigation / 安全设置导航
     */
    private void promptSetup(Runnable openSettings) {
        if (!frame.isShowing() || security.securityState().configured()) return;
        Object[] options = {messages.text("security.setup.openSettings"), messages.text("security.setup.later")};
        int choice = JOptionPane.showOptionDialog(frame, messages.text("security.setup.hint"),
            messages.text("security.setup"), JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE,
            null, options, options[0]);
        if (choice == 0 && frame.isShowing()) openSettings.run();
    }

    /** Reads configured privacy state from the original facade. / 从原门面读取已配置隐私状态。
     * @return whether configured security is locked / 已配置安全是否锁定
     */
    private boolean locked() {
        var current = security.securityState();
        return current.configured() && !current.unlocked();
    }

    /** Checks the original owner chain against the desktop. / 对照桌面检查原所有者链。
     * @param window candidate owned window / 候选所属窗口
     * @return whether the candidate belongs to the desktop / 候选是否属于桌面
     */
    private boolean owned(Window window) {
        for (Window current = window; current != null; current = current.getOwner())
            if (current == frame) return true;
        return false;
    }

    /** Applies privacy state without reentrant listener updates. / 应用隐私状态并避免监听器重入更新。
     */
    private void synchronize() {
        if (closed || updating) return;
        updating = true;
        try {
            applyState();
        } finally {
            updating = false;
        }
    }

    /** Revokes input, conceals owned dialogs and then notifies admission observers. / 撤销输入、隐藏所属对话框后通知准入观察器。
     */
    private void applyState() {
        boolean locked = locked();
        if (locked && !lastLocked) {
            MenuSelectionManager.defaultManager().clearSelectedPath();
            clearPasswords(frame);
            oldDefault = frame.getRootPane().getDefaultButton();
            frame.getRootPane().setDefaultButton(null);
            frame.setGlassPane(gate);
            gate.setVisible(true);
            KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner();
            SwingUtilities.invokeLater(controls::focusAction);
        } else if (!locked && lastLocked) restore();
        lastLocked = locked;
        if (locked) hideDialogs();
        controls.refresh(security.securityState().configured(), locked);
        boolean blocked = !security.securityState().unlocked();
        if (lastBlocked != blocked) {
            lastBlocked = blocked;
            lockObserver.accept(blocked);
        }
        if (unlock != null) unlock.refresh();
    }

    /** Conceals owned business dialogs while retaining their visibility for unlock. / 隐藏所属业务对话框并保留解锁恢复所需可见状态。
     */
    private void hideDialogs() {
        for (Window window : Window.getWindows()) {
            if (window == frame || !owned(window) || !window.isShowing()
                || unlock != null && window == unlock.window() || navigationPopup(window)) continue;
            hidden.putIfAbsent(window, true);
            clearPasswords(window);
            if (window instanceof RootPaneContainer root) root.getContentPane().setVisible(false);
            window.setVisible(false);
        }
    }

    /** Recognizes host navigation menus and tooltips in owned popup trees. / 在所属弹层树中识别宿主导航菜单及提示。
     * @param component original component tree / 原组件树
     * @return whether this tree contains host navigation / 此树是否包含宿主导航
     */
    private static boolean navigationPopup(Component component) {
        if (component instanceof JPopupMenu && DesktopLockGate.navigation(component)) return true;
        if (component instanceof JToolTip tip && DesktopLockGate.navigation(tip.getComponent())) return true;
        if (component instanceof Container container)
            for (Component child : container.getComponents())
                if (navigationPopup(child)) return true;
        return false;
    }

    /** Restores the original glass pane and schedules owned-dialog visibility. / 恢复原玻璃层并安排所属对话框可见状态恢复。
     */
    private void restore() {
        gate.setVisible(false);
        frame.setGlassPane(oldGlass);
        frame.getRootPane().setDefaultButton(oldDefault);
        if (unlock != null) {
            unlock.close();
            unlock = null;
        }
        var original = new IdentityHashMap<>(hidden);
        hidden.clear();
        original.forEach((window, visible) -> SwingUtilities.invokeLater(() -> {
            if (!closed && !locked() && window.isDisplayable()) {
                if (window instanceof RootPaneContainer root) root.getContentPane().setVisible(true);
                if (visible) window.setVisible(true);
            }
        }));
    }

    /** Creates or focuses the single prompt while the desktop remains locked. / 桌面仍锁定时创建或聚焦唯一提示。
     */
    private void openUnlock() {
        if (!locked() || closed) return;
        if (unlock != null) {
            unlock.focus();
            return;
        }
        unlock = new DesktopUnlockDialog(frame, security, messages, this::synchronize, () -> unlock = null);
        unlock.show();
    }

    /** Clears password fields throughout the owned component tree. / 清空所属组件树中的密码字段。
     * @param component original component tree / 原组件树
     */
    private static void clearPasswords(Component component) {
        if (component instanceof JPasswordField field) field.setText("");
        if (component instanceof Container container)
            for (Component child : container.getComponents()) clearPasswords(child);
    }

    /** Releases listeners, password controls and the input gate. / 释放监听器、密码控件及输入门禁。 */
    @Override
    public void close() {
        closed = true;
        gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner.unbindSecurity(security);
        timer.stop();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keys);
        Toolkit.getDefaultToolkit().removeAWTEventListener(windows);
        gate.setVisible(false);
        frame.setGlassPane(oldGlass);
        if (unlock != null) unlock.close();
        hidden.clear();
    }
}
