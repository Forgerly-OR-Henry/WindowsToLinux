package gold.debug.windowstolinux.app.ui.window;

import java.awt.*;
import javax.swing.JButton;

import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;

/** Fixed two-line identity fading within the available sidebar width. / 在可用侧栏宽度内淡入淡出的固定两行标识。 */
final class DesktopBrandLabel extends JButton {
    /** Existing localized product identity. / 既有本地化产品标识。 */
    private final MessageCatalog messages;

    /** Continuous caption presentation shared with sidebar navigation. / 与侧栏导航共用的连续文案展示。 */
    private double folded;

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

    /** Follows the original width clock while preserving logo placement. / 跟随原宽度时钟，保留 Logo 位置。
     * @param progress expanded-to-collapsed progress / 展开至折叠进度 */
    void presentation(double progress) {
        folded = Math.max(0, Math.min(1, progress));
        repaint();
    }

    /** Fits both identity lines once to the expanded width, independent of collapse frames. / 按展开宽度适配两行标识，不随折叠帧改变字号。
     * @param width expanded identity width / 展开标识宽度 */
    void fitCaption(int width) {
        if (getIcon() == null) return;
        int available = Math.max(1, width - iconX(width) - getIcon().getIconWidth() - UIScale.scale(8));
        var metrics = getFontMetrics(getFont());
        int required = Math.max(metrics.stringWidth(messages.text("shell.brand.top")), metrics.stringWidth(messages.text("shell.brand.bottom")));
        if (required > available)
            setFont(getFont().deriveFont(Math.max(8f, (float) Math.floor(getFont().getSize2D() * available / required))));
    }

    /** Keeps the original logo position within the actual width. / 在实际宽度内保持原 Logo 位置。
     * @param width available width / 可用宽度
     * @return leading logo coordinate / Logo 起始坐标 */
    private int iconX(int width) {
        return Math.max(0, Math.min((UIScale.scale(48) - getIcon().getIconWidth()) / 2, width - getIcon().getIconWidth()));
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
            int x = iconX(getWidth());
            getIcon().paintIcon(this, g, x, Math.max(0, (height - getIcon().getIconHeight()) / 2));
            g.setFont(getFont());
            var metrics = g.getFontMetrics();
            String top = messages.text("shell.brand.top"), bottom = messages.text("shell.brand.bottom");
            int textX = x + getIcon().getIconWidth() + UIScale.scale(8);
            float opacity = (float) (1 - folded);
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
