package gold.debug.windowstolinux.app.ui.window;

import java.awt.*;
import javax.swing.JButton;

import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;

/** Fixed two-line identity fading within the available sidebar width. / 在可用侧栏宽度内淡入淡出的固定两行标识。 */
final class DesktopBrandLabel extends JButton {
    /** Existing localized product identity. / 既有本地化产品标识。 */
    private final MessageCatalog messages;

    /** Binds the original brand messages. / 绑定原标识文案。
     * @param messages original catalog / 原文案目录 */
    DesktopBrandLabel(MessageCatalog messages) {
        this.messages = messages;
        setContentAreaFilled(false);
        setBorderPainted(false);
        setOpaque(false);
        setRolloverEnabled(false);
        setFocusPainted(false);
        setMargin(new Insets(0, 0, 0, 0));
        setMinimumSize(new Dimension(0, 0));
        putClientProperty("JComponent.minimumWidth", 0);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        putClientProperty("desktop.navigation", true);
    }

    /** Draws stable lines without width-dependent ellipsis or HTML layout. / 绘制稳定文字行，不使用随宽度变化的省略或 HTML 布局。
     * @param graphics paint context / 绘制上下文 */
    @Override
    protected void paintComponent(Graphics graphics) {
        if (getIcon() == null) return;
        var g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int height = getHeight() - getInsets().bottom;
            int x = Math.max(0, Math.min((UIScale.scale(48) - getIcon().getIconWidth()) / 2, getWidth() - getIcon().getIconWidth()));
            getIcon().paintIcon(this, g, x, Math.max(0, (height - getIcon().getIconHeight()) / 2));
            g.setFont(getFont());
            var metrics = g.getFontMetrics();
            String top = messages.text("shell.brand.top"), bottom = messages.text("shell.brand.bottom");
            int textX = x + getIcon().getIconWidth() + UIScale.scale(8);
            int required = textX + Math.max(metrics.stringWidth(top), metrics.stringWidth(bottom)) + UIScale.scale(6);
            float opacity = Math.max(0, Math.min(1, (getWidth() - required) / (float) UIScale.scale(24)));
            if (opacity <= 0) return;
            g.setComposite(AlphaComposite.SrcOver.derive(opacity));
            g.setColor(getForeground());
            int baseline = (height - 2 * metrics.getHeight()) / 2 + metrics.getAscent();
            g.drawString(top, textX, baseline);
            g.drawString(bottom, textX, baseline + metrics.getHeight());
        } finally {
            g.dispose();
        }
    }
}
