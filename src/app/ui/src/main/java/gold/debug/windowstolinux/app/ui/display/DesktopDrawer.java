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

    private double inventoryFraction = 1, targetFraction = 1;

    private double spacing;

    private int targetGap;

    private boolean expanded, closed;

    private BufferedImage outgoing;

    private JComponent inventory;

    private final Rectangle bounds = new Rectangle();

    private int detailWidth;

    private boolean layingOut;

    private final HierarchyListener lifetime = this::hierarchyChanged;

    private void hierarchyChanged(HierarchyEvent event) {
        if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && !owner.isShowing()) reset(expanded);
    }

    public DesktopDrawer(JComponent owner, JComponent detail) {
        this.owner = owner;
        this.detail = detail;
        owner.addHierarchyListener(lifetime);
        detail.setVisible(false);
    }

    public boolean expanded() {
        return expanded;
    }

    public double value() {
        return value;
    }

    public void expanded(boolean next) {
        if (closed || next == expanded) return;
        if (motion != null) motion.close();
        outgoing = !next && detail.isShowing() ? snapshot(detail) : null;
        expanded = next;
        detail.setVisible(next);
        move();
    }

    private void move() {
        if (motion != null) motion.close();
        double from = value, target = expanded ? 1 : 0;
        double fromWidth = inventoryFraction, toWidth = expanded ? targetFraction : 1;
        double fromSpacing = spacing, toSpacing = expanded ? targetGap : 0;
        motion = scope.animate(owner, UiMotionKind.DETAIL, progress -> {
            value = from + (target - from) * progress;
            inventoryFraction = fromWidth + (toWidth - fromWidth) * progress;
            spacing = fromSpacing + (toSpacing - fromSpacing) * progress;
            if (progress == 1) outgoing = null;
            layoutFrame();
        });
    }

    public void reset(boolean next) {
        if (motion != null) motion.close();
        outgoing = null;
        expanded = next;
        value = next ? 1 : 0;
        inventoryFraction = next ? targetFraction : 1;
        spacing = next ? targetGap : 0;
        detail.setVisible(next);
        layoutFrame();
    }

    private void layoutFrame() {
        if (!layingOut) {
            if (inventory == null) owner.revalidate();
            else if (boundsChanged()) owner.doLayout();
        }
        owner.repaint();
    }

    private boolean boundsChanged() {
        int left = (int) Math.round(bounds.width * inventoryFraction);
        int detailX = bounds.x + left + (int) Math.round(spacing);
        return inventory.getX() != bounds.x || inventory.getY() != bounds.y
            || inventory.getWidth() != left || inventory.getHeight() != bounds.height
            || detail.getX() != detailX || detail.getY() != bounds.y
            || detail.getWidth() != detailWidth || detail.getHeight() != bounds.height;
    }

    /** Only positions existing components; never recreates a business view per frame. */
    public void layout(JComponent inventory, Rectangle bounds, int finalLeft, int gap) {
        finalLeft = Math.max(0, Math.min(bounds.width, finalLeft));
        double nextFraction = bounds.width <= 0 ? 1 : finalLeft / (double) bounds.width;
        boolean resized = this.inventory != inventory || this.bounds.width != bounds.width || this.bounds.height != bounds.height;
        boolean retargeted = targetFraction != nextFraction || targetGap != gap;
        this.inventory = inventory;
        this.bounds.setBounds(bounds);
        detailWidth = Math.max(0, bounds.width - finalLeft - gap);
        targetFraction = nextFraction;
        targetGap = gap;
        layingOut = true;
        try {
            if (resized) reset(expanded);
            else if (retargeted && expanded) move();
            int left = (int) Math.round(bounds.width * inventoryFraction);
            position(inventory, bounds.x, bounds.y, left, bounds.height);
            position(detail, bounds.x + left + (int) Math.round(spacing), bounds.y, detailWidth, bounds.height);
        } finally {
            layingOut = false;
        }
    }

    private static void position(JComponent component, int x, int y, int width, int height) {
        boolean resized = component.getWidth() != width || component.getHeight() != height;
        if (resized || component.getX() != x || component.getY() != y) {
            component.setBounds(x, y, width, height);
            if (resized) component.revalidate();
        }
    }

    public void paint(Graphics graphics) {
        if (outgoing != null) graphics.drawImage(outgoing, detail.getX(), detail.getY(), null);
    }

    static BufferedImage snapshot(JComponent component) {
        if (component.getWidth() <= 0 || component.getHeight() <= 0) return null;
        var image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        component.printAll(graphics);
        graphics.dispose();
        return image;
    }

    @Override
    public void close() {
        closed = true;
        scope.close();
        outgoing = null;
        motion = null;
        owner.removeHierarchyListener(lifetime);
    }
}
