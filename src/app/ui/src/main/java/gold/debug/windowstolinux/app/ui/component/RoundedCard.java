package gold.debug.windowstolinux.app.ui.component;

import java.awt.*;

import javax.swing.JPanel;

/**
 * One shared, scalable rounded surface. / 共用的可缩放圆角表面。
 */
final class RoundedCard extends JPanel {
    /**
     * Outline.
     * <p>轮廓。
     */
    private final Color outline;

    /**
     * Binds the supplied dependencies and state for rounded card.
     * <p>为Rounded卡片绑定传入的依赖及状态。
     *
     * @param layout layout / 布局
     * @param background background / 背景
     * @param outline outline / 轮廓
     */
    RoundedCard(LayoutManager layout, Color background, Color outline) {
        super(layout);
        this.outline = outline;
        setBackground(background);
        setOpaque(false);
    }

    /**
     * Paints component.
     * <p>绘制组件。
     *
     * @param graphics graphics / 图形
     */
    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(getBackground());
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 24, 24);
            g.setColor(outline);
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 24, 24);
        } finally {
            g.dispose();
        }
        super.paintComponent(graphics);
    }
}
