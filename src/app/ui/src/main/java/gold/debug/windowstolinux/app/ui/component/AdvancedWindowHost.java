package gold.debug.windowstolinux.app.ui.component;

import java.awt.*;
import java.awt.event.*;

import javax.swing.*;

/**
 * Hosts one active inspector while preserving the primary workspace. / 承载当前检查面板并保持主工作区尺寸。
 */
public final class AdvancedWindowHost implements AutoCloseable {
    /**
     * Width of the advanced pane in pixels.
     * <p>高级面板宽度，单位为像素。
     */
    public static final int WIDTH = 320;

    /**
     * Component or resource identity owning the operation.
     * <p>持有操作的组件或资源身份。
     */
    private final Window owner;

    /**
     * Reviewed server hostname or IP address.
     * <p>已审阅服务器主机名或 IP 地址。
     */
    private final Container host;

    /**
     * Active.
     * <p>活跃。
     */
    private AdvancedOptionsPane active;

    /**
     * Swing control for floating.
     * <p>浮动对应的 Swing 控件。
     */
    private JDialog floating;

    /**
     * Swing control for mounted.
     * <p>已挂载对应的 Swing 控件。
     */
    private JComponent mounted;

    /**
     * Docked.
     * <p>已停靠。
     */
    private boolean docked;

    /**
     * Closing.
     * <p>关闭中。
     */
    private boolean closing;

    /**
     * Normal bounds.
     * <p>普通边界集合。
     */
    private Rectangle normalBounds;

    /**
     * Maximized.
     * <p>已最大化。
     */
    private boolean maximized;

    /**
     * Creates a controller for a BorderLayout window host. / 为窗口 BorderLayout 容器创建控制器。
     *
     * @param owner component or resource identity owning the operation / 持有操作的组件或资源身份
     * @param host reviewed server hostname or IP address / 已审阅服务器主机名或 IP 地址
     */
    public AdvancedWindowHost(Window owner, Container host) {
        this.owner = owner;
        this.host = host;
        normalBounds = owner.getBounds();
        owner.addComponentListener(new ComponentAdapter() {
            /**
             * Updates retained window state after the component moves.
             * <p>在组件移动后更新保留的窗口状态。
             *
             * @param event state or UI event being processed / 正在处理的状态或 UI 事件
             */
            @Override
            public void componentMoved(ComponentEvent event) {
                rememberNormalBounds();
            }

            /**
             * Updates the layout and retained state after the component size changes.
             * <p>在组件大小变化后更新布局及保留状态。
             *
             * @param event state or UI event being processed / 正在处理的状态或 UI 事件
             */
            @Override
            public void componentResized(ComponentEvent event) {
                rememberNormalBounds();
            }
        });
        owner.addWindowListener(new WindowAdapter() {
            /**
             * Completes resource cleanup after the Swing window has closed.
             * <p>在 Swing 窗口关闭后完成资源清理。
             *
             * @param event state or UI event being processed / 正在处理的状态或 UI 事件
             */
            @Override
            public void windowClosed(WindowEvent event) {
                close();
            }
        });
        if (owner instanceof JFrame frame)
            frame.addWindowStateListener(event -> stateChanged(frame));
    }

