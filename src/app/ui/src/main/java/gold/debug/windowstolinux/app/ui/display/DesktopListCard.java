package gold.debug.windowstolinux.app.ui.display;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import javax.swing.*;

import com.formdev.flatlaf.ui.FlatEmptyBorder;
import com.formdev.flatlaf.util.UIScale;

/** Shared inventory surface, including rows painted during drag previews. / 共用清单表面，也用于拖动预览。 */
public final class DesktopListCard extends JPanel {
    /** Marks a completed row so renderers never nest card surfaces. / 标记完整行，避免嵌套卡片。 */
    private static final String ROW = "desktop.inventory.row";

    /** Original selection state for this renderer invocation. / 本次渲染的原选择状态。 */
    private final boolean selected;

    /** Builds one surface around author content. / 围绕作者内容构建一个表面。
     * @param content original content / 原内容
     * @param selected original selection / 原选择
     * @param grip original reorder icon / 原排序图标 */
    private DesktopListCard(Component content, boolean selected, Icon grip) {
        super(new BorderLayout(UIScale.scale(10), 0));
        this.selected = selected;
        setOpaque(false);
        setBorder(new FlatEmptyBorder(10, 16, 10, 16));
        transparent(content);
        if (grip != null) add(new JLabel(grip), BorderLayout.WEST);
        add(content, BorderLayout.CENTER);
    }

    /** Preserves author content inside one spaced card. / 将作者内容保留在一个有间距的卡片中。
     * @param content original renderer / 原渲染内容
     * @param selected original selection / 原选择
     * @param grip optional existing reorder icon / 可选原排序图标
     * @return one complete inventory row / 一个完整清单行 */
    public static JComponent row(Component content, boolean selected, Icon grip) {
        if (content instanceof JComponent component && Boolean.TRUE.equals(component.getClientProperty(ROW)))
            return component;
        var row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setBorder(new FlatEmptyBorder(6, 0, 6, 0));
        row.putClientProperty(ROW, true);
        row.add(new DesktopListCard(content, selected, grip));
        if (content instanceof JComponent component) row.setToolTipText(component.getToolTipText());
        return row;
    }

    /** Lets the shared surface show through standard renderer backgrounds. / 让共用表面透过标准渲染器背景展示。
     * @param content original renderer tree / 原渲染器树 */
    private static void transparent(Component content) {
        if (content instanceof JLabel label) {
            label.setOpaque(false);
            if (label instanceof DefaultListCellRenderer) label.setBorder(new FlatEmptyBorder(0, 0, 0, 0));
        }
        if (content instanceof JPanel panel) {
            panel.setOpaque(false);
            for (var child : panel.getComponents()) transparent(child);
        }
    }

    /** Returns the scaled card boundary. / 返回缩放后的卡片边界。
     * @return rounded boundary / 圆角边界 */
    private Shape outline() {
        int arc = UIScale.scale(24);
        return new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), arc, arc);
    }

    /** Paints the selected surface and marker. / 绘制选中表面及标记。
     * @param graphics current context / 当前绘制上下文 */
    @Override
    protected void paintComponent(Graphics graphics) {
        var g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(UIManager.getColor(selected ? "List.selectionBackground" : "List.cardBackground"));
            g.fill(outline());
            if (selected) {
                g.clip(outline());
                g.setColor(UIManager.getColor("Component.focusColor"));
                g.fillRect(0, 0, UIScale.scale(4), getHeight());
            }
        } finally {
            g.dispose();
        }
        super.paintComponent(graphics);
    }

    /** Clips author content to the card corners. / 将作者内容裁剪到卡片圆角内。
     * @param graphics current context / 当前绘制上下文 */
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
}
