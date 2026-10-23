package gold.debug.windowstolinux.app.ui.window;

import java.awt.*;
import javax.swing.*;

import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.ui.control.DesktopIcons;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;

/** Sidebar status and its opposite action. / 侧栏状态及对应的反向操作。 */
final class DesktopLockStatus extends JPanel {
    /** Localized security captions. / 本地化安全文案。 */
    private final PageMessagePresenter messages;

    /** State icon and fading caption. / 状态图标及渐隐文案。 */
    private final JLabel status = new JLabel() {
        /** Fades only the caption while the state icon remains visible. / 只淡出文字，状态图标保持可见。
         * @param graphics paint context / 绘制上下文 */
        @Override
        protected void paintComponent(Graphics graphics) {
            paintControl(graphics, this, getIcon(), getText(), getForeground(), false);
        }
    };

    /** Original lock or unlock button preserving keyboard operation. / 保留键盘操作的原锁定或解锁按钮。 */
    private final JButton action = new JButton() {
        /** Keeps the action surface and icon continuous while its caption fades. / 文字淡出时操作底色和图标保持连续。
         * @param graphics paint context / 绘制上下文 */
        @Override
        protected void paintComponent(Graphics graphics) {
            paintControl(graphics, this, getIcon(), getText(), getForeground(), true);
        }
    };

    /** Last rendered state, locale, palette and scale signature. / 上次渲染的状态、语言、配色及缩放签名。 */
    private String appearance;

    /** Continuous sidebar presentation, sharing its existing animation. / 与侧栏既有动画共用的连续展示进度。 */
    private double folded;

    /** Complete localized state and action captions. / 完整本地化状态及操作文案。 */
    private String stateText, actionText;