    /**
     * Refreshes dependent control state after the observed selection changes.
     * <p>在观测选择变化后刷新依赖控件状态。
     *
     * @param frame frame / 框架
     */
    private void stateChanged(JFrame frame) {
        boolean maximum = (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0;
        if (maximum) {
            maximized = true;
            if (docked) {
                unmount();
                update(active);
            }
        } else if (maximized && (frame.getExtendedState() & Frame.ICONIFIED) == 0) {
            Rectangle restore = new Rectangle(normalBounds);
            unmount();
            maximized = false;
            owner.setBounds(restore);
            update(active);
        }
    }

    /**
     * Remembers ordinary window bounds, excluding a docked pane's added width.
     * <p>记录普通窗口边界，排除停靠面板增加的宽度。
     */
    private void rememberNormalBounds() {
        if (closing || maximized || owner instanceof Frame frame && frame.getExtendedState() != Frame.NORMAL)
            return;
        normalBounds = owner.getBounds();
        if (docked)
            normalBounds.width -= WIDTH;
    }

    /**
     * Selects the page; each page retains its own open state. / 切换页面，保留各页面展开状态。
     *
     * @param pane pane / 面板
     */
    public void activate(AdvancedOptionsPane pane) {
        if (active == pane) {
            update(pane);
            return;
        }
        unmount();
        active = pane;
        if (pane != null) {
            pane.bind(this);
            update(pane);
        }
    }

    /**
     * Responds only to the currently active page. / 仅响应当前页面。
     *
     * @param pane pane / 面板
     */
    public void update(AdvancedOptionsPane pane) {
        if (pane == null || active != pane)
            return;
        if (!pane.expanded()) {
            unmount();
            return;
        }
        if (mounted != null)
            return;
        mounted = pane.drawer();
        mounted.setVisible(true);
        Rectangle usable = usableBounds(owner);
        boolean maximized = owner instanceof Frame frame && (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0;
        if (!maximized && owner.getX() + owner.getWidth() + WIDTH <= usable.x + usable.width) {
            normalBounds = owner.getBounds();
            host.add(mounted, BorderLayout.EAST);
            docked = true;
            owner.setSize(owner.getWidth() + WIDTH, owner.getHeight());
            owner.validate();
        } else {
            floating = new JDialog(owner, pane.inspectorTitle(), Dialog.ModalityType.MODELESS);
            floating.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            floating.setContentPane(mounted);
            floating.setSize(WIDTH, Math.max(240, Math.min(owner.getHeight(), usable.height)));
            floating.setLocation(
                    Math.max(usable.x, Math.min(owner.getX() + owner.getWidth(), usable.x + usable.width - WIDTH)),
                    Math.max(usable.y, Math.min(owner.getY(), usable.y + usable.height - floating.getHeight())));
            floating.addWindowListener(new WindowAdapter() {
                /**
                 * Handles the user's close request through the owning window's cleanup path.
                 * <p>通过所属窗口的清理路径处理用户关闭请求。
                 *
                 * @param event state or UI event being processed / 正在处理的状态或 UI 事件
                 */
                @Override
                public void windowClosing(WindowEvent event) {
                    if (!closing)
                        pane.setExpanded(false);
                }
            });
            floating.setVisible(true);
        }
    }

    /**
     * Returns bounds without the inspector's extra width, for theme rebuilding. / 返回不含检查面板附加宽度的窗口尺寸。
     *
     * @return bounds without the inspector's extra width, for theme rebuilding / 不含检查面板附加宽度的窗口尺寸
     */
    public Rectangle workspaceBounds() {
        if (maximized || owner instanceof Frame frame && frame.getExtendedState() != Frame.NORMAL)
            return new Rectangle(normalBounds);
        Rectangle bounds = owner.getBounds();
        if (docked)
            bounds.width -= WIDTH;
        return bounds;
    }

    /**
     * Restores normal bounds before applying a saved maximized window state. / 应用已保存最大化状态前恢复普通窗口尺寸。
     *
     * @param bounds bounds / 边界集合
     */
    public void restoreWorkspaceBounds(Rectangle bounds) {
        normalBounds = new Rectangle(bounds);
        owner.setBounds(bounds);
    }

    /**
     * Detaches the advanced pane, disposes its floating window and restores docked-owner sizing.
     * <p>分离高级面板、销毁其浮动窗口，并恢复停靠宿主的尺寸。
     */
    private void unmount() {
        closing = true;
        try {
            if (mounted != null && mounted.getParent() != null)
                mounted.getParent().remove(mounted);
            if (floating != null) {
                floating.dispose();
                floating = null;
            }
            if (docked) {
                boolean maximized = owner instanceof Frame frame
                        && (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0;
                if (!maximized)
                    owner.setSize(owner.getWidth() - WIDTH, owner.getHeight());
                docked = false;
            }
            mounted = null;
            host.revalidate();
            host.repaint();
        } finally {
            closing = false;
        }
    }

    /**
     * Returns the current screen bounds after removing operating-system insets.
     * <p>扣除操作系统保留边距后返回当前屏幕可用边界。
     *
     * @param window window / 窗口
     * @return the current screen bounds after removing operating-system insets / 扣除操作系统保留边距后返回当前屏幕可用边界
     */
    private static Rectangle usableBounds(Window window) {
        GraphicsConfiguration configuration = window.getGraphicsConfiguration();
        Rectangle bounds = new Rectangle(configuration.getBounds());
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        bounds.x += insets.left;
        bounds.y += insets.top;
        bounds.width -= insets.left + insets.right;
        bounds.height -= insets.top + insets.bottom;
        return bounds;
    }

    /**
     * Disposes only this controller's inspector window. / 仅释放本控制器的检查窗口。
     */
    @Override
    public void close() {
        unmount();
        active = null;
    }
}
