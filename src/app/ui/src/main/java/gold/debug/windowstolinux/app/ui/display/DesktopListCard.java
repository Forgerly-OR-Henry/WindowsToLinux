package gold.debug.windowstolinux.app.ui.display;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.event.MouseEvent;
import javax.swing.*;

import com.formdev.flatlaf.ui.FlatEmptyBorder;
import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.ui.control.DesktopIcons;

/** Shared inventory surface, including rows painted during drag previews. / 共用清单表面，也用于拖动预览。 */
public final class DesktopListCard extends JPanel {
    /** Marks a completed row so renderers never nest card surfaces. / 标记完整行，避免嵌套卡片。 */
    private static final String ROW = "desktop.inventory.row";

    static final String OWNER = "desktop.inventory.owner";

    public static final String DRAGGABLE = "desktop.inventory.draggable";

    private static final String GRIP = "desktop.inventory.grip";

    /** Original selection state for this renderer invocation. / 本次渲染的原选择状态。 */
    private boolean selected;

    private JLabel grip;

    /** Builds one surface around author content. / 围绕作者内容构建一个表面。
     * @param content original content / 原内容
     * @param selected original selection / 原选择
     * @param draggable whether this owner offers handle dragging / 所有者是否提供手柄拖动 */
    private DesktopListCard(Component content, boolean selected, boolean draggable) {
        super(new BorderLayout(UIScale.scale(10), 0));
        this.selected = selected;
        setOpaque(false);
        setBorder(new FlatEmptyBorder(10, 16, 10, 16));
        transparent(content);
        draggable(draggable);
        add(content, BorderLayout.CENTER);
    }

    private void draggable(boolean draggable) {
        if (draggable && grip == null) {
            grip = new JLabel(DesktopIcons.icon("action/grip-vertical", 16,
                () -> UIManager.getColor("Label.disabledForeground")));
            grip.putClientProperty(GRIP, true);
            add(grip, BorderLayout.WEST);
        } else if (!draggable && grip != null) {
            remove(grip);
            grip = null;
        }
    }

    /** The owning control alone declares the handle and gesture capability. / 手柄及手势能力只由所属控件声明。 */
    public static boolean isDraggable(JComponent owner) {
        return Boolean.TRUE.equals(owner.getClientProperty(DRAGGABLE));
    }

    /** Preserves author content inside one spaced card. / 将作者内容保留在一个有间距的卡片中。
     * @param content original renderer / 原渲染内容
     * @param selected original selection / 原选择
     * @param draggable whether to show the shared handle / 是否显示共用手柄
     * @return one complete inventory row / 一个完整清单行 */
    public static JComponent row(Component content, boolean selected, boolean draggable) {
        if (content instanceof JComponent component && component.getClientProperty(ROW) instanceof DesktopListCard card) {
            card.selected = selected;
            card.draggable(draggable);
            return component;
        }
        var row = new Row();
        row.setOpaque(false);
        row.setBorder(new FlatEmptyBorder(6, 0, 6, 0));
        var card = new DesktopListCard(content, selected, draggable);
        row.putClientProperty(ROW, card);
        row.add(card);
        if (content instanceof JComponent component) row.setToolTipText(component.getToolTipText());
        return row;
    }

    /** Returns the laid-out icon rectangle, excluding the remaining left slot and card spacing. / 返回实际图标布局矩形，排除左侧槽位余下区域和卡片间距。 */
    public static Rectangle handleBounds(Component row, int width, int height) {
        row.setSize(width, height);
        layout(row);
        return handle(row, row);
    }

    private static void layout(Component component) {
        if (component instanceof Container container) {
            container.doLayout();
            for (var child : container.getComponents()) layout(child);
        }
    }

    private static Rectangle handle(Component component, Component row) {
        if (component instanceof JLabel label && Boolean.TRUE.equals(label.getClientProperty(GRIP))) {
            var insets = label.getInsets();
            var view = new Rectangle(insets.left, insets.top,
                label.getWidth() - insets.left - insets.right, label.getHeight() - insets.top - insets.bottom);
            var icon = new Rectangle();
            SwingUtilities.layoutCompoundLabel(label, label.getFontMetrics(label.getFont()), label.getText(), label.getIcon(),
                label.getVerticalAlignment(), label.getHorizontalAlignment(), label.getVerticalTextPosition(),
                label.getHorizontalTextPosition(), view, icon, new Rectangle(), label.getIconTextGap());
            return SwingUtilities.convertRectangle(label, icon, row);
        }
        if (component instanceof Container container) {
            for (var child : container.getComponents()) {
                var bounds = handle(child, row);
                if (!bounds.isEmpty()) return bounds;
            }
        }
        return new Rectangle();
    }

    private static final class Row extends JPanel {
        private Row() {
            super(new BorderLayout());
        }

        @Override
        public String getToolTipText(MouseEvent event) {
            if (getClientProperty(OWNER) instanceof JComponent owner
                && handleBounds(this, getWidth(), getHeight()).contains(event.getPoint()) && owner.getToolTipText() != null)
                return owner.getToolTipText();
            return super.getToolTipText(event);
        }
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
