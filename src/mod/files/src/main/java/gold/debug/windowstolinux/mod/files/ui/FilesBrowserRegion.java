package gold.debug.windowstolinux.mod.files.ui;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Optional;
import java.util.function.Supplier;
import javax.swing.*;

import gold.debug.windowstolinux.appapi.ui.page.*;

/** Author-owned browser geometry driven only by the public host clock. */
final class FilesBrowserRegion extends JPanel implements AutoCloseable {
    private final Supplier<Optional<UiMotion>> motion;

    private UiMotionHandle transition;

    private JComponent left, right;

    private double progress;

    private boolean expanded;

    private BufferedImage outgoing;

    private Dimension measured;

    FilesBrowserRegion(Supplier<Optional<UiMotion>> motion) {
        super(null);
        this.motion = motion;
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && !isShowing()) reset();
        });
    }

    void left(JComponent component) {
        left = component;
        add(component);
    }

    void expanded(boolean value, JComponent component) {
        if (expanded == value) return;
        if (transition != null) transition.close();
        outgoing = null;
        if (!value && right != null && right.isShowing()) {
            outgoing = new BufferedImage(Math.max(1, right.getWidth()), Math.max(1, right.getHeight()), BufferedImage.TYPE_INT_ARGB);
            var graphics = outgoing.createGraphics();
            right.printAll(graphics);
            graphics.dispose();
        }
        expanded = value;
        if (component != right) {
            if (right != null) remove(right);
            right = component;
            add(right);
        }
        right.setVisible(value);
        double from = progress, target = value ? 1 : 0;
        var clock = motion.get();
        if (clock.isEmpty()) {
            reset();
            return;
        }
        transition = clock.get().animate(this, UiMotionKind.DETAIL, frame -> {
            progress = from + (target - from) * frame;
            if (frame == 1) outgoing = null;
            revalidate();
            repaint();
        });
    }

    void reset() {
        if (transition != null) transition.close();
        transition = null;
        outgoing = null;
        progress = expanded ? 1 : 0;
        revalidate();
        repaint();
    }

    @Override
    public void doLayout() {
        if (left == null) return;
        if (measured != null && !measured.equals(getSize())) reset();
        measured = getSize();
        int target = Math.max(0, (getWidth() - 6) / 2);
        int width = (int) Math.round(getWidth() + (target - getWidth()) * progress);
        left.setBounds(0, 0, width, getHeight());
        if (right != null) right.setBounds(width + (int) Math.round(6 * progress), 0,
            Math.max(0, getWidth() - target - 6), getHeight());
    }

    @Override
    protected void paintChildren(Graphics graphics) {
        super.paintChildren(graphics);
        if (outgoing != null && right != null) graphics.drawImage(outgoing, right.getX(), right.getY(), null);
    }

    @Override
    public void close() {
        reset();
    }
}
