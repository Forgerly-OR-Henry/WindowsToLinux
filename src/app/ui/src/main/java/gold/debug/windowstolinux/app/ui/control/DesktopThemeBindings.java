package gold.debug.windowstolinux.app.ui.control;

import java.awt.Component;
import java.awt.Container;
import java.awt.Color;

import javax.swing.JComponent;

import gold.debug.windowstolinux.app.ui.display.ThemePalette;

/** Retains explicit palette roles when updating existing themed controls. / 更新已有主题控件时保留明确的配色角色。 */
final class DesktopThemeBindings {
    /** Original themed surface role. / 原主题界面角色。 */
    private static final String ROLE = DesktopThemeBindings.class.getName();

    /** Logical custom font retained across theme and zoom updates. / 主题和缩放更新间保留的逻辑自定义字体。 */
    private static final String FONT = ROLE + ".font";

    /** Prevents construction of this stateless helper. / 禁止构造无状态工具。 */
    private DesktopThemeBindings() {
    }

    /** Registers a role chosen by the component factory. / 登记由组件工厂选择的角色。
     * @param <T> actual component type / 实际组件类型
     * @param component original component / 原组件
     * @param role declared palette role / 声明的配色角色
     * @return original component / 原组件 */
    static <T extends JComponent> T role(T component, String role) {
        component.putClientProperty(ROLE, role);
        return component;
    }

    /** Binds a custom logical font to the existing scale refresh. / 将自定义逻辑字体绑定到既有缩放刷新。
     * @param component original control / 原控件
     * @param font logical font before user scaling / 用户缩放前的逻辑字体 */
    static void font(JComponent component, java.awt.Font font) {
        component.putClientProperty(FONT, font);
        component.setFont(font.deriveFont(com.formdev.flatlaf.util.UIScale.scale(font.getSize2D())));
    }

    /** Keeps button hover and pressed states distinct without a static outline. / 按钮悬停及按下状态以颜色区分，不使用静态描边。
     * @param component original action / 原操作控件
     * @param palette current palette / 当前配色
     * @param primary primary action / 是否主要操作 */
    static void button(JComponent component, ThemePalette palette, boolean primary) {
        component.setBackground(primary ? palette.accent() : palette.secondaryButtonBackground());
        component.setForeground(primary ? Color.WHITE : palette.secondaryButtonForeground());
        component.putClientProperty("FlatLaf.style", java.util.Map.of("arc", 8, "borderWidth", 0, "focusWidth", 1,
            "hoverBackground", primary ? palette.accentDark() : palette.navigationActive(),
            "pressedBackground", primary ? palette.accentDark() : palette.navigationActive()));
    }

    /** Refreshes only factory-declared palette roles, without comparing old colors. / 仅刷新工厂声明的配色角色，不比较旧颜色。
     * @param root original component tree / 原组件树
     * @param palette confirmed effective palette / 已确认生效配色 */
    static void refresh(Component root, ThemePalette palette) {
        if (root instanceof JComponent component && component.getClientProperty(FONT) instanceof java.awt.Font font)
            component.setFont(font.deriveFont(com.formdev.flatlaf.util.UIScale.scale(font.getSize2D())));
        if (root instanceof JComponent component && component.getClientProperty(ROLE) instanceof String role) {
            switch (role) {
                case "page" -> component.setBackground(palette.pageBackground());
                case "card", "output" -> {
                    component.setBackground(palette.cardBackground());
                    component.setForeground(palette.sidebarForeground());
                }
                case "primary" -> {
                    button(component, palette, true);
                }
                case "secondary" -> {
                    button(component, palette, false);
                }
                case "badge" -> {
                    component.setBackground(palette.badgeBackground());
                    component.setForeground(palette.accentDark());
                }
                case "title" -> component.setForeground(palette.sidebarForeground());
                case "detail" -> component.setForeground(palette.subduedText());
                case "number" -> component.setForeground(palette.accent());
                case "scroll" -> {
                    component.setBorder(javax.swing.BorderFactory.createEmptyBorder());
                    if (component instanceof javax.swing.JScrollPane scroll)
                        scroll.getViewport().setBackground(palette.cardBackground());
                }
                default -> throw new IllegalStateException("Unknown declared theme role: " + role);
            }
        }
        if (root instanceof Container container)
            for (Component child : container.getComponents())
                refresh(child, palette);
    }
}
