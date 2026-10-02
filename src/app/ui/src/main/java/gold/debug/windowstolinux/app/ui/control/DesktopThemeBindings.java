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

    /** Refreshes only factory-declared palette roles, without comparing old colors. / 仅刷新工厂声明的配色角色，不比较旧颜色。
     * @param root original component tree / 原组件树
     * @param palette confirmed effective palette / 已确认生效配色 */
    static void refresh(Component root, ThemePalette palette) {
        if (root instanceof JComponent component && component.getClientProperty(ROLE) instanceof String role) {
            switch (role) {
                case "page" -> component.setBackground(palette.pageBackground());
                case "card", "output" -> {
                    component.setBackground(palette.cardBackground());
                    component.setForeground(palette.sidebarForeground());
                }
                case "primary" -> {
                    component.setBackground(palette.accent());
                    component.setForeground(Color.WHITE);
                }
                case "secondary" -> {
                    component.setBackground(palette.secondaryButtonBackground());
                    component.setForeground(palette.secondaryButtonForeground());
                }
                case "badge" -> {
                    component.setBackground(palette.badgeBackground());
                    component.setForeground(palette.accentDark());
                }
                case "title" -> component.setForeground(palette.sidebarForeground());
                case "detail" -> component.setForeground(palette.subduedText());
                case "number" -> component.setForeground(palette.accent());
                case "scroll" -> {
                    component.setBorder(javax.swing.BorderFactory.createMatteBorder(1, 0, 0, 0, palette.cardBorder()));
                    if (component instanceof javax.swing.JScrollPane scroll)
                        scroll.getViewport().setBackground(palette.cardBackground());
                }
                default -> throw new IllegalStateException("Unknown declared theme role: " + role);
            }
            if (component instanceof RoundedCard card)
                card.outline(palette.cardBorder());
        }
        if (root instanceof Container container)
            for (Component child : container.getComponents())
                refresh(child, palette);
    }
}
