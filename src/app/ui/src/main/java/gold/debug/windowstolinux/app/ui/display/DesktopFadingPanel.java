package gold.debug.windowstolinux.app.ui.display;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Objects;
import javax.swing.JPanel;
import gold.debug.windowstolinux.appapi.ui.page.*;

/** Fades changed identities while ordinary refreshes keep the current surface. */
public class DesktopFadingPanel extends JPanel implements AutoCloseable {
    private final DesktopMotionScope scope = new DesktopMotionScope(() -> true);
    private UiMotionHandle transition;
    private Object identity;
    private BufferedImage outgoing;
    private float opacity;

    public DesktopFadingPanel() { this(new FlowLayout()); }

    public DesktopFadingPanel(LayoutManager layout) {
        super(layout);
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && !isShowing()) resetMotion();
        });
    }

    /** Call before changing content; a null identity clears private paint immediately. */
    public void content(Object next) {
        if (Objects.equals(identity, next)) return;
        BufferedImage image = identity != null && next != null && isShowing() && DesktopMotion.enabled()
            ? DesktopDrawer.snapshot(this) : null;
        resetMotion();
        identity = next;
        outgoing = image;
        if (image != null) transition = scope.animate(this, UiMotionKind.CONTENT, progress -> {
            opacity = (float) (1 - progress);
            if (progress == 1) outgoing = null;
            repaint();
        });
    }

    public void resetMotion() {
        if (transition != null) transition.close();
        transition = null; outgoing = null; opacity = 0;
        repaint();
    }

    @Override protected void paintChildren(Graphics graphics) {
        super.paintChildren(graphics);
        if (outgoing == null) return;
        var paint = (Graphics2D) graphics.create();
        paint.setComposite(AlphaComposite.SrcOver.derive(opacity));
        paint.drawImage(outgoing, 0, 0, null);
        paint.dispose();
    }

    @Override public void close() { resetMotion(); scope.close(); identity = null; }
}
