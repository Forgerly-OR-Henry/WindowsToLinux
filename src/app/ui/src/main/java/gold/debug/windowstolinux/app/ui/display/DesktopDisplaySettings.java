package gold.debug.windowstolinux.app.ui.display;

import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;

import java.util.Objects;
import java.util.Locale;

/**
 * Persistable display preferences selected in the desktop settings page.
 *
 * <p>在桌面设置页面选择的可持久化显示偏好。
 *
 * @param localeTag the {@code localeTag} value / {@code localeTag} 值
 * @param themeMode the {@code themeMode} value / {@code themeMode} 值
 */
public record DesktopDisplaySettings(String localeTag, ThemeMode themeMode) {
    /**
     * Creates a {@code DesktopDisplaySettings} instance.
     *
     * <p>创建 {@code DesktopDisplaySettings} 实例。
     *
     * @param localeTag the {@code localeTag} value / {@code localeTag} 值
     * @param themeMode the {@code themeMode} value / {@code themeMode} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public DesktopDisplaySettings {
        localeTag = Objects.requireNonNull(localeTag, "localeTag");
        themeMode = Objects.requireNonNull(themeMode, "themeMode");
    }

    /**
     * Performs the {@code defaults} operation.
     *
     * <p>执行 {@code defaults} 操作。
     *
     * @return the operation result / 操作结果
     */
    public static DesktopDisplaySettings defaults() {
        return defaults(Locale.getDefault());
    }

    /**
     * Performs the {@code defaults} operation.
     *
     * <p>执行 {@code defaults} 操作。
     *
     * @param systemLocale the {@code systemLocale} value / {@code systemLocale} 值
     * @return the operation result / 操作结果
     */
    public static DesktopDisplaySettings defaults(Locale systemLocale) {
        return new DesktopDisplaySettings(MessageCatalog.defaultLanguageTag(systemLocale), ThemeMode.SYSTEM);
    }

    /**
     * Creates a value through {@code fromStoredValues}.
     *
     * <p>通过 {@code fromStoredValues} 创建值。
     *
     * @param storedLocaleTag the {@code storedLocaleTag} value / {@code storedLocaleTag} 值
     * @param storedThemeMode the {@code storedThemeMode} value / {@code storedThemeMode} 值
     * @param systemLocale the {@code systemLocale} value / {@code systemLocale} 值
     * @return the operation result / 操作结果
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public static DesktopDisplaySettings fromStoredValues(String storedLocaleTag, String storedThemeMode,
                                                     Locale systemLocale) {
        Objects.requireNonNull(systemLocale, "systemLocale");
        String localeTag = storedLocaleTag == null
                ? MessageCatalog.defaultLanguageTag(systemLocale)
                : MessageCatalog.normalizeLanguageTag(storedLocaleTag);
        return new DesktopDisplaySettings(localeTag, ThemeMode.fromStoredValue(storedThemeMode));
    }
}
