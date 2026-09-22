package gold.debug.windowstolinux.app.ui.display;

import java.util.Locale;
import java.util.Objects;

import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;

/**
 * Persistable display preferences selected in the desktop settings page.
 *
 *  <p>在桌面设置页面选择的可持久化显示偏好。
 *
 * @param localeTag locale tag / 区域标签
 * @param themeMode theme mode / 主题模式
 */
public record DesktopDisplayConfiguration(String localeTag, ThemeMode themeMode) {
    /**
     * Validates and binds the inputs required by desktop display configuration.
     * <p>校验并绑定Desktop显示配置所需输入。
     *
     * @param localeTag locale tag / 区域标签
     * @param themeMode theme mode / 主题模式
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DesktopDisplayConfiguration {
        localeTag = Objects.requireNonNull(localeTag, "localeTag");
        themeMode = Objects.requireNonNull(themeMode, "themeMode");
    }

    /**
     * Returns defaults.
     * <p>返回默认集合。
     *
     * @return the operation result / 操作结果
     */
    public static DesktopDisplayConfiguration defaults() {
        return defaults(Locale.getDefault());
    }

    /**
     * Builds desktop display configuration from the supplied defaults inputs.
     * <p>根据所提供默认集合输入构建Desktop显示配置。
     *
     * @param systemLocale system locale / 系统区域
     * @return the operation result / 操作结果
     */
    public static DesktopDisplayConfiguration defaults(Locale systemLocale) {
        return new DesktopDisplayConfiguration(MessageCatalog.defaultLanguageTag(systemLocale), ThemeMode.SYSTEM);
    }

    /**
     * Creates a value through {@code fromStoredValues}.
     *
     *  <p>通过 {@code fromStoredValues} 创建值。
     *
     * @param storedLocaleTag stored locale tag / 已存储区域标签
     * @param storedThemeMode stored theme mode / 已存储主题模式
     * @param systemLocale system locale / 系统区域
     * @return the operation result / 操作结果
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static DesktopDisplayConfiguration fromStoredValues(String storedLocaleTag, String storedThemeMode,
            Locale systemLocale) {
        Objects.requireNonNull(systemLocale, "systemLocale");
        String localeTag = storedLocaleTag == null
                ? MessageCatalog.defaultLanguageTag(systemLocale)
                : MessageCatalog.normalizeLanguageTag(storedLocaleTag);
        return new DesktopDisplayConfiguration(localeTag, ThemeMode.fromStoredValue(storedThemeMode));
    }
}
