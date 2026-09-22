package gold.debug.windowstolinux.app.ui.component;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JCheckBox;
import javax.swing.SwingConstants;
import javax.swing.UIManager;

import com.formdev.flatlaf.util.UIScale;

/**
 * A trailing switch retaining native checkbox state, keyboard and accessibility behavior. / 保留原生复选状态、键盘与无障碍行为的后置开关。
 */
public final class ToggleSwitch extends JCheckBox {
    /**
     * Keyboard focus.
     * <p>键盘焦点。
     */
    private boolean keyboardFocus;

    /**
     * Creates an initially off switch without a duplicate field label. / 创建默认关闭且不重复字段说明的开关。
     */
    public ToggleSwitch() {
        this(null, false);
    }

    /**
     * Creates a labeled switch with an explicit initial state. / 创建带文字和明确初始状态的开关。
     *
     * @param text bounded text consumed or produced by the current formatter / 当前格式化器消费或生成的有界文本
     * @param selected explicitly selected item or state / 显式选择的项目或状态
     */
    public ToggleSwitch(String text, boolean selected) {
        super(text, selected);
        setOpaque(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setRolloverEnabled(true);
        setHorizontalAlignment(SwingConstants.LEFT);
        setHorizontalTextPosition(SwingConstants.LEFT);
        setIconTextGap(UIScale.scale(10));
        setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        Icon icon = new SwitchIcon();
        setIcon(icon);
        setSelectedIcon(icon);
        setDisabledIcon(icon);
        setDisabledSelectedIcon(icon);
        addFocusListener(new FocusAdapter() {
            /**
             * Updates the associated control when it receives keyboard focus.
             * <p>在关联控件获得键盘焦点时更新控件。
             *
             * @param event state or UI event being processed / 正在处理的状态或 UI 事件
             */
            @Override
            public void focusGained(FocusEvent event) {
                keyboardFocus = event.getCause() != FocusEvent.Cause.MOUSE_EVENT;
                repaint();
            }

            /**
             * Updates the associated control when keyboard focus leaves it.
             * <p>在键盘焦点离开关联控件时更新控件。
             *
             * @param event state or UI event being processed / 正在处理的状态或 UI 事件
             */
            @Override
            public void focusLost(FocusEvent event) {
                keyboardFocus = false;
                repaint();
            }
        });
        addMouseListener(new MouseAdapter() {
            /**
             * Handles the start of a mouse interaction with the associated control.
             * <p>处理与关联控件的鼠标交互开始。
             *
             * @param event state or UI event being processed / 正在处理的状态或 UI 事件
             */
            @Override
            public void mousePressed(MouseEvent event) {
                keyboardFocus = false;
                repaint();
            }
        });
    }

    /**
     * Paints the toggle-switch state with the selected desktop palette.
     * <p>使用所选桌面配色绘制开关状态。
     */
    private static final class SwitchIcon implements Icon {
        /**
         * Returns icon width.
         * <p>返回图标宽度。
         *
         * @return icon width / 图标宽度
         */
        @Override
        public int getIconWidth() {
            return UIScale.scale(44);
        }

        /**
         * Returns icon height.
         * <p>返回图标高度。
         *
         * @return icon height / 图标高度
         */
        @Override
        public int getIconHeight() {
            return UIScale.scale(26);
        }

        /**
         * Paints the toggle track, thumb and focus appearance at the supplied pixel origin using a disposable graphics copy.
         * <p>使用可释放图形副本，在指定像素起点绘制开关轨道、滑块及焦点外观。
         *
         * @param component component / 组件
         * @param graphics graphics / 图形
         * @param x horizontal paint origin in pixels / 绘制起点的水平坐标，单位为像素
         * @param y vertical paint origin in pixels / 绘制起点的垂直坐标，单位为像素
         */
        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            ToggleSwitch control = (ToggleSwitch) component;
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.translate(x, y);
                UIScale.scaleGraphics(g);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (!control.isEnabled())
                    g.setComposite(AlphaComposite.SrcOver.derive(0.45f));
                Color accent = UIManager.getColor("Component.focusColor");
                Color track = UIManager
                        .getColor(control.isSelected() ? "Component.focusColor" : "Component.borderColor");
                if (track == null)
                    track = control.getForeground();
                if (accent == null)
                    accent = control.getForeground();
                g.setColor(control.getModel().isPressed() ? track.darker() : track);
                g.fillRoundRect(2, 3, 40, 20, 20, 20);
                g.setColor(Color.WHITE);
                int thumb = control.isSelected() ? 24 : 4;
                g.fillOval(thumb, 5, 16, 16);
                if (control.keyboardFocus && control.isFocusOwner() && control.isEnabled()) {
                    g.setColor(accent);
                    g.fillOval(thumb + 6, 11, 4, 4);
                }
            } finally {
                g.dispose();
            }
        }
    }
}
