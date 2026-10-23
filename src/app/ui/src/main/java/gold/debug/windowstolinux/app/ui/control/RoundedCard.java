package gold.debug.windowstolinux.app.ui.control;

import java.awt.Color;
import java.awt.BasicStroke;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;

import javax.swing.JPanel;

import com.formdev.flatlaf.util.UIScale;

/**
 * One shared, scalable rounded surface. / 共用的可缩放圆角表面。
 */
final class RoundedCard extends JPanel {
    /** Optional inner outline; ordinary cards keep their existing silhouette. / 可选内轮廓，普通卡片保持原轮廓。 */
    private Color outlineColor;

    /** Changes only the outline role during an appearance refresh. / 外观刷新时仅更新轮廓配色角色。 */
    void outlineColor(Color color) {
        outlineColor = color;
        repaint();
    }

    /**
     * Binds the supplied dependencies and state for rounded card.
     * <p>为Rounded卡片绑定传入的依赖及状态。
     *
     * @param layout layout / 布局
     * @param background background / 背景
     */
    RoundedCard(LayoutManager layout, Color background) {
        super(layout);
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
            g.fill(outline());
        } finally {
            g.dispose();
        }
        super.paintComponent(graphics);
    }

    /** Keeps opaque tables and viewports inside the rounded surface. / 将不透明表格及视口限制在圆角表面内。
     * @param graphics original painting context / 原绘制上下文 */
    @Override
    protected void paintChildren(Graphics graphics) {
        Graphics2D clipped = (Graphics2D) graphics.create();
        try {
            clipped.clip(outline());
            super.paintChildren(clipped);
            if (outlineColor != null) {
                float width = UIScale.scale(1f);
                float inset = width / 2;
                float arc = UIScale.scale(20f);
                clipped.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                clipped.setStroke(new BasicStroke(width));
                clipped.setColor(outlineColor);
                clipped.draw(new RoundRectangle2D.Float(inset, inset, getWidth() - width,
                    getHeight() - width, arc - width, arc - width));
            }
        } finally {
            clipped.dispose();
        }
    }

    /** Returns the scaled card silhouette shared by both paint passes. / 返回两个绘制阶段共用的缩放卡片轮廓。
     * @return rounded outline / 圆角轮廓 */
    private Shape outline() {
        int arc = UIScale.scale(20);
        return new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), arc, arc);
    }
}
