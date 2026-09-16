package gold.debug.windowstolinux.app.ui.component;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/** Hosts one active inspector while preserving the primary workspace. / 承载当前检查面板并保持主工作区尺寸。 */
public final class AdvancedWindowHost implements AutoCloseable {
    public static final int WIDTH = 320;
    private final Window owner;
    private final Container host;
    private AdvancedOptionsPane active;
    private JDialog floating;
    private JComponent mounted;
    private boolean docked;
    private boolean closing;

    /** Creates a controller for a BorderLayout window host. / 为窗口 BorderLayout 容器创建控制器。 */
    public AdvancedWindowHost(Window owner, Container host) {
        this.owner = owner;
        this.host = host;
        owner.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) { close(); }
        });
        if (owner instanceof JFrame frame) frame.addWindowStateListener(event -> {
            if (docked && (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0) {
                unmount();
                update(active);
            }
        });
    }

    /** Selects the page; each page retains its own open state. / 切换页面，保留各页面展开状态。 */
    public void activate(AdvancedOptionsPane pane) {
        if (active == pane) { update(pane); return; }
        unmount();
        active = pane;
        if (pane != null) { pane.bind(this); update(pane); }
    }

    /** Responds only to the currently active page. / 仅响应当前页面。 */
    public void update(AdvancedOptionsPane pane) {
        if (pane == null || active != pane) return;
        if (!pane.expanded()) { unmount(); return; }
        if (mounted != null) return;
        mounted = pane.drawer();
        mounted.setVisible(true);
        Rectangle usable = usableBounds(owner);
        boolean maximized = owner instanceof Frame frame && (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0;
        if (!maximized && owner.getX() + owner.getWidth() + WIDTH <= usable.x + usable.width) {
            host.add(mounted, BorderLayout.EAST);
            docked = true;
            owner.setSize(owner.getWidth() + WIDTH, owner.getHeight());
            owner.validate();
        } else {
            floating = new JDialog(owner, pane.inspectorTitle(), Dialog.ModalityType.MODELESS);
            floating.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            floating.setContentPane(mounted);
            floating.setSize(WIDTH, Math.max(240, Math.min(owner.getHeight(), usable.height)));
            floating.setLocation(Math.max(usable.x, Math.min(owner.getX() + owner.getWidth(), usable.x + usable.width - WIDTH)),
                    Math.max(usable.y, Math.min(owner.getY(), usable.y + usable.height - floating.getHeight())));
            floating.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent event) { if (!closing) pane.setExpanded(false); }
            });
            floating.setVisible(true);
        }
    }

    /** Returns bounds without the inspector's extra width, for theme rebuilding. / 返回不含检查面板附加宽度的窗口尺寸。 */
    public Rectangle workspaceBounds() {
        Rectangle bounds = owner.getBounds();
        if (docked) bounds.width -= WIDTH;
        return bounds;
    }

    private void unmount() {
        closing = true;
        try {
            if (mounted != null && mounted.getParent() != null) mounted.getParent().remove(mounted);
            if (floating != null) { floating.dispose(); floating = null; }
            if (docked) {
                boolean maximized = owner instanceof Frame frame && (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0;
                if (!maximized) owner.setSize(owner.getWidth() - WIDTH, owner.getHeight());
                docked = false;
            }
            mounted = null;
            host.revalidate(); host.repaint();
        } finally { closing = false; }
    }

    private static Rectangle usableBounds(Window window) {
        GraphicsConfiguration configuration = window.getGraphicsConfiguration();
        Rectangle bounds = new Rectangle(configuration.getBounds());
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        bounds.x += insets.left; bounds.y += insets.top;
        bounds.width -= insets.left + insets.right; bounds.height -= insets.top + insets.bottom;
        return bounds;
    }

    /** Disposes only this controller's inspector window. / 仅释放本控制器的检查窗口。 */
    @Override public void close() { unmount(); active = null; }
}
