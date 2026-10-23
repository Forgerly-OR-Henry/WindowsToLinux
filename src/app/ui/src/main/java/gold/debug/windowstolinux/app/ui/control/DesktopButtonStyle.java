package gold.debug.windowstolinux.app.ui.control;

import java.awt.Color;
import java.util.Map;

import javax.swing.JButton;

import gold.debug.windowstolinux.app.ui.display.ThemePalette;

/** Shared button surfaces and feedback, independent of page layout. / 与页面布局独立的共用按钮表面及反馈。 */
public final class DesktopButtonStyle {
    /** Prevents construction of the style helper. / 禁止构造样式工具。 */
    private DesktopButtonStyle() {
    }

    /** Applies the declared action role without changing listeners or geometry. / 应用声明的动作角色，不改变监听器及尺寸。
     * @param button original action / 原动作
     * @param palette effective palette / 生效配色
     * @param primary primary action / 是否主动作 */
    static void action(JButton button, ThemePalette palette, boolean primary) {
        Color background = primary ? palette.accent() : palette.secondaryButtonBackground();
        Color hover = blend(background, primary ? Color.BLACK : palette.accent(), primary ? 0.12f : 0.10f);
        Color pressed = blend(background, primary ? Color.BLACK : palette.accent(), primary ? 0.24f : 0.20f);
        surface(button, background, primary ? Color.WHITE : palette.secondaryButtonForeground(), hover, pressed);
    }

    /** Shares rounded feedback across platform and author navigation. / 平台及作者导航共用圆角反馈。
     * @param button original navigation action / 原导航动作
     * @param background current location surface / 当前位置底色
     * @param foreground readable label color / 可读标签色
     * @param hover hover feedback / 悬停反馈
     * @param pressed pressed feedback / 按下反馈 */
    public static void navigation(JButton button, Color background, Color foreground, Color hover, Color pressed) {
        surface(button, background, foreground, hover, pressed);
    }

    /** Installs filled pointer states without an outer focus frame. / 安装填充指针状态，不绘制外层焦点框。
     * @param button original button / 原按钮
     * @param background resting surface / 静止底色
     * @param foreground label color / 标签色
     * @param hover hover surface / 悬停底色
     * @param pressed pressed surface / 按下底色 */
    private static void surface(JButton button, Color background, Color foreground, Color hover, Color pressed) {
        button.setOpaque(false);
        button.setBorderPainted(true);
        button.setFocusPainted(true);
        button.setBackground(background);
        button.setForeground(foreground);
        button.putClientProperty("FlatLaf.style", Map.ofEntries(
            Map.entry("arc", 14), Map.entry("borderWidth", 0), Map.entry("focusWidth", 0),
            Map.entry("focusedBackground", background), Map.entry("hoverBackground", hover),
            Map.entry("pressedBackground", pressed), Map.entry("default.background", background),
            Map.entry("default.foreground", foreground), Map.entry("default.focusedBackground", background),
            Map.entry("default.hoverBackground", hover), Map.entry("default.pressedBackground", pressed)));
    }

    /** Blends feedback colors without introducing another palette. / 混合反馈色，不引入另一套配色。
     * @param base resting color / 静止颜色
     * @param tint feedback tint / 反馈色
     * @param weight tint proportion / 反馈色比例
     * @return mixed color / 混合颜色 */
    private static Color blend(Color base, Color tint, float weight) {
        return new Color(Math.round(base.getRed() * (1 - weight) + tint.getRed() * weight),
            Math.round(base.getGreen() * (1 - weight) + tint.getGreen() * weight),
            Math.round(base.getBlue() * (1 - weight) + tint.getBlue() * weight));
    }
}
