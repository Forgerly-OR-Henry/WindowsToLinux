package gold.debug.windowstolinux.app.ui.control;

import java.awt.AlphaComposite;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComponent;

import com.formdev.flatlaf.ui.FlatButtonUI;
import com.formdev.flatlaf.ui.FlatUIUtils;

/** Keeps sidebar icons fixed while full captions fade with its width clock. / 侧栏图标固定，完整文案随宽度时钟渐隐。 */
public final class DesktopNavigationButton extends JButton {
    /** Continuous caption presentation. / 连续文案展示进度。 */
    private double folded;

    /** Creates an original Swing action with a stable caption. / 创建保留完整文案的原 Swing 动作。
     * @param text full visible and accessible caption / 完整可见及无障碍文案 */
    public DesktopNavigationButton(String text) {
        super(text);
        putClientProperty("html.disable", true);
    }

    /** Follows the host width animation without changing labels or action models. / 跟随宿主宽度动画，不改变文案或动作模型。
     * @param progress expanded-to-collapsed progress / 展开至折叠进度 */
    public void presentation(double progress) {
        double next = Math.max(0, Math.min(1, progress));
        if (folded == next) return;
        folded = next;
        repaint();
    }

    /** Retains native surfaces and feedback while anchoring icon and text painting. / 保留原生底色及反馈，固定图标和文字绘制起点。 */
    @Override
    public void updateUI() {
        setUI(new FlatButtonUI(false) {
            /** Contracts feedback around the anchored icon as captions disappear. / 文案收起时反馈底色围绕固定图标对称收拢。
             * @param graphics paint context / 绘制上下文
             * @param component original button / 原按钮 */
            @Override
            protected void paintBackground(Graphics graphics, JComponent component) {
                var icon = getIcon();
                if (folded == 0 || icon == null) {
                    super.paintBackground(graphics, component);
                    return;
                }
                var background = getBackground(component);
                if (background == null) return;
                int compactWidth = 2 * component.getInsets().left + icon.getIconWidth();
                int width = Math.min(component.getWidth(), (int) Math.round(component.getWidth() * (1 - folded) + compactWidth * folded));
                var g = (Graphics2D) graphics.create();
                try {
                    FlatUIUtils.setRenderingHints(g);
                    g.setColor(FlatUIUtils.deriveColor(background, getBackgroundBase(component, false)));
                    FlatUIUtils.paintComponentBackground(g, 0, 0, width, component.getHeight(),
                        FlatUIUtils.getBorderFocusWidth(component), FlatUIUtils.getBorderArc(component));
                } finally {
                    g.dispose();
                }
            }

            /** Paints fixed original icons and fades complete captions within the available width. / 在可用宽度内绘制固定原图标并渐隐完整文案。
             * @param graphics paint context / 绘制上下文
             * @param component original button / 原按钮 */
            @Override
            public void paint(Graphics graphics, JComponent component) {
                var button = (AbstractButton) component;
                var icon = button.getIcon();
                var insets = button.getInsets();
                if (icon != null) super.paintIcon(graphics, component, new Rectangle(insets.left,
                    insets.top + (button.getHeight() - insets.top - insets.bottom - icon.getIconHeight()) / 2,
                    icon.getIconWidth(), icon.getIconHeight()));
                String text = button.getText();
                if (folded == 1 || text == null || text.isEmpty()) return;
                var g = (Graphics2D) graphics.create();
                try {
                    g.setFont(button.getFont());
                    g.setComposite(AlphaComposite.SrcOver.derive((float) (1 - folded)));
                    var metrics = button.getFontMetrics(button.getFont());
                    int x = insets.left + (icon == null ? 0 : icon.getIconWidth() + button.getIconTextGap());
                    int y = insets.top + (button.getHeight() - insets.top - insets.bottom - metrics.getHeight()) / 2;
                    super.paintText(g, button, new Rectangle(x, y, metrics.stringWidth(text), metrics.getHeight()), text);
                } finally {
                    g.dispose();
                }
            }
        });
    }
}
