package gold.debug.windowstolinux.app.ui.control;

import java.awt.Component;
import java.awt.Container;

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
        DesktopButtonStyle.action((javax.swing.JButton) component, palette, primary);
    }

    /** Keeps existing scroll models and view position while changing the local surface. / 更换局部表面时保留滚动模型及视图位置。 */
    static void cardScroll(javax.swing.JScrollPane scroll, ThemePalette palette) {
        scroll.putClientProperty(gold.debug.windowstolinux.app.ui.display.DesktopScrollPaneUi.CARD_SURFACE, true);
        scrollSurface(scroll, palette);
        scroll.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        if (scroll.getViewport() != null
            && scroll.getViewport().getView() instanceof javax.swing.JPanel content) {
            content.setBackground(palette.cardBackground());
            if (!"outlined-card".equals(content.getClientProperty(ROLE))) role(content, "card");
        }
    }

    /** Reapplies local colors after Swing has updated child UI delegates. / Swing 更新子控件绘制器后重新应用局部颜色。 */
    static void scrollSurface(javax.swing.JScrollPane scroll, ThemePalette palette) {
        scroll.setBackground(palette.cardBackground());
        if (scroll.getViewport() != null)
            scroll.getViewport().setBackground(palette.cardBackground());
        for (var bar : new javax.swing.JScrollBar[]{scroll.getVerticalScrollBar(), scroll.getHorizontalScrollBar()})
            if (bar != null) bar.setBackground(palette.cardBackground());
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
                case "card", "output", "card-inventory", "outlined-card" -> {
                    component.setBackground(palette.cardBackground());
                    component.setForeground(palette.sidebarForeground());
                    if (role.equals("outlined-card"))
                        ((RoundedCard) component).outlineColor(palette.emphasisBackground());
                    if (role.equals("card-inventory") && component.getParent() instanceof javax.swing.JViewport viewport
                        && viewport.getParent() instanceof javax.swing.JScrollPane scroll)
                        scrollSurface(scroll, palette);
                }
                case "card-scroll" -> cardScroll((javax.swing.JScrollPane) component, palette);
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
                case "divider" -> component.setForeground(palette.cardBorder());
                case "card-divider" -> component.setForeground(palette.pageBackground());
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
