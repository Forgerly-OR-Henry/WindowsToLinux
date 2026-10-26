package gold.debug.windowstolinux.app.ui.display;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.*;
import javax.swing.*;

import gold.debug.windowstolinux.appapi.ui.page.*;
import com.formdev.flatlaf.util.UIScale;

/** Feedback belongs to the real inventory, never a recycled cell renderer. */
final class DesktopCardFeedback implements AutoCloseable {
    private final JComponent owner;

    private final DesktopMotionScope scope = new DesktopMotionScope(() -> true);

    private final Map<Integer, Sample> rows = new HashMap<>();

    private int hovered = -1;

    private boolean pressed;

    private final java.awt.event.HierarchyListener lifetime = this::hierarchyChanged;

    DesktopCardFeedback(JComponent owner) {
        this.owner = owner;
        owner.addHierarchyListener(lifetime);
    }

    private void hierarchyChanged(java.awt.event.HierarchyEvent event) {
        if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && !owner.isShowing())
            clear();
    }

    void pointer(MouseEvent event, boolean down) {
        hovered = -1;
        if (owner.isEnabled()) {
            int row = owner instanceof JList<?> list ? list.locationToIndex(event.getPoint())
                : ((JTable) owner).rowAtPoint(event.getPoint());
            Shape shape = outline(row);
            if (shape != null && shape.contains(event.getPoint())) hovered = row;
        }
        pressed = down;
        update();
    }

    void exit() {
        hovered = -1;
        pressed = false;
        update();
    }

    private void update() {
        if (hovered >= 0) rows.computeIfAbsent(hovered, ignored -> new Sample());
        for (var entry : java.util.List.copyOf(rows.entrySet())) {
            int row = entry.getKey();
            Sample sample = entry.getValue();
            double target = row == hovered ? pressed ? 1 : .55 : 0;
            if (sample.target == target) continue;
            if (sample.motion != null) sample.motion.close();
            double from = sample.value;
            sample.target = target;
            sample.motion = scope.animate(owner, pressed ? UiMotionKind.PRESS : UiMotionKind.HOVER, progress -> {
                sample.value = from + (target - from) * progress;
                if (progress == 1 && target == 0) rows.remove(row, sample);
                owner.repaint();
            });
        }
    }

    void paint(Graphics graphics) {
        if (!owner.isEnabled()) return;
        var paint = (Graphics2D) graphics.create();
        paint.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color accent = UIManager.getColor("Component.focusColor");
        for (var entry : rows.entrySet()) {
            Shape shape = outline(entry.getKey());
            if (shape == null) continue;
            paint.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(),
                (int) Math.round(255 * entry.getValue().value)));
            paint.setStroke(new BasicStroke(UIScale.scale(1.5f)));
            paint.draw(shape);
        }
        paint.dispose();
    }

    private Shape outline(int row) {
        if (row < 0) return null;
        if (owner.getClientProperty(DesktopListCard.FEEDBACK_OUTLINE) instanceof java.util.function.IntFunction<?> custom)
            return custom.apply(row) instanceof Shape shape ? shape : null;
        if (!DesktopCardSupport.isCardView(owner)) return null;
        Rectangle bounds;
        if (owner instanceof JList<?> list) {
            if (row >= list.getModel().getSize()) return null;
            bounds = list.getCellBounds(row, row);
        } else {
            var table = (JTable) owner;
            if (row >= table.getRowCount() || table.getColumnCount() == 0) return null;
            bounds = table.getCellRect(row, 0, true);
            bounds.width = table.getWidth();
        }
        if (bounds == null) return null;
        float inset = UIScale.scale(.75f), gap = UIScale.scale(9f), arc = UIScale.scale(24f);
        return new RoundRectangle2D.Float(bounds.x + inset, bounds.y + gap + inset,
            bounds.width - inset * 2, bounds.height - gap * 2 - inset * 2, arc, arc);
    }

    void clear() {
        scope.cancel();
        rows.clear();
        hovered = -1;
        pressed = false;
        owner.repaint();
    }

    @Override
    public void close() {
        scope.close();
        rows.clear();
        owner.removeHierarchyListener(lifetime);
    }

    private static final class Sample {
        private double value, target;

        private UiMotionHandle motion;
    }
}
