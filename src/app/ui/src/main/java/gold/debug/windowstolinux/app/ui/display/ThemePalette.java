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
        Color sidebarForeground, Color navigationActive, Color accent, Color accentDark, Color subduedText,
        Color secondaryButtonBackground, Color secondaryButtonForeground, Color badgeBackground, Color inputBorder) {
    /**
     * Builds theme palette from the supplied light inputs.
     * <p>根据所提供浅色输入构建主题配色。
     *
     * @return the operation result / 操作结果
     */
    public static ThemePalette light() {
        return new ThemePalette(new Color(245, 247, 251), Color.WHITE, new Color(228, 233, 241),
                new Color(248, 250, 253), new Color(44, 56, 76), new Color(229, 238, 255), new Color(48, 103, 219),
                new Color(45, 86, 174), new Color(104, 117, 139), new Color(228, 234, 244), new Color(44, 56, 76),
                new Color(234, 240, 250), new Color(218, 226, 238));
    }

    /**
     * Builds theme palette from the supplied dark inputs.
     * <p>根据所提供深色输入构建主题配色。
     *
     * @return the operation result / 操作结果
     */
    public static ThemePalette dark() {
        return new ThemePalette(new Color(20, 25, 34), new Color(28, 35, 47), new Color(45, 55, 71),
                new Color(23, 29, 40), new Color(221, 228, 239), new Color(40, 61, 92), new Color(65, 123, 232),
                new Color(159, 194, 255), new Color(155, 169, 191), new Color(49, 62, 82), new Color(221, 228, 239),
                new Color(38, 56, 82), new Color(57, 71, 94));
    }
}
