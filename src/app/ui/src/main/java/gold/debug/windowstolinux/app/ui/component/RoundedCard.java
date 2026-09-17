package gold.debug.windowstolinux.app.ui.component;

import javax.swing.JPanel;
import java.awt.*;

/** One shared, scalable rounded surface. / 共用的可缩放圆角表面。 */
final class RoundedCard extends JPanel {
    private final Color outline;

    RoundedCard(LayoutManager layout, Color background, Color outline) {
        super(layout);
        this.outline = outline;
        setBackground(background);
        setOpaque(false);
    }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(getBackground());
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 24, 24);
            g.setColor(outline);
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 24, 24);
        } finally { g.dispose(); }
        super.paintComponent(graphics);
    }
}
