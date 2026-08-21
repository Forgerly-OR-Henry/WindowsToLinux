package gold.debug.windowstolinux.app.ui.display;

import java.util.Locale;

/**
 * User-selectable desktop appearance preference.
 *
 * <p>用户可选择的桌面外观偏好。
 */
public enum ThemeMode {
    /**
     * Represents the {@code SYSTEM} option.
     *
     * <p>表示 {@code SYSTEM} 选项。
     */
    SYSTEM,
    /**
     * Represents the {@code LIGHT} option.
     *
     * <p>表示 {@code LIGHT} 选项。
     */
    LIGHT,
    /**
     * Represents the {@code DARK} option.
     *
     * <p>表示 {@code DARK} 选项。
     */
    DARK;

    /**
     * Creates a value through {@code fromStoredValue}.
     *
     * <p>通过 {@code fromStoredValue} 创建值。
     *
     * @param value the {@code value} value / {@code value} 值
     * @return the operation result / 操作结果
     */
    public static ThemeMode fromStoredValue(String value) {
        if (value == null || value.isBlank()) {
            return SYSTEM;
        }
        try {
            return ThemeMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            // An unknown persisted preference safely falls back to the system theme. / 未知的持久化偏好安全回退到系统主题。
            return SYSTEM;
        }
    }
}
