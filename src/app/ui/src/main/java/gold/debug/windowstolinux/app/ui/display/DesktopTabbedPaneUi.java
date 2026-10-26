package gold.debug.windowstolinux.app.ui.display;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.*;
import javax.swing.event.ChangeListener;
import javax.swing.plaf.*;

import com.formdev.flatlaf.ui.FlatTabbedPaneUI;
import gold.debug.windowstolinux.appapi.ui.page.*;

/** Paint-only tab transitions preserve original tab and page component identities. */
public class DesktopTabbedPaneUi extends FlatTabbedPaneUI {
    private Component previous;

    private Overlay overlay;

    private final Runnable policy = () -> {
        if (!DesktopMotion.enabled() && wheelTabScroller instanceof MotionWheelScroller wheel) wheel.finish();
    };

    private final java.awt.event.HierarchyListener lifetime = event -> {
        if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && !tabPane.isShowing()
            && wheelTabScroller instanceof MotionWheelScroller wheel) wheel.finish();
    };

    @Override
    protected void installListeners() {
        super.installListeners();
        DesktopMotion.observe(policy);
        tabPane.addHierarchyListener(lifetime);
    }

    @Override
    protected void uninstallListeners() {
        DesktopMotion.unobserve(policy);
        tabPane.removeHierarchyListener(lifetime);
        super.uninstallListeners();
    }

    @Override
    protected boolean isSmoothScrollingEnabled() {
        return DesktopMotion.enabled() && super.isSmoothScrollingEnabled();
    }

    @Override
    protected FlatWheelTabScroller createWheelTabScroller() {
        return new MotionWheelScroller();
    }

    private final class MotionWheelScroller extends FlatWheelTabScroller {
        private Point target;

        private boolean moving;

        private final ChangeListener position = event -> {
            if (tabViewport != null && tabViewport.getViewPosition().equals(target)) moving = false;
        };

        MotionWheelScroller() {
            if (tabViewport != null) tabViewport.addChangeListener(position);
        }

        @Override
        protected void setViewPositionAnimated(Point next) {
            target = new Point(next);
            moving = DesktopMotion.enabled();
            super.setViewPositionAnimated(next);
        }

        void finish() {
            super.uninstall();
            if (moving && target != null && tabViewport != null && tabViewport.isShowing()) {
                Dimension size = tabViewport.getViewSize();
                tabViewport.setViewPosition(new Point(Math.max(0, Math.min(target.x, size.width - tabViewport.getWidth())),
                    Math.max(0, Math.min(target.y, size.height - tabViewport.getHeight()))));
            }
            moving = false;
        }

        @Override
        protected void uninstall() {
            finish();
            if (tabViewport != null) tabViewport.removeChangeListener(position);
        }
    }

    public static ComponentUI createUI(JComponent component) {
        return new DesktopTabbedPaneUi();
    }

    @Override
    protected void installComponents() {
        super.installComponents();
        overlay = new Overlay();
        tabPane.add(overlay);
        tabPane.setComponentZOrder(overlay, 0);
    }

    @Override
    protected void uninstallComponents() {
        if (overlay != null) {
            overlay.close();
            tabPane.remove(overlay);
            overlay = null;
        }
        previous = null;
        super.uninstallComponents();
    }

    @Override
    protected ChangeListener createChangeListener() {
        var original = super.createChangeListener();
        return event -> {
            BufferedImage image = null;
            if (overlay != null && DesktopMotion.enabled() && tabPane.isShowing() && previous instanceof JComponent old) {
                image = DesktopDrawer.snapshot(old);
                if (image != null) {
                    var g = image.createGraphics();
                    overlay.paintPrior(g);
                    g.dispose();
                }
            }
            original.stateChanged(event);
            previous = tabPane.getSelectedComponent();
            if (overlay != null) overlay.present(image);
        };
    }

    private final class Overlay extends JComponent implements UIResource, AutoCloseable {
        private final DesktopMotionScope scope = new DesktopMotionScope(() -> true);

        private UiMotionHandle motion;

        private BufferedImage image;

        private float opacity;

        Overlay() {
            setVisible(false);
            setFocusable(false);
            addHierarchyListener(event -> {
                if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && !isShowing()) {
                    if (motion != null) motion.close();
                    clear();
                }
            });
        }

        void present(BufferedImage next) {
            if (motion != null) motion.close();
            image = next;
            if (image == null || previous == null) {
                clear();
                return;
            }
            setBounds(previous.getBounds());
            setVisible(true);
            motion = scope.animate(this, UiMotionKind.CONTENT, p -> {
                opacity = (float) (1 - p);
                if (p == 1) clear();
                repaint();
            });
        }

        void clear() {
            image = null;
            opacity = 0;
            setVisible(false);
        }

        void paintPrior(Graphics2D g) {
            if (image == null) return;
            var copy = (Graphics2D) g.create();
            copy.setComposite(AlphaComposite.SrcOver.derive(opacity));
            copy.drawImage(image, 0, 0, null);
            copy.dispose();
        }

        @Override
        public boolean contains(int x, int y) {
            return false;
        }

        @Override
        protected void paintComponent(Graphics g) {
            paintPrior((Graphics2D) g);
        }

        @Override
        public void close() {
            scope.close();
            clear();
        }
    }
}