    /** Creates sidebar security captions and the original toggle action. / 创建侧栏安全文案及原切换操作。
     * @param messages localized safe messages / 本地化安全消息
     * @param toggle existing lock or unlock action / 既有锁定或解锁操作
     */
    DesktopLockStatus(PageMessagePresenter messages, Runnable toggle) {
        super(null);
        this.messages = messages;
        setName("security.statusControl");
        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);
        sizing();
        status.setName("security.state");
        status.setFont(status.getFont().deriveFont(11f));
        action.setName("security.lock");
        action.putClientProperty("desktop.navigation", true);
        action.putClientProperty("JComponent.minimumWidth", 0);
        action.putClientProperty("JButton.buttonType", null);
        action.putClientProperty("FlatLaf.style", "borderWidth: 0; focusWidth: 0; arc: 14");
        action.setBorderPainted(false);
        action.setContentAreaFilled(false);
        action.setMargin(new Insets(5, 5, 5, 5));
        action.addActionListener(event -> toggle.run());
        add(status);
        add(action);
    }

    /** Updates security captions and controls from the original facade. / 从原门面更新安全文案及控件。
     * @param configured whether a master password is configured / 是否已配置主密码
     * @param locked current privacy state / 当前隐私状态
     */
    void refresh(boolean configured, boolean locked) {
        String current = configured + ":" + locked + ":" + messages.catalog().locale()
            + ":" + UIManager.getColor("Label.foreground") + ":" + UIScale.getUserScaleFactor();
        if (current.equals(appearance)) return;
        appearance = current;
        sizing();
        status.setFont(status.getFont().deriveFont(UIScale.scale(11f)));
        status.setBorder(BorderFactory.createEmptyBorder(0,
            UIScale.scale(11) + (UIScale.scale(19) - UIScale.scale(14)) / 2, 0, 0));
        stateText = messages.text(!configured ? "security.state.unconfigured"
            : locked ? "security.state.locked" : "security.state.unlocked");
        status.setForeground(UIManager.getColor("Label.disabledForeground"));
        status.setIcon(DesktopIcons.icon(locked ? "lock/locked" : "lock/unlocked", 14,
            status::getForeground));
        status.setToolTipText(stateText);
        status.getAccessibleContext().setAccessibleName(stateText);
        status.setText(stateText);
        actionText = messages.text(locked ? "security.unlock" : "shell.lock");
        action.setText(actionText);
        action.setIcon(DesktopIcons.icon(locked ? "lock/unlocked" : "lock/locked", 16, action::getForeground));
        action.setToolTipText(locked ? actionText : messages.text("security.lock"));
        action.getAccessibleContext().setAccessibleName(actionText);
        action.setEnabled(configured);
        revalidate();
    }

    /** Follows sidebar width while keeping rotation and settings movement continuous. / 跟随侧栏宽度，保持转位与设置移动连续。
     * @param progress expanded-to-collapsed progress / 展开至折叠进度 */
    void presentation(double progress) {
        double next = Math.max(0, Math.min(1, progress));
        if (folded == next) return;
        folded = next;
        sizing();
        revalidate();
        repaint();
    }

    /** Aligns the state icon with the settings gear using its actual leading insets. / 按设置齿轮的实际左侧留白对齐状态图标。
     * @param settings original settings button / 原设置按钮 */
    void alignWith(JButton settings) {
        int inset = (int) Math.round(settings.getInsets().left + UIScale.scale(10 - settings.getMargin().left)
            + (settings.getIcon().getIconWidth() - UIScale.scale(14)) / 2d);
        if (status.getInsets().left != inset) status.setBorder(BorderFactory.createEmptyBorder(0, inset, 0, 0));
    }

    /** Reserves the arc above the bottom-anchored state. / 在底部固定的状态上方预留圆弧空间。 */
    private void sizing() {
        int height = (int) Math.round(UIScale.scale(46) * (1 + Math.sin(folded * Math.PI / 2)));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        setPreferredSize(new Dimension(PlatformNavigationShell.SIDEBAR_WIDTH - 16, height));
    }

    /** Follows a quarter ellipse from equal halves to evenly spaced rows. / 沿四分之一椭圆从等宽两半转为等距纵向行。 */
    @Override
    public void doLayout() {
        int width = getWidth(), height = getHeight();
        double shrink = 1 - Math.pow(1 - folded, 3), angle = folded * Math.PI / 2;
        double expandedHalfWidth = (width + PlatformNavigationShell.sidebarTravel() * folded) / 2;
        double controlWidth = expandedHalfWidth * (1 - shrink) + 38 * shrink;
        int actionWidth = Math.min(width, (int) Math.floor(controlWidth));
        int statusWidth = Math.min(width, (int) Math.ceil(controlWidth));
        int actionHeight = UIScale.scale(38);
        int statusHeight = actionHeight;
        double offsetX = expandedHalfWidth * Math.cos(angle), offsetY = UIScale.scale(46) * Math.sin(angle);
        double iconCenter = status.getInsets().left + UIScale.scale(14) / 2d;
        double centerX = iconCenter * (1 - Math.cos(angle)) + statusWidth * Math.cos(angle) / 2;
        centerX = Math.max(statusWidth / 2d, Math.min(centerX, width - actionWidth / 2d - offsetX));
        double centerY = height - statusHeight / 2d;
        int stateX = (int) Math.round(centerX - statusWidth / 2d), stateY = (int) Math.round(centerY - statusHeight / 2d);
        status.setBounds(stateX, stateY, statusWidth, statusHeight);
        action.setBounds(Math.min(width - actionWidth, (int) Math.round(stateX + statusWidth / 2d + offsetX - actionWidth / 2d)),
            (int) Math.round(stateY + statusHeight / 2d - offsetY - actionHeight / 2d), actionWidth, actionHeight);
    }

    /** Centers action content within its expanded budget and fades it without changing caption models. / 操作内容按展开宽度居中并渐隐，不更改文案模型。
     * @param graphics paint context / 绘制上下文
     * @param control original Swing control / 原 Swing 控件
     * @param icon retained icon / 保留图标
     * @param text original caption / 原文字
     * @param foreground original foreground / 原前景色
     * @param button whether to paint the action surface / 是否绘制操作底色 */
    private void paintControl(Graphics graphics, JComponent control, Icon icon, String text, Color foreground, boolean button) {
        if (icon == null) return;
        var g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            if (button) paintActionSurface(g);
            g.setFont(control.getFont());
            var metrics = control.getFontMetrics(control.getFont());
            int width = control.getWidth(), height = control.getHeight(), gap = UIScale.scale(4);
            var iconBounds = new Rectangle();
            var textBounds = new Rectangle();
            String caption = button ? SwingUtilities.layoutCompoundLabel(control, metrics, text, icon,
                SwingConstants.CENTER, SwingConstants.CENTER, SwingConstants.CENTER, SwingConstants.RIGHT,
                new Rectangle(UIScale.scale(5), 0, Math.max(0, (PlatformNavigationShell.SIDEBAR_WIDTH - 16) / 2 - UIScale.scale(10)), height),
                iconBounds, textBounds, gap) : text;
            // Compensates the lock SVG's transparent leading edge when captions are visible. / 文案可见时补偿锁图标透明边缘造成的视觉偏移。
            int expandedX = button ? iconBounds.x - UIScale.scale(1) : status.getInsets().left;
            int iconX = button ? (int) Math.round(expandedX * (1 - folded) + (width - icon.getIconWidth()) * folded / 2)
                : expandedX - status.getX();
            float enabled = control.isEnabled() ? 1f : .55f;
            g.setComposite(AlphaComposite.SrcOver.derive(enabled));
            icon.paintIcon(control, g, iconX, (height - icon.getIconHeight()) / 2);
            g.setComposite(AlphaComposite.SrcOver.derive((float) (1 - folded) * enabled));
            g.setColor(foreground);
            g.drawString(caption, iconX + (button ? textBounds.x - iconBounds.x : icon.getIconWidth() + gap),
                (height - metrics.getHeight()) / 2 + metrics.getAscent());
        } finally {
            g.dispose();
        }
    }

    /** Keeps filled hover, pressed and disabled feedback on the original action model. / 在原操作模型上保留填充悬停、按下和禁用反馈。
     * @param graphics paint context / 绘制上下文 */
    private void paintActionSurface(Graphics2D graphics) {
        Color background = action.isEnabled() ? action.getBackground() : UIManager.getColor("Button.disabledBackground");
        if (action.isEnabled()) {
            String key = action.getModel().isPressed() && action.getModel().isArmed() ? "Button.pressedBackground"
                : action.getModel().isRollover() ? "Button.hoverBackground" : "Button.background";
            Color feedback = UIManager.getColor(key);
            if (feedback != null) background = feedback;
        }
        int width = action.getWidth(), height = action.getHeight(), inset = UIScale.scale(1), arc = UIScale.scale(14);
        graphics.setColor(background);
        graphics.fillRoundRect(inset, inset, Math.max(0, width - inset * 2), Math.max(0, height - inset * 2), arc, arc);
    }

    /** Focuses the admitted sidebar security action. / 聚焦准入的侧栏安全操作。
     */
    void focusAction() {
        action.requestFocusInWindow();
    }

    /** Explains why closing requires unlock and focuses that action. / 说明关闭需要解锁并聚焦该操作。
     */
    void blockedExit() {
        action.setToolTipText(messages.text("security.exit.unlock"));
        focusAction();
    }
}
