package gold.debug.windowstolinux.app.ui.display;

import java.awt.Color;

/**
 * Immutable application colors for one effective light or dark appearance.
 *
 *  <p>单个生效浅色或深色外观使用的不可变应用颜色。
 *
 * @param pageBackground page background / 页面背景
 * @param cardBackground card background / 卡片背景
 * @param cardBorder card border / 卡片Border
 * @param sidebarBackground sidebar background / 侧栏背景
 * @param sidebarForeground sidebar foreground / 侧栏前景
 * @param navigationActive navigation active / 导航活跃
 * @param accent accent / 强调色
 * @param accentDark accent dark / 强调色深色
 * @param subduedText subdued text / 弱化文本
 * @param secondaryButtonBackground secondary button background / 次要按钮背景
 * @param secondaryButtonForeground secondary button foreground / 次要按钮前景
 * @param badgeBackground badge background / 标记背景
 * @param inputBorder input border / 输入Border
 */
public record ThemePalette(Color pageBackground, Color cardBackground, Color cardBorder, Color sidebarBackground,
                           Color sidebarForeground, Color navigationActive, Color accent, Color accentDark,
                           Color subduedText,
                           Color secondaryButtonBackground, Color secondaryButtonForeground, Color badgeBackground,
                           Color inputBorder) {
    /**
     * Builds theme palette from the supplied light inputs.
     * <p>根据所提供浅色输入构建主题配色。
     *
     * @return the operation result / 操作结果
     */
    public static ThemePalette light() {
        return new ThemePalette(new Color(230, 230, 230), new Color(250, 250, 250), new Color(218, 220, 224),
            new Color(230, 230, 230), new Color(48, 50, 56), new Color(212, 226, 255), new Color(53, 116, 240),
            new Color(42, 93, 192), new Color(108, 112, 126), new Color(210, 210, 210), new Color(48, 50, 56),
            new Color(229, 237, 252), new Color(187, 190, 197));
    }

    /**
     * Builds theme palette from the supplied dark inputs.
     * <p>根据所提供深色输入构建主题配色。
     *
     * @return the operation result / 操作结果
     */
    public static ThemePalette dark() {
        return new ThemePalette(new Color(30, 30, 30), new Color(10, 10, 10), new Color(67, 69, 74),
            new Color(30, 30, 30), new Color(223, 225, 229), new Color(46, 67, 110), new Color(53, 116, 240),
            new Color(84, 138, 247), new Color(157, 160, 168), new Color(50, 50, 50), new Color(223, 225, 229),
            new Color(49, 59, 79), new Color(90, 93, 99));
    }
}
