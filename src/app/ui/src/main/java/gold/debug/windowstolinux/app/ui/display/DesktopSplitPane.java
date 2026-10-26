package gold.debug.windowstolinux.app.ui.display;

import java.awt.*;
import javax.swing.*;
import com.formdev.flatlaf.util.UIScale;

/** Shared inventory/detail geometry with a cancellable right-hand reveal. */
public final class DesktopSplitPane extends JPanel implements AutoCloseable {
    private final JComponent inventory, detail;
    private final double fraction;
    private final int gap;
    private final Dimension preferred;
    private final DesktopDrawer drawer;

    public DesktopSplitPane(JComponent inventory, JComponent detail, double fraction, int gap, Dimension preferred) {
        super(null); setOpaque(false);
        this.inventory = inventory; this.detail = detail; this.fraction = fraction; this.gap = gap; this.preferred = preferred;
        drawer = new DesktopDrawer(this, detail); add(inventory); add(detail); detail.setVisible(false);
    }

    public void expanded(boolean value) { drawer.expanded(value); }
    public void reset(boolean value) { drawer.reset(value); }
    public double progress() { return drawer.value(); }
    @Override public Dimension getPreferredSize() { return UIScale.scale(preferred); }
    @Override public Dimension getMinimumSize() { return new Dimension(); }
    @Override public void doLayout() {
        var insets = getInsets(); int width = getWidth() - insets.left - insets.right;
        int spacing = UIScale.scale(gap);
        drawer.layout(inventory, new Rectangle(insets.left, insets.top, width, getHeight()-insets.top-insets.bottom),
            (int)((width-spacing)*fraction), spacing);
    }
    @Override protected void paintChildren(Graphics graphics) { super.paintChildren(graphics); drawer.paint(graphics); }
    @Override public void close() { drawer.close(); }
}
