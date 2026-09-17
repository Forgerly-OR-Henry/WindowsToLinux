package gold.debug.windowstolinux.app.ui.component;

import com.formdev.flatlaf.util.UIScale;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JCheckBox;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
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

/** A trailing switch retaining native checkbox state, keyboard and accessibility behavior. / 保留原生复选状态、键盘与无障碍行为的后置开关。 */
public final class ToggleSwitch extends JCheckBox {
    private boolean keyboardFocus;

    /** Creates an initially off switch without a duplicate field label. / 创建默认关闭且不重复字段说明的开关。 */
    public ToggleSwitch() { this(null, false); }

    /** Creates a labeled switch with an explicit initial state. / 创建带文字和明确初始状态的开关。 */
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
        setIcon(icon); setSelectedIcon(icon); setDisabledIcon(icon); setDisabledSelectedIcon(icon);
        addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent event) {
                keyboardFocus = event.getCause() != FocusEvent.Cause.MOUSE_EVENT; repaint();
            }
            @Override public void focusLost(FocusEvent event) { keyboardFocus = false; repaint(); }
        });
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { keyboardFocus = false; repaint(); }
        });
    }

    private static final class SwitchIcon implements Icon {
        @Override public int getIconWidth() { return UIScale.scale(44); }
        @Override public int getIconHeight() { return UIScale.scale(26); }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            ToggleSwitch control = (ToggleSwitch) component;
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.translate(x, y); UIScale.scaleGraphics(g);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (!control.isEnabled()) g.setComposite(AlphaComposite.SrcOver.derive(0.45f));
                Color accent = UIManager.getColor("Component.focusColor");
                Color track = UIManager.getColor(control.isSelected() ? "Component.focusColor" : "Component.borderColor");
                if (track == null) track = control.getForeground();
                if (accent == null) accent = control.getForeground();
                g.setColor(control.getModel().isPressed() ? track.darker() : track);
                g.fillRoundRect(2, 3, 40, 20, 20, 20);
                g.setColor(Color.WHITE);
                int thumb = control.isSelected() ? 24 : 4;
                g.fillOval(thumb, 5, 16, 16);
                if (control.keyboardFocus && control.isFocusOwner() && control.isEnabled()) {
                    g.setColor(accent); g.fillOval(thumb + 6, 11, 4, 4);
                }
            } finally { g.dispose(); }
        }
    }
}
