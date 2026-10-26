package gold.debug.windowstolinux.app.ui.display;

import java.awt.*;
import javax.swing.*;
import javax.swing.plaf.ComponentUI;

import com.formdev.flatlaf.ui.FlatProgressBarUI;
import gold.debug.windowstolinux.appapi.ui.page.*;

/** Animates only painted fill; authoritative values and unknown progress stay unchanged. */
public final class DesktopProgressBarUi extends FlatProgressBarUI {
    private final DesktopMotionScope scope = new DesktopMotionScope(() -> true);

    private UiMotionHandle motion;

    private double displayed = -1, target = -1;

    private final java.awt.event.HierarchyListener lifetime = event -> {
        if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && !progressBar.isShowing()) {
            if (motion != null) motion.close();
            displayed = target;
        }
    };

    private final Runnable policy = () -> {
        if (progressBar == null) return;
        if (progressBar.isIndeterminate()) {
            if (DesktopMotion.enabled()) startAnimationTimer();
            else {
                stopAnimationTimer();
                setAnimationIndex(0);
            }
        }
        progressBar.repaint();
    };

    public static ComponentUI createUI(JComponent component) {
        return new DesktopProgressBarUi();
    }

    @Override
    protected void installListeners() {
        super.installListeners();
        DesktopMotion.observe(policy);
        progressBar.addHierarchyListener(lifetime);
    }

    @Override
    protected void uninstallListeners() {
        DesktopMotion.unobserve(policy);
        progressBar.removeHierarchyListener(lifetime);
        scope.close();
        super.uninstallListeners();
    }

    @Override
    protected void startAnimationTimer() {
        if (DesktopMotion.enabled()) super.startAnimationTimer();
    }

    @Override
    protected int getAmountFull(Insets insets, int width, int height) {
        double next = progressBar.getPercentComplete();
        if (!Double.isFinite(next)) next = 0;
        if (displayed < 0 || !progressBar.isShowing() || !DesktopMotion.enabled()) {
            if (motion != null) motion.close();
            displayed = target = next;
        } else if (next != target) {
            if (motion != null) motion.close();
            double from = displayed;
            target = next;
            double destination = next;
            motion = scope.animate(progressBar, UiMotionKind.PROGRESS, p -> {
                displayed = from + (destination - from) * p;
                progressBar.repaint();
            });
        }
        return (int) Math.round((progressBar.getOrientation() == JProgressBar.HORIZONTAL ? width : height) * displayed);
    }
}
