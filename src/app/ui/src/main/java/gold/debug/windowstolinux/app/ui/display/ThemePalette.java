package gold.debug.windowstolinux.app.ui.display;

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
                new Color(245, 247, 251), Color.WHITE, new Color(228, 233, 241),
                new Color(248, 250, 253), new Color(44, 56, 76), new Color(229, 238, 255),
                new Color(48, 103, 219), new Color(45, 86, 174), new Color(104, 117, 139),
                new Color(246, 248, 252), new Color(66, 82, 105), new Color(234, 240, 250),
                new Color(218, 226, 238)
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
                new Color(20, 25, 34), new Color(28, 35, 47), new Color(45, 55, 71),
                new Color(23, 29, 40), new Color(221, 228, 239), new Color(40, 61, 92),
                new Color(65, 123, 232), new Color(159, 194, 255), new Color(155, 169, 191),
                new Color(34, 44, 60), new Color(216, 224, 239), new Color(38, 56, 82),
                new Color(57, 71, 94)
        );
    }
}
