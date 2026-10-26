package gold.debug.windowstolinux.app.ui.display;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import javax.swing.*;
import gold.debug.windowstolinux.appapi.ui.page.*;

/** Slides a fixed-width detail surface while the inventory gives way. / 固定详情宽度进入，清单同步让位。 */
public final class DesktopDrawer implements AutoCloseable {
    private final JComponent owner, detail;
    private final DesktopMotionScope scope = new DesktopMotionScope(() -> true);
    private UiMotionHandle motion;
    private double value;
    private boolean expanded, closed;
    private BufferedImage outgoing;
    private Dimension size;
    private final HierarchyListener lifetime = this::hierarchyChanged;

    private void hierarchyChanged(HierarchyEvent event) {
        if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && !owner.isShowing()) reset(expanded);
    }

    public DesktopDrawer(JComponent owner, JComponent detail) {
        this.owner = owner; this.detail = detail;
        owner.addHierarchyListener(lifetime);
        detail.setVisible(false);
    }

    public boolean expanded() { return expanded; }
    public double value() { return value; }

    public void expanded(boolean next) {
        if (closed || next == expanded) return;
        if (motion != null) motion.close();
        outgoing = !next && detail.isShowing() ? snapshot(detail) : null;
        expanded = next;
        detail.setVisible(next);
        double from = value, target = next ? 1 : 0;
        motion = scope.animate(owner, UiMotionKind.DETAIL, progress -> {
            value = from + (target - from) * progress;
            if (progress == 1) outgoing = null;
            owner.revalidate(); owner.repaint();
        });
    }

    public void reset(boolean next) {
        if (motion != null) motion.close();
        outgoing = null; expanded = next; value = next ? 1 : 0; detail.setVisible(next);
        owner.revalidate(); owner.repaint();
    }

    /** Only positions existing components; never recreates a business view per frame. */
    public void layout(JComponent inventory, Rectangle bounds, int finalLeft, int gap) {
        var currentSize = new Dimension(bounds.width, bounds.height);
        if (size != null && !size.equals(currentSize)) reset(expanded);
        size = currentSize;
        finalLeft = Math.max(0, Math.min(bounds.width, finalLeft));
        int detailWidth = Math.max(0, bounds.width - finalLeft - gap);
        int left = (int)Math.round(bounds.width + (finalLeft - bounds.width) * value);
        inventory.setBounds(bounds.x, bounds.y, left, bounds.height);
        detail.setBounds(bounds.x + left + (int)Math.round(gap * value), bounds.y, detailWidth, bounds.height);
    }

    public void paint(Graphics graphics) {
        if (outgoing != null) graphics.drawImage(outgoing, detail.getX(), detail.getY(), null);
    }

    static BufferedImage snapshot(JComponent component) {
        if (component.getWidth() <= 0 || component.getHeight() <= 0) return null;
        var image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        component.printAll(graphics); graphics.dispose(); return image;
    }

    @Override public void close() {
        closed = true; scope.close(); outgoing = null; motion = null; owner.removeHierarchyListener(lifetime);
    }
}
