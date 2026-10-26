package gold.debug.windowstolinux.app.ui.display;

import java.awt.Color;
import java.awt.event.*;
import javax.swing.JComponent;

import gold.debug.windowstolinux.appapi.ui.page.*;

/** Interpolates painted colors without rewriting component styles each frame. */
final class DesktopColorMotion implements AutoCloseable {
    private final JComponent owner;

    private final DesktopMotionScope scope = new DesktopMotionScope(() -> true);

    private Color value, target;

    private UiMotionHandle motion;

    private final HierarchyListener lifetime = this::hierarchyChanged;

    DesktopColorMotion(JComponent owner) {
        this.owner = owner;
        owner.addHierarchyListener(lifetime);
    }

    private void hierarchyChanged(HierarchyEvent event) {
        if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && !owner.isShowing()) {
            if (motion != null) motion.close();
            value = target;
        }
    }

    Color color(Color next, UiMotionKind kind) {
        if (next == null) return null;
        if (value == null || !DesktopMotion.enabled() || !owner.isShowing()) {
            if (motion != null) motion.close();
            value = next;
            target = next;
        } else if (!next.equals(target)) {
            if (motion != null) motion.close();
            Color from = value;
            target = next;
            motion = scope.animate(owner, kind, progress -> {
                value = blend(from, next, progress);
                owner.repaint();
            });
        }
        return value;
    }

    static Color blend(Color from, Color to, double amount) {
        return new Color((int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * amount),
            (int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * amount),
            (int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * amount),
            (int) Math.round(from.getAlpha() + (to.getAlpha() - from.getAlpha()) * amount));
    }

    @Override
    public void close() {
        scope.close();
        owner.removeHierarchyListener(lifetime);
        value = target = null;
    }
}
