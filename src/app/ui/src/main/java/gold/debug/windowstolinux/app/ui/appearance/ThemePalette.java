package gold.debug.windowstolinux.app.ui.appearance;

import java.awt.Color;

/**
 * Immutable application colors for one effective light or dark appearance.
 *
 * <p>单个生效浅色或深色外观使用的不可变应用颜色。
 *
 * @param pageBackground the {@code pageBackground} value / {@code pageBackground} 值
 * @param cardBackground the {@code cardBackground} value / {@code cardBackground} 值
 * @param cardBorder the {@code cardBorder} value / {@code cardBorder} 值
 * @param sidebarBackground the {@code sidebarBackground} value / {@code sidebarBackground} 值
 * @param sidebarForeground the {@code sidebarForeground} value / {@code sidebarForeground} 值
 * @param navigationActive the {@code navigationActive} value / {@code navigationActive} 值
 * @param accent the {@code accent} value / {@code accent} 值
 * @param accentDark the {@code accentDark} value / {@code accentDark} 值
 * @param subduedText the {@code subduedText} value / {@code subduedText} 值
 * @param secondaryButtonBackground the {@code secondaryButtonBackground} value / {@code secondaryButtonBackground} 值
 * @param secondaryButtonForeground the {@code secondaryButtonForeground} value / {@code secondaryButtonForeground} 值
 * @param badgeBackground the {@code badgeBackground} value / {@code badgeBackground} 值
 * @param inputBorder the {@code inputBorder} value / {@code inputBorder} 值
 */
public record ThemePalette(
        Color pageBackground,
        Color cardBackground,
        Color cardBorder,
        Color sidebarBackground,
        Color sidebarForeground,
        Color navigationActive,
        Color accent,
        Color accentDark,
        Color subduedText,
        Color secondaryButtonBackground,
        Color secondaryButtonForeground,
        Color badgeBackground,
        Color inputBorder
) {
    /**
     * Performs the {@code light} operation.
     *
     * <p>执行 {@code light} 操作。
     *
     * @return the operation result / 操作结果
     */
    public static ThemePalette light() {
        return new ThemePalette(
                new Color(246, 248, 252), Color.WHITE, new Color(224, 229, 239),
                new Color(20, 32, 54), new Color(214, 223, 240), new Color(49, 96, 184),
                new Color(42, 108, 224), new Color(30, 86, 186), new Color(96, 107, 126),
                new Color(237, 242, 251), new Color(42, 61, 89), new Color(229, 239, 255),
                new Color(232, 236, 244)
        );
    }

    /**
     * Performs the {@code dark} operation.
     *
     * <p>执行 {@code dark} 操作。
     *
     * @return the operation result / 操作结果
     */
    public static ThemePalette dark() {
        return new ThemePalette(
                new Color(31, 35, 43), new Color(42, 47, 56), new Color(69, 76, 89),
                new Color(17, 24, 39), new Color(211, 220, 236), new Color(57, 110, 202),
                new Color(86, 156, 255), new Color(159, 202, 255), new Color(184, 195, 213),
                new Color(58, 67, 82), new Color(225, 232, 242), new Color(45, 71, 111),
                new Color(83, 91, 106)
        );
    }
}
