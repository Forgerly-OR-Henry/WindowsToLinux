package gold.debug.windowstolinux.mod.deployment.ui.style;

import java.awt.Dimension;
import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.Graphics;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.UIManager;

/** Rounded Mod-owned surface retaining live Swing colors and original content geometry. / 保留实时 Swing 配色与原内容布局的 Mod 所属圆角表面。 */
final class DeploymentCard extends JPanel {
    /** Creates the original padded presentation surface. / 创建保留原内边距的展示表面。
     * @param layout original content layout / 原内容布局 */
    DeploymentCard(LayoutManager layout) {
        super(layout);
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(DeploymentPageStyle.scale(14), DeploymentPageStyle.scale(16),
            DeploymentPageStyle.scale(14), DeploymentPageStyle.scale(16)));
    }

    /** Prevents vertical forms from stretching input cards. / 阻止纵向表单拉伸输入卡片。
     * @return natural height with flexible width / 自然高度与弹性宽度 */
    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    /** Paints the rounded surface from current host Swing colors. / 使用当前宿主 Swing 配色绘制圆角表面。
     * @param graphics current context / 当前上下文 */
    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        var g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            String color = Boolean.TRUE.equals(getClientProperty("deployment.style.inventory"))
                ? Boolean.TRUE.equals(getClientProperty("deployment.style.selected"))
                ? "List.selectionBackground" : "Table.background"
                : "Table.background";
            g.setColor(UIManager.getColor(color));
            g.fill(outline());
            if (Boolean.TRUE.equals(getClientProperty("deployment.style.outlined"))) {
                float line = DeploymentPageStyle.scaleFactor();
                g.setColor(UIManager.getColor("ScrollBar.thumb"));
                g.setStroke(new BasicStroke(line));
                float inset = line / 2f;
                int arc = DeploymentPageStyle.scale(20);
                g.draw(new RoundRectangle2D.Float(inset, inset, getWidth() - line, getHeight() - line, arc, arc));
            }
        } finally {
            g.dispose();
        }
    }

    /** Keeps opaque content inside the card outline without changing component state. / 保持不透明内容位于卡片轮廓内，不改变组件状态。
     * @param graphics current context / 当前上下文 */
    @Override
    protected void paintChildren(Graphics graphics) {
        var g = (Graphics2D) graphics.create();
        try {
            g.clip(outline());
            super.paintChildren(g);
        } finally {
            g.dispose();
        }
    }

    /** Returns the shared outline for background and child painting. / 返回背景与子组件绘制共用的轮廓。
     * @return current rounded card shape / 当前圆角卡片形状 */
    private Shape outline() {
        int arc = DeploymentPageStyle.scale(20);
        return new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), arc, arc);
    }
}
