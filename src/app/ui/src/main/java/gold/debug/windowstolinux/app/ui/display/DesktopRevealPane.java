package gold.debug.windowstolinux.app.ui.display;

import java.awt.*;
import java.awt.event.HierarchyEvent;
import javax.swing.*;

import gold.debug.windowstolinux.appapi.ui.page.*;

/** Content-only entrance; a real dialog's result and disposal are never delayed. */
public final class DesktopRevealPane extends JPanel implements AutoCloseable {
    private final JComponent content;

    private final DesktopMotionScope scope = new DesktopMotionScope(() -> true);

    private UiMotionHandle motion;

    private float alpha = 1;

    private int offset;

    public DesktopRevealPane(JComponent content) {
        super(new BorderLayout());
        this.content = content;
        setOpaque(false);
        add(content);
        putClientProperty("desktop.motion.content", content);
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) return;
            if (isShowing()) reveal();
            else stop();
        });
    }

    public JComponent content() {
        return content;
    }

    private void reveal() {
        if (motion != null) motion.close();
        motion = scope.animate(this, UiMotionKind.POPUP, p -> {
            alpha = (float) p;
            offset = (int) Math.round(com.formdev.flatlaf.util.UIScale.scale(6) * (1 - p));
            doLayout();
            repaint();
        });
    }

    private void stop() {
        if (motion != null) motion.close();
        alpha = 1;
        offset = 0;
    }

    @Override
    public void doLayout() {
        super.doLayout();
        // Keep local bounds: JPopupMenu.setLocation recreates the popup in screen coordinates.
        content.setBounds(content.getX(), content.getY() + offset, content.getWidth(), content.getHeight());
    }

    @Override
    protected void paintChildren(Graphics graphics) {
        var g = (Graphics2D) graphics.create();
        g.setComposite(AlphaComposite.SrcOver.derive(alpha));
        super.paintChildren(g);
        g.dispose();
    }

    @Override
    public void close() {
        stop();
        scope.close();
    }

    public static void install(JDialog dialog) {
        if (dialog.getContentPane() instanceof DesktopRevealPane) return;
        if (dialog.getContentPane() instanceof JComponent content)
            dialog.setContentPane(new DesktopRevealPane(content));
    }
}
